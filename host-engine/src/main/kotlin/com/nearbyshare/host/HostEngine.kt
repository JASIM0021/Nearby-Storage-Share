package com.nearbyshare.host

import com.nearbyshare.core.network.protocol.ErrorCode
import com.nearbyshare.core.network.protocol.OpException
import com.nearbyshare.core.network.protocol.Permissions
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import com.nearbyshare.lan.DownloadSource
import com.nearbyshare.lan.HostBackend
import com.nearbyshare.lan.PairingRequest
import com.nearbyshare.lan.PeerRecord
import com.nearbyshare.lan.toHexString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The host's brain on a PC. Implements what the network server needs ([HostBackend]) with the same rules as the Android app:
 * every operation is authorized against the database each time, quota is reserved atomically before bytes are accepted and
 * enforced again as they arrive, uploads are staged and verified before they appear, and guests are kept apart.
 *
 * It also offers the administration operations and live state the host's screen needs.
 */
class HostEngine(dataDir: File, private val scope: CoroutineScope) : HostBackend, AutoCloseable {
    private val dataRoot = dataDir.also { it.mkdirs() }
    private val store = HostStore(File(dataDir, "host.db"))
    private val privateStore = PrivateStore(File(dataDir, "shared_storage"))
    private val visible = VisibleStore()
    private val reserveMutex = Mutex()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Set by the app so the network server can drop a device's connections when it is revoked or blocked. */
    @Volatile var onDeviceDropped: (String) -> Unit = {}

    /** Live byte counters of running transfers, for a progress display. */
    @Volatile var progressListener: (transferId: String, bytes: Long) -> Unit = { _, _ -> }

    // ---- observable state -------------------------------------------------------------------------------------------------

    private val _config = MutableStateFlow(store.config())
    val config: StateFlow<HostConfig> = _config.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceRow>>(emptyList())
    val devices: StateFlow<List<DeviceRow>> = _devices.asStateFlow()

    private val _usage = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Bytes stored per guest device id. */
    val usage: StateFlow<Map<String, Long>> = _usage.asStateFlow()

    private val _used = MutableStateFlow(0L)

    /** Bytes guests currently occupy, in total. */
    val usedBytes: StateFlow<Long> = _used.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingPairing>>(emptyList())
    val pending: StateFlow<List<PendingPairing>> = _pending.asStateFlow()

    private val _audit = MutableStateFlow<List<AuditRec>>(emptyList())
    val recentAudit: StateFlow<List<AuditRec>> = _audit.asStateFlow()

    /** Increments whenever stored data changes, so screens can re-read per-device lists. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    init {
        // A fresh start has nothing in flight: release every reservation and drop half-written files.
        store.inFlight().forEach { t -> t.stagingKey?.let { removeStaging(it) } }
        store.failAllInFlight(now())
        privateStore.clearStaging()
        clearOpenCache()
        refresh()
    }

    private fun now() = System.currentTimeMillis()

    fun refresh() {
        _config.value = store.config()
        val accesses = store.accesses().associateBy { it.deviceId }
        _devices.value = store.devices().map { DeviceRow(it, accesses[it.deviceId]) }
        _usage.value = store.usageByDevice()
        _used.value = store.sumCommitted()
        _audit.value = store.recentAudit(40)
        _changes.update { it + 1 }
    }

    // ---- authorization ---------------------------------------------------------------------------------------------------------

    private enum class Need { ANY, READ, WRITE, DELETE, FOLDERS }

    private class Authz(val config: HostConfig, val access: Access)

    private fun authorize(deviceId: String, need: Need): Authz {
        val cfg = store.config()
        if (!cfg.sharingEnabled) throw OpException(ErrorCode.SHARING_OFF, "Sharing is turned off")
        val device = store.device(deviceId)
        if (device == null || device.trustState != TrustState.APPROVED) throw OpException(ErrorCode.DENIED, "Access denied")
        val acc = store.access(deviceId) ?: throw OpException(ErrorCode.DENIED, "Access denied")
        if (acc.revokedAt != null) throw OpException(ErrorCode.DENIED, "Access revoked")
        if (acc.expiresAt != null && acc.expiresAt < now()) throw OpException(ErrorCode.EXPIRED, "Access expired")
        val allowed = when (need) {
            Need.ANY -> true
            Need.READ -> acc.canRead
            Need.WRITE -> acc.canWrite
            Need.DELETE -> acc.canDelete
            Need.FOLDERS -> acc.canCreateFolders
        }
        if (!allowed) throw OpException(ErrorCode.DENIED, "Permission denied")
        return Authz(cfg, acc)
    }

    // ---- HostBackend: connection decisions ---------------------------------------------------------------------------------

    override suspend fun sharingEnabled() = store.config().sharingEnabled
    override suspend fun maxGuests() = store.config().maxGuests

    override suspend fun findPeer(certFingerprint: String): PeerRecord? {
        val d = store.deviceByFp(certFingerprint) ?: return null
        return if (d.trustState == TrustState.APPROVED) PeerRecord(d.deviceId, d.displayName) else null
    }

    override suspend fun isBlocked(deviceId: String) = store.device(deviceId)?.trustState == TrustState.BLOCKED

    override suspend fun requestPairingApproval(request: PairingRequest): Boolean {
        val cfg = store.config()
        val known = store.device(request.deviceId)
        return when {
            known?.trustState == TrustState.BLOCKED -> false
            known == null && cfg.accessMode == AccessMode.PRIVATE -> false.also { audit(request.deviceId, "PAIR", "REJECTED: host is Private") }
            known == null && cfg.accessMode == AccessMode.OPEN -> true
            // a new device, or a known one pairing again (it keeps its files and permissions): the host confirms the code
            else -> ask(request, rePair = known != null)
        }
    }

    private suspend fun ask(request: PairingRequest, rePair: Boolean): Boolean {
        val key = "pair:${request.id}"
        val answer = CompletableDeferred<Boolean>()
        waiting[key] = answer
        _pending.update { it + PendingPairing(key, request.deviceId, request.name, request.sas, rePair) }
        try {
            return answer.await()
        } finally { // also when the guest gives up: the prompt disappears
            waiting.remove(key)
            _pending.update { list -> list.filterNot { it.key == key } }
        }
    }

    /** The host's person pressed Allow or Deny. */
    fun resolvePairing(key: String, approve: Boolean) {
        waiting[key]?.complete(approve)
    }

    override suspend fun registerPeer(certFingerprint: String, deviceId: String, name: String) {
        val t = now()
        if (store.device(deviceId) != null) {
            store.updatePairing(deviceId, name, certFingerprint, t) // keeps files and permissions
        } else {
            val c = store.config()
            store.tx {
                store.insertDevice(Device(deviceId, name, TrustState.APPROVED, t, t, certFingerprint))
                store.saveAccess(Access(deviceId, c.defaultCanRead, c.defaultCanWrite, c.defaultCanOverwrite, c.defaultCanDelete, c.defaultCanCreateFolders))
            }
        }
        refresh()
    }

    // ---- HostBackend: file operations ----------------------------------------------------------------------------------------------

    private fun FileRec.remote() = RemoteFile(fileId, displayName, isFolder, sizeBytes, updatedAt, parentFileId)

    private fun deviceCap(acc: Access, cfg: HostConfig) = minOf(acc.quotaOverrideBytes ?: cfg.quotaBytes, cfg.quotaBytes)

    override suspend fun storageInfo(deviceId: String): StorageInfo {
        val a = authorize(deviceId, Need.ANY)
        val cap = deviceCap(a.access, a.config)
        val used = store.sumCommittedBy(deviceId) + store.sumReservedBy(deviceId)
        val hostRemaining = a.config.quotaBytes - store.sumCommitted() - store.sumReserved()
        val remaining = minOf(cap - used, hostRemaining).coerceAtLeast(0)
        val acc = a.access
        return StorageInfo(cap, used, remaining, Permissions(acc.canRead, acc.canWrite, acc.canWrite && acc.canOverwrite, acc.canDelete, acc.canCreateFolders))
    }

    override suspend fun list(deviceId: String, parentId: String?): List<RemoteFile> {
        authorize(deviceId, Need.READ)
        if (parentId != null) requireOwnedFolder(deviceId, parentId)
        return store.list(deviceId, parentId).map { it.remote() }
    }

    override suspend fun stat(deviceId: String, fileId: String): RemoteFile {
        authorize(deviceId, Need.READ)
        return mine(deviceId, fileId).remote()
    }

    override suspend fun search(deviceId: String, query: String): List<RemoteFile> {
        authorize(deviceId, Need.READ)
        val q = query.trim().take(100)
        if (q.isEmpty()) return emptyList()
        return store.search(deviceId, q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")).map { it.remote() }
    }

    override suspend fun createFolder(deviceId: String, name: String, parentId: String?): RemoteFile {
        val a = authorize(deviceId, Need.FOLDERS)
        validateName(name)
        if (parentId != null) requireOwnedFolder(deviceId, parentId)
        if (store.findByName(deviceId, parentId, name) != null) throw OpException(ErrorCode.NAME_EXISTS, "Name already exists")
        if (a.config.storageMode == StorageMode.VISIBLE) {
            // make the real folder first: if the shared folder is unavailable, nothing is left half-created
            try {
                val parentDir = dirFor(a.config, deviceId, parentId, create = true)!!
                visible.childDir(parentDir, name, create = true)
            } catch (e: IOException) {
                throw OpException(ErrorCode.UNAVAILABLE, "Could not create the folder on the host")
            }
        }
        val t = now()
        val rec = FileRec(UUID.randomUUID().toString(), deviceId, parentId, true, name, null, 0, null, t, t)
        store.insertFile(rec)
        refresh()
        return rec.remote()
    }

    override suspend fun delete(deviceId: String, fileId: String) {
        val a = authorize(deviceId, Need.DELETE)
        val f = mine(deviceId, fileId)
        if (f.isFolder && store.childCount(f.fileId) > 0) throw OpException(ErrorCode.BAD_REQUEST, "Folder is not empty")
        if (f.isFolder && a.config.storageMode == StorageMode.VISIBLE) {
            // remove the real directory only when empty: anything the host put inside it is never deleted
            runCatching {
                val dir = dirFor(a.config, deviceId, f.fileId, create = false)
                if (dir != null && Files.newDirectoryStream(dir).use { !it.iterator().hasNext() }) Files.delete(dir)
            }
        }
        store.markDeleted(f.fileId, now())
        deleteStored(f.storageKey)
        audit(deviceId, "DELETE ${f.displayName}", "OK")
        refresh()
    }

    override suspend fun rename(deviceId: String, fileId: String, newName: String): RemoteFile {
        val a = authorize(deviceId, Need.WRITE)
        validateName(newName)
        val f = mine(deviceId, fileId)
        if (f.displayName == newName) return f.remote()
        if (store.findByName(deviceId, f.parentFileId, newName) != null) throw OpException(ErrorCode.NAME_EXISTS, "A file with that name already exists")
        // a real directory's path is part of every file path inside it, so renaming folders is only safe in hidden storage
        if (f.isFolder && a.config.storageMode == StorageMode.VISIBLE) {
            throw OpException(ErrorCode.BAD_REQUEST, "Folders cannot be renamed while the host stores files in a visible folder")
        }
        if (!f.isFolder && VisibleStore.isKey(f.storageKey)) {
            val old = visible.pathOf(f.storageKey!!)
            if (!Files.isRegularFile(old)) throw OpException(ErrorCode.NOT_FOUND, "File is missing on the host")
            val target = old.resolveSibling(VisibleStore.fsSafe(newName)).normalize()
            if (target.parent != old.parent) throw OpException(ErrorCode.BAD_REQUEST, "Invalid name")
            if (Files.exists(target)) throw OpException(ErrorCode.NAME_EXISTS, "A file with that name already exists in the shared folder")
            try {
                Files.move(old, target)
            } catch (e: IOException) {
                throw OpException(ErrorCode.UNAVAILABLE, "Could not rename the file on the host")
            }
            store.setStorageKey(f.fileId, VisibleStore.keyOf(target))
        }
        store.renameFile(f.fileId, newName, now())
        audit(deviceId, "RENAME ${f.displayName}", "OK")
        refresh()
        return mine(deviceId, fileId).remote()
    }

    // ---- uploads ----------------------------------------------------------------------------------------------------------------

    override suspend fun beginUpload(deviceId: String, name: String, size: Long, sha256: String, parentId: String?, peerName: String): String {
        validateName(name)
        if (size <= 0) throw OpException(ErrorCode.BAD_REQUEST, "Invalid size")
        if (!Regex("[0-9a-fA-F]{64}").matches(sha256)) throw OpException(ErrorCode.BAD_REQUEST, "Invalid hash")
        val auth = authorize(deviceId, Need.WRITE)
        if (parentId != null) requireOwnedFolder(deviceId, parentId)
        val cfg = auth.config
        val target = storageRoot(cfg) // throws UNAVAILABLE when the visible folder is gone
        // slow file-system check before the transaction; the quota maths below is re-checked inside it
        val free = Files.getFileStore(target).usableSpace
        if (free - size < cfg.safetyReserveBytes) throw OpException(ErrorCode.NO_SPACE, "The host device is low on storage")

        return reserveMutex.withLock {
            store.tx {
                val existing = store.findByName(deviceId, parentId, name)
                if (existing != null && (existing.isFolder || !auth.access.canOverwrite)) {
                    throw OpException(ErrorCode.NAME_EXISTS, "A file with that name already exists")
                }
                if (store.countActiveBy(deviceId) > 0) throw OpException(ErrorCode.BUSY, "Another upload is in progress")
                // an overwrite only needs the difference; the old object is released at commit
                val needed = (size - (existing?.sizeBytes ?: 0)).coerceAtLeast(0)
                val cap = deviceCap(auth.access, cfg)
                val deviceUsed = store.sumCommittedBy(deviceId) + store.sumReservedBy(deviceId)
                val hostUsed = store.sumCommitted() + store.sumReserved()
                if (needed > cfg.quotaBytes - hostUsed || needed > cap - deviceUsed) throw OpException(ErrorCode.QUOTA_EXCEEDED, "Shared storage quota exceeded")
                val t = now()
                val id = UUID.randomUUID().toString()
                store.saveTransfer(
                    TransferRec(id, deviceId, peerName, existing?.fileId, parentId, name, Direction.UPLOAD, TransferState.RESERVED, size, 0, needed, null, sha256.lowercase(), null, t, t),
                )
                id
            }
        }
    }

    override suspend fun receiveUpload(deviceId: String, transferId: String, input: InputStream): String? {
        val t = store.transfer(transferId)
        if (t == null || t.deviceId != deviceId || t.direction != Direction.UPLOAD || t.state != TransferState.RESERVED) {
            throw OpException(ErrorCode.BAD_REQUEST, "Unknown transfer")
        }
        store.setTransferState(transferId, TransferState.RECEIVING, 0, t.reservedBytes, null, now())
        var committedKey: String? = null
        try {
            val cfg = store.config()
            val saf = cfg.storageMode == StorageMode.VISIBLE
            var stagingFile: File? = null
            var part: Path? = null
            var dir: Path? = null
            val out = try {
                if (saf) {
                    dir = dirFor(cfg, deviceId, t.parentFileId, create = true)!!
                    part = visible.createPart(dir, t.name)
                    store.saveTransfer(t.copy(state = TransferState.RECEIVING, stagingKey = VisibleStore.keyOf(part), updatedAt = now()))
                    Files.newOutputStream(part)
                } else {
                    stagingFile = privateStore.newStaging()
                    store.saveTransfer(t.copy(state = TransferState.RECEIVING, stagingKey = stagingFile.name, updatedAt = now()))
                    stagingFile.outputStream()
                }
            } catch (e: IOException) {
                throw OpException(ErrorCode.UNAVAILABLE, "Could not write to the host's storage")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            out.use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    received += n
                    if (received > t.totalBytes) throw OpException(ErrorCode.QUOTA_EXCEEDED, "More data than declared")
                    digest.update(buf, 0, n)
                    o.write(buf, 0, n)
                }
            }
            store.setTransferState(transferId, TransferState.VERIFYING, received, t.reservedBytes, null, now())
            if (received != t.totalBytes) throw OpException(ErrorCode.INTEGRITY, "Incomplete upload")
            if (!digest.digest().toHexString().equals(t.sha256, ignoreCase = true)) throw OpException(ErrorCode.INTEGRITY, "Checksum mismatch")
            authorize(deviceId, Need.WRITE) // permissions may have been revoked while the bytes were flowing

            val old = t.fileId?.let { store.file(it) }
            val key = if (saf) {
                val replace = old?.storageKey?.takeIf { VisibleStore.isKey(it) }?.removePrefix(VisibleStore.PREFIX)
                VisibleStore.keyOf(visible.finalize(part!!, dir!!, t.name, replace))
            } else {
                privateStore.commit(stagingFile!!)
            }
            committedKey = key

            val fileId = store.tx {
                val ts = now()
                val existing = t.fileId?.let { store.file(it) }
                val id = if (existing != null) {
                    store.updateFileContent(existing.fileId, key, t.totalBytes, t.sha256, ts)
                    existing.fileId
                } else {
                    UUID.randomUUID().toString().also {
                        store.insertFile(FileRec(it, deviceId, t.parentFileId, false, t.name, key, t.totalBytes, t.sha256, ts, ts))
                    }
                }
                store.saveTransfer(t.copy(state = TransferState.COMPLETED, fileId = id, transferredBytes = received, reservedBytes = 0, stagingKey = null, updatedAt = ts))
                id
            }
            if (old?.storageKey != null && old.storageKey != key) deleteStored(old.storageKey)
            audit(deviceId, "UPLOAD ${t.name}", "OK")
            refresh()
            return fileId
        } catch (e: Throwable) {
            committedKey?.let { deleteStored(it) }
            failTransfer(transferId, if (e is kotlinx.coroutines.CancellationException) TransferState.CANCELLED else TransferState.FAILED, e.message)
            audit(deviceId, "UPLOAD ${t.name}", "FAILED")
            refresh()
            throw e
        }
    }

    // ---- downloads ----------------------------------------------------------------------------------------------------------------

    override suspend fun beginDownload(deviceId: String, fileId: String, peerName: String): DownloadSource {
        authorize(deviceId, Need.READ)
        val f = mine(deviceId, fileId)
        if (f.isFolder || f.storageKey == null) throw OpException(ErrorCode.NOT_FOUND, "Not found")
        val key = f.storageKey
        val file: File = if (VisibleStore.isKey(key)) {
            // the host can edit this folder in Explorer, so check the file is still what was stored
            visible.pathOf(key).toFile().takeIf { it.isFile && it.length() == f.sizeBytes }
                ?: throw OpException(ErrorCode.NOT_FOUND, "This file was changed or removed on the host")
        } else {
            privateStore.resolve(key).takeIf { it.isFile } ?: throw OpException(ErrorCode.NOT_FOUND, "File is missing on the host")
        }
        val t = now()
        val id = UUID.randomUUID().toString()
        store.saveTransfer(TransferRec(id, deviceId, peerName, f.fileId, f.parentFileId, f.displayName, Direction.DOWNLOAD, TransferState.SENDING, f.sizeBytes, 0, 0, null, f.sha256, null, t, t))
        audit(deviceId, "DOWNLOAD ${f.displayName}", "STARTED")
        return DownloadSource(id, f.displayName, f.sizeBytes, f.sha256 ?: "") { file.inputStream() }
    }

    override suspend fun finishTransfer(transferId: String, ok: Boolean, bytes: Long, error: String?) {
        val t = store.transfer(transferId) ?: return
        store.setTransferState(transferId, if (ok) TransferState.COMPLETED else TransferState.FAILED, if (ok) t.totalBytes else bytes, 0, error, now())
        refresh()
    }

    /** Releases the reservation and removes staging data. Safe to call more than once. */
    private fun failTransfer(transferId: String, state: TransferState, error: String?) {
        val t = store.transfer(transferId) ?: return
        if (t.state == TransferState.COMPLETED || t.state == TransferState.FAILED || t.state == TransferState.CANCELLED) return
        t.stagingKey?.let { removeStaging(it) }
        store.setTransferState(transferId, state, t.transferredBytes, 0, error?.take(200), now())
    }

    override suspend fun abortActiveTransfers(deviceId: String) {
        store.inFlight(deviceId).forEach { failTransfer(it.transferId, TransferState.FAILED, "Disconnected") }
        refresh()
    }

    override suspend fun audit(deviceId: String?, action: String, outcome: String) {
        store.addAudit(AuditRec(UUID.randomUUID().toString(), deviceId, action, outcome, now()))
    }

    override fun onProgress(transferId: String, bytes: Long) = progressListener(transferId, bytes)

    // ---- administration (used by the host's screen) -------------------------------------------------------------------------------

    fun setSharing(on: Boolean) = updateConfig { it.copy(sharingEnabled = on) }

    fun updateConfig(transform: (HostConfig) -> HostConfig) {
        store.saveConfig(transform(store.config()))
        refresh()
    }

    /** The disk that holds the shared files: total and free bytes. */
    fun diskInfo(): Pair<Long, Long> {
        val fs = runCatching { Files.getFileStore(storageRoot(store.config())) }.getOrNull() ?: return 0L to 0L
        return fs.totalSpace to fs.usableSpace
    }

    /** The most the host can offer without eating into its safety reserve (what guests already store counts as available). */
    fun maxOfferable(): Long {
        val cfg = store.config()
        val free = diskInfo().second
        return (free - cfg.safetyReserveBytes + store.sumCommitted()).coerceAtLeast(0)
    }

    /** Returns an error message, or null when the limit was applied. */
    fun setQuota(bytes: Long): String? {
        val stored = store.sumCommitted()
        val max = maxOfferable()
        val error = when {
            bytes < GIB / 4 -> "Minimum shared storage is 256 MB"
            bytes < stored -> "Guests already store ${bytes(stored)}. Delete files first or pick a larger limit."
            bytes > max -> "Only ${bytes(max)} can be offered while keeping ${bytes(store.config().safetyReserveBytes)} free for this PC."
            else -> null
        }
        if (error == null) updateConfig { it.copy(quotaBytes = bytes) }
        return error
    }

    private fun bytes(b: Long): String = when {
        b >= GIB -> "%.1f GB".format(b.toDouble() / GIB)
        b >= MIB -> "%.0f MB".format(b.toDouble() / MIB)
        else -> "$b B"
    }

    fun usePrivateStorage() = updateConfig { it.copy(storageMode = StorageMode.PRIVATE) }

    /** Returns an error message, or null when the folder is now used for new uploads. Files already saved stay where they are. */
    fun useVisibleFolder(dir: String): String? {
        val p = visible.root(dir) ?: return "That folder is not writable. Pick a different one."
        updateConfig { it.copy(storageMode = StorageMode.VISIBLE, visibleDir = p.toAbsolutePath().normalize().toString()) }
        return null
    }

    fun updateAccess(deviceId: String, transform: (Access) -> Access) {
        val a = store.access(deviceId) ?: return
        store.saveAccess(transform(a))
        refresh()
    }

    fun revoke(deviceId: String) {
        updateAccess(deviceId) { it.copy(revokedAt = now()) }
        onDeviceDropped(deviceId)
    }

    fun restore(deviceId: String) = updateAccess(deviceId) { it.copy(revokedAt = null) }

    fun block(deviceId: String) {
        store.setTrust(deviceId, TrustState.BLOCKED)
        refresh()
        onDeviceDropped(deviceId)
    }

    fun unblock(deviceId: String) {
        store.setTrust(deviceId, TrustState.APPROVED)
        refresh()
    }

    /** Forgets the device's key so it must pair again (its files and permissions are kept). */
    fun resetPairing(deviceId: String) {
        store.clearCertFp(deviceId)
        refresh()
        onDeviceDropped(deviceId)
    }

    /** null = follow the host's overall quota; never more than the host quota. */
    fun setDeviceQuota(deviceId: String, bytes: Long?) {
        val cap = store.config().quotaBytes
        updateAccess(deviceId) { it.copy(quotaOverrideBytes = bytes?.coerceAtMost(cap)) }
    }

    /** null = never expires. */
    fun setDeviceExpiry(deviceId: String, hoursFromNow: Long?) =
        updateAccess(deviceId) { it.copy(expiresAt = hoursFromNow?.let { h -> now() + h * 3_600_000L }) }


    // ---- what the host can inspect --------------------------------------------------------------------------------------------

    /** A received file as the host sees it: who sent it, where in their folders, and where it really is on this PC. */
    class Received(val file: FileRec, val deviceName: String, val folder: String, val location: File?)

    /** Every file guests have stored, newest first. [location] is null if the file is missing on disk. */
    fun receivedFiles(limit: Int = 500): List<Received> {
        val names = store.devices().associate { it.deviceId to it.displayName }
        return store.recentFiles(limit).map { f ->
            val parts = ArrayDeque<String>()
            var cur = f.parentFileId
            while (cur != null) {
                val p = store.file(cur) ?: break
                parts.addFirst(p.displayName)
                cur = p.parentFileId
            }
            Received(f, names[f.ownerDeviceId] ?: "Unknown device", parts.joinToString("/"), physicalFile(f))
        }
    }

    /** The stored file on disk, or null if it is gone. In private storage this has a random name. */
    fun physicalFile(f: FileRec): File? {
        val key = f.storageKey ?: return null
        val file = if (VisibleStore.isKey(key)) visible.pathOf(key).toFile() else runCatching { privateStore.resolve(key) }.getOrNull()
        return file?.takeIf { it.isFile }
    }

    /** A copy with the file's real name in a temporary folder, for opening files kept under random names. Replaced each time. */
    fun openableCopy(f: FileRec): File? {
        val src = physicalFile(f) ?: return null
        if (VisibleStore.isKey(f.storageKey)) return src
        val dir = File(dataRoot, "open_cache/${f.fileId}").apply { deleteRecursively(); mkdirs() }
        return File(dir, VisibleStore.fsSafe(f.displayName)).also { src.copyTo(it, overwrite = true) }
    }

    /** Removes the temporary copies made for opening. */
    fun clearOpenCache() { File(dataRoot, "open_cache").deleteRecursively() }
    fun fileCount(deviceId: String) = store.fileCount(deviceId)
    fun transfersFor(deviceId: String, limit: Int = 200) = store.transfersFor(deviceId, limit)
    fun auditFor(deviceId: String, limit: Int = 100) = store.auditFor(deviceId, limit)
    fun recentTransfers(limit: Int = 50) = store.recentTransfers(limit)
    fun clearFinishedTransfers() { store.clearFinishedTransfers(); refresh() }

    override fun close() = store.close()

    // ---- helpers ---------------------------------------------------------------------------------------------------------------------

    private fun mine(deviceId: String, fileId: String): FileRec =
        store.file(fileId)?.takeIf { it.ownerDeviceId == deviceId } ?: throw OpException(ErrorCode.NOT_FOUND, "Not found")

    private fun requireOwnedFolder(deviceId: String, folderId: String) {
        val p = store.file(folderId)
        if (p == null || !p.isFolder || p.ownerDeviceId != deviceId) throw OpException(ErrorCode.NOT_FOUND, "Folder not found")
    }

    /** The folder whose disk will hold new uploads. */
    private fun storageRoot(cfg: HostConfig): Path =
        if (cfg.storageMode == StorageMode.VISIBLE) {
            visible.root(cfg.visibleDir) ?: throw OpException(ErrorCode.UNAVAILABLE, "The host's shared folder is not available right now")
        } else {
            dataRoot.toPath()
        }

    /**
     * Real directory for a guest folder (or the guest's top folder when [folderId] is null). Rebuilt from the database each time,
     * so folders created while in hidden mode get their real directory on first use.
     */
    private fun dirFor(cfg: HostConfig, deviceId: String, folderId: String?, create: Boolean): Path? {
        val root = storageRoot(cfg)
        var dir = visible.guestDir(root, store.device(deviceId)?.displayName ?: "device", deviceId, create) ?: return null
        val chain = ArrayDeque<String>()
        var cur = folderId
        while (cur != null) {
            val f = store.file(cur) ?: break
            chain.addFirst(f.displayName)
            cur = f.parentFileId
        }
        for (n in chain) dir = visible.childDir(dir, n, create) ?: return null
        return dir
    }

    private fun deleteStored(key: String?) {
        if (key == null) return
        if (VisibleStore.isKey(key)) visible.delete(key) else privateStore.delete(key)
    }

    private fun removeStaging(key: String) {
        runCatching { if (VisibleStore.isKey(key)) visible.delete(key) else privateStore.stagingFile(key).delete() }
    }

    companion object {
        /** Guest-supplied names are untrusted: they are only ever labels in the database, never paths. */
        fun validateName(name: String) {
            val bad = name.isBlank() || name == "." || name == ".." || name.toByteArray(Charsets.UTF_8).size > 255 ||
                name.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } || name != name.trim()
            if (bad) throw OpException(ErrorCode.BAD_REQUEST, "Invalid name")
        }
    }
}
