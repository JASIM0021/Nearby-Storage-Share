package com.nearbyshare.domain

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.nearbyshare.core.common.toHex
import com.nearbyshare.core.database.ACTIVE_UPLOAD_STATES
import com.nearbyshare.core.database.AppDatabase
import com.nearbyshare.core.database.AuditEvent
import com.nearbyshare.core.database.DeviceAccess
import com.nearbyshare.core.database.HostConfig
import com.nearbyshare.core.database.SharedFile
import com.nearbyshare.core.database.StorageMode
import com.nearbyshare.core.database.Transfer
import com.nearbyshare.core.database.TransferDirection
import com.nearbyshare.core.database.TransferRole
import com.nearbyshare.core.database.TransferState
import com.nearbyshare.core.database.TrustState
import com.nearbyshare.core.network.protocol.ErrorCode
import com.nearbyshare.core.network.protocol.OpException
import com.nearbyshare.core.network.protocol.Permissions
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import com.nearbyshare.core.storage.PrivateFileStore
import com.nearbyshare.core.storage.StorageScanner
import com.nearbyshare.core.storage.VisibleFolderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Exactly one of [file] (private storage) or [uri] (visible folder) is set. */
class DownloadTicket(val transferId: String, val file: File?, val uri: Uri?, val name: String, val sizeBytes: Long, val sha256: String)

/**
 * Host-side file system. EVERY remote operation goes through here and is authorized against the database
 * on every call, so a modified guest app (or a hand-crafted message) can never do more than the host allows.
 * Guests are isolated: each device only ever sees and touches the files it owns.
 *
 * Files live either in the app-private object store (UUID names, hidden) or, when the host chose "Visible folder",
 * in that folder under their real names. Each file row remembers where it is (storage_key), so switching modes
 * never orphans existing files.
 */
class HostFileService(
    private val db: AppDatabase,
    private val store: PrivateFileStore,
    private val visible: VisibleFolderStore,
    private val scanner: StorageScanner,
) {
    private enum class Need { ANY, READ, WRITE, DELETE, FOLDERS }

    private class Authz(val config: HostConfig, val access: DeviceAccess)

    private val reserveMutex = Mutex()

    private suspend fun authorize(deviceId: String, need: Need): Authz {
        val cfg = db.hostConfig().get()
        if (cfg == null || !cfg.sharingEnabled) throw OpException(ErrorCode.SHARING_OFF, "Sharing is turned off")
        val device = db.devices().get(deviceId)
        if (device == null || device.trustState != TrustState.APPROVED) throw OpException(ErrorCode.DENIED, "Access denied")
        val acc = db.access().get(deviceId) ?: throw OpException(ErrorCode.DENIED, "Access denied")
        if (acc.revokedAt != null) throw OpException(ErrorCode.DENIED, "Access revoked")
        if (acc.expiresAt != null && acc.expiresAt < System.currentTimeMillis()) throw OpException(ErrorCode.EXPIRED, "Access expired")
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

    suspend fun audit(deviceId: String?, action: String, outcome: String) {
        db.audit().insert(AuditEvent(UUID.randomUUID().toString(), deviceId, action, outcome, System.currentTimeMillis()))
    }

    // ---- visible-folder helpers -------------------------------------------------------------------------------

    private fun isVisible(cfg: HostConfig) = cfg.storageMode == StorageMode.VISIBLE

    private fun requireVisibleRoot(cfg: HostConfig): DocumentFile =
        visible.root(cfg.visibleTreeUri) ?: throw OpException(ErrorCode.UNAVAILABLE, "The host's shared folder is not available right now")

    /**
     * Real directory for a guest folder (or the guest's own top folder when [folderId] is null). The path is rebuilt from
     * the database every time, so folders created while in Private mode get their real directory on first use.
     */
    private suspend fun dirFor(cfg: HostConfig, deviceId: String, folderId: String?, create: Boolean): DocumentFile? {
        val root = requireVisibleRoot(cfg)
        val name = db.devices().get(deviceId)?.displayName ?: "device"
        var dir = visible.guestDir(root, name, deviceId, create) ?: return null
        val chain = ArrayDeque<String>()
        var cur = folderId
        while (cur != null) {
            val f = db.files().get(cur) ?: break
            chain.addFirst(f.displayName)
            cur = f.parentFileId
        }
        for (n in chain) dir = (if (create) visible.ensureChildDir(dir, n) else visible.findChildDir(dir, n)) ?: return null
        return dir
    }

    private fun deleteStored(key: String?, except: String? = null) {
        if (key == null || key == except) return
        if (VisibleFolderStore.isKey(key)) visible.delete(key) else store.delete(key)
    }

    // ---- queries ---------------------------------------------------------------------------------------------

    suspend fun storageInfo(deviceId: String): StorageInfo {
        val (cfg, acc) = authorize(deviceId, Need.ANY).let { it.config to it.access }
        val deviceCap = minOf(acc.quotaOverrideBytes ?: cfg.quotaBytes, cfg.quotaBytes)
        val deviceUsed = db.files().sumCommittedBy(deviceId) + db.transfers().sumReservedBy(deviceId)
        val hostRemaining = cfg.quotaBytes - db.files().sumCommitted() - db.transfers().sumReserved()
        val remaining = minOf(deviceCap - deviceUsed, hostRemaining).coerceAtLeast(0)
        return StorageInfo(
            deviceCap, deviceUsed, remaining,
            Permissions(acc.canRead, acc.canWrite, acc.canWrite && acc.canOverwrite, acc.canDelete, acc.canCreateFolders),
        )
    }

    suspend fun list(deviceId: String, parentId: String?): List<RemoteFile> {
        authorize(deviceId, Need.READ)
        if (parentId != null) requireOwnedFolder(deviceId, parentId)
        return db.files().list(deviceId, parentId).map { it.toRemote() }
    }

    /** Details of one item, including its parent folder, so a drive browser can rebuild the path from a single lookup. */
    suspend fun stat(deviceId: String, fileId: String): RemoteFile {
        authorize(deviceId, Need.READ)
        val f = db.files().get(fileId)?.takeIf { it.ownerDeviceId == deviceId } ?: throw OpException(ErrorCode.NOT_FOUND, "Not found")
        return f.toRemote()
    }

    /** Name search across everything this device owns (never other devices' files). */
    suspend fun search(deviceId: String, query: String): List<RemoteFile> {
        authorize(deviceId, Need.READ)
        val q = query.trim().take(100)
        if (q.isEmpty()) return emptyList()
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.files().search(deviceId, escaped).map { it.toRemote() }
    }

    suspend fun rename(deviceId: String, fileId: String, newName: String): RemoteFile = withContext(Dispatchers.IO) {
        val auth = authorize(deviceId, Need.WRITE)
        validateName(newName)
        val f = db.files().get(fileId)?.takeIf { it.ownerDeviceId == deviceId } ?: throw OpException(ErrorCode.NOT_FOUND, "Not found")
        if (f.displayName == newName) return@withContext f.toRemote()
        if (db.files().findByName(deviceId, f.parentFileId, newName) != null) {
            throw OpException(ErrorCode.NAME_EXISTS, "A file with that name already exists")
        }
        // A real directory's path is part of every file URI inside it, so renaming folders is only safe in private storage.
        if (f.isFolder && isVisible(auth.config)) {
            throw OpException(ErrorCode.BAD_REQUEST, "Folders cannot be renamed while the host stores files in a visible folder")
        }
        if (!f.isFolder && VisibleFolderStore.isKey(f.storageKey)) {
            val doc = visible.fromKey(f.storageKey!!)?.takeIf { it.exists() } ?: throw OpException(ErrorCode.NOT_FOUND, "File is missing on the host")
            if (!doc.renameTo(newName)) throw OpException(ErrorCode.UNAVAILABLE, "Could not rename the file on the host")
            db.files().setStorageKey(f.fileId, VisibleFolderStore.keyOf(doc))
        }
        db.files().rename(f.fileId, newName, System.currentTimeMillis())
        audit(deviceId, "RENAME ${f.displayName}", "OK")
        db.files().get(f.fileId)!!.toRemote()
    }

    // ---- mutations -------------------------------------------------------------------------------------------

    suspend fun createFolder(deviceId: String, name: String, parentId: String?): RemoteFile = withContext(Dispatchers.IO) {
        val auth = authorize(deviceId, Need.FOLDERS)
        validateName(name)
        if (parentId != null) requireOwnedFolder(deviceId, parentId)
        if (db.files().findByName(deviceId, parentId, name) != null) throw OpException(ErrorCode.NAME_EXISTS, "Name already exists")
        if (isVisible(auth.config)) {
            // Make the real directory first: if the folder is unavailable, nothing is left half-created in the database.
            try {
                val parentDir = dirFor(auth.config, deviceId, parentId, create = true)!!
                visible.ensureChildDir(parentDir, name)
            } catch (e: IOException) {
                throw OpException(ErrorCode.UNAVAILABLE, "Could not create the folder on the host")
            }
        }
        val now = System.currentTimeMillis()
        val folder = SharedFile(UUID.randomUUID().toString(), deviceId, parentId, true, name, null, 0, null, now, now)
        db.files().insert(folder)
        folder.toRemote()
    }

    suspend fun delete(deviceId: String, fileId: String): Unit = withContext(Dispatchers.IO) {
        val auth = authorize(deviceId, Need.DELETE)
        val f = db.files().get(fileId)?.takeIf { it.ownerDeviceId == deviceId } ?: throw OpException(ErrorCode.NOT_FOUND, "Not found")
        if (f.isFolder && db.files().childCount(f.fileId) > 0) throw OpException(ErrorCode.BAD_REQUEST, "Folder is not empty")
        if (f.isFolder && isVisible(auth.config)) {
            // Remove the real directory only if it is empty: anything the host put inside it themselves is never deleted.
            runCatching {
                val dir = dirFor(auth.config, deviceId, f.fileId, create = false)
                if (dir != null && dir.listFiles().isEmpty()) dir.delete()
            }
        }
        db.files().markDeleted(f.fileId, System.currentTimeMillis())
        deleteStored(f.storageKey)
    }

    /** Validates permission + quota, then reserves capacity atomically. Returns the transfer id. */
    suspend fun beginUpload(deviceId: String, name: String, size: Long, sha256: String, parentId: String?, peerName: String): String {
        validateName(name)
        if (size <= 0) throw OpException(ErrorCode.BAD_REQUEST, "Invalid size")
        if (!Regex("[0-9a-fA-F]{64}").matches(sha256)) throw OpException(ErrorCode.BAD_REQUEST, "Invalid hash")
        val auth = authorize(deviceId, Need.WRITE)
        if (parentId != null) requireOwnedFolder(deviceId, parentId)

        // Slow filesystem checks happen BEFORE the transaction; quota maths is revalidated inside it.
        val checkInternalSpace = if (isVisible(auth.config)) {
            requireVisibleRoot(auth.config)
            auth.config.visibleTreeUri?.let { visible.isPrimaryVolume(it) } ?: true
        } else true
        if (checkInternalSpace && !scanner.hasSafeFreeSpace(size, auth.config.safetyReserveBytes)) {
            throw OpException(ErrorCode.NO_SPACE, "The host device is low on storage")
        }

        return reserveMutex.withLock {
            db.withTransaction {
                val cfg = auth.config
                val existing = db.files().findByName(deviceId, parentId, name)
                if (existing != null && (existing.isFolder || !auth.access.canOverwrite)) {
                    throw OpException(ErrorCode.NAME_EXISTS, "A file with that name already exists")
                }
                if (db.transfers().countActiveBy(deviceId) > 0) throw OpException(ErrorCode.BUSY, "Another upload is in progress")

                // Overwrites only need the difference; the old object is released at commit.
                val needed = (size - (existing?.sizeBytes ?: 0)).coerceAtLeast(0)
                val deviceCap = minOf(auth.access.quotaOverrideBytes ?: cfg.quotaBytes, cfg.quotaBytes)
                val deviceUsed = db.files().sumCommittedBy(deviceId) + db.transfers().sumReservedBy(deviceId)
                val hostUsed = db.files().sumCommitted() + db.transfers().sumReserved()
                if (needed > cfg.quotaBytes - hostUsed || needed > deviceCap - deviceUsed) {
                    throw OpException(ErrorCode.QUOTA_EXCEEDED, "Shared storage quota exceeded")
                }

                val now = System.currentTimeMillis()
                val id = UUID.randomUUID().toString()
                db.transfers().upsert(
                    Transfer(
                        id, TransferRole.HOST, deviceId, peerName, existing?.fileId, parentId, name,
                        TransferDirection.UPLOAD, TransferState.RESERVED, size, 0, needed, null, sha256.lowercase(), null, now, now,
                    ),
                )
                id
            }
        }
    }

    /** Streams [input] to a staging target with a hard byte bound, verifies size + SHA-256, then commits. */
    suspend fun receiveUpload(deviceId: String, transferId: String, input: InputStream): SharedFile = withContext(Dispatchers.IO) {
        val t = db.transfers().get(transferId)
        if (t == null || t.deviceId != deviceId || t.role != TransferRole.HOST || t.direction != TransferDirection.UPLOAD ||
            t.state != TransferState.RESERVED
        ) throw OpException(ErrorCode.BAD_REQUEST, "Unknown transfer")

        db.transfers().upsert(t.copy(state = TransferState.RECEIVING, updatedAt = System.currentTimeMillis()))
        var committedKey: String? = null
        try {
            val cfg = db.hostConfig().get() ?: throw OpException(ErrorCode.SHARING_OFF, "Sharing is turned off")
            val saf = isVisible(cfg)

            // Where the bytes land while they are still unverified: a private staging file, or a hidden ".part" file in the visible folder.
            var stagingFile: File? = null
            var part: DocumentFile? = null
            var dir: DocumentFile? = null
            val out: OutputStream
            try {
                if (saf) {
                    dir = dirFor(cfg, deviceId, t.parentFileId, create = true)!!
                    part = visible.createPart(dir, t.name)
                    out = visible.openOut(part)
                    db.transfers().upsert(t.copy(state = TransferState.RECEIVING, stagingKey = VisibleFolderStore.keyOf(part), updatedAt = System.currentTimeMillis()))
                } else {
                    stagingFile = store.newStagingFile()
                    out = stagingFile.outputStream()
                    db.transfers().upsert(t.copy(state = TransferState.RECEIVING, stagingKey = stagingFile.name, updatedAt = System.currentTimeMillis()))
                }
            } catch (e: IOException) {
                throw OpException(ErrorCode.UNAVAILABLE, "Could not write to the host's storage")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            out.use {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    received += n
                    if (received > t.totalBytes) throw OpException(ErrorCode.QUOTA_EXCEEDED, "More data than declared")
                    digest.update(buf, 0, n)
                    it.write(buf, 0, n)
                }
            }
            db.transfers().setState(transferId, TransferState.VERIFYING, received, t.reservedBytes, null, System.currentTimeMillis())
            if (received != t.totalBytes) throw OpException(ErrorCode.INTEGRITY, "Incomplete upload")
            if (!digest.digest().toHex().equals(t.sha256, ignoreCase = true)) throw OpException(ErrorCode.INTEGRITY, "Checksum mismatch")
            authorize(deviceId, Need.WRITE) // permissions may have been revoked while bytes were flowing

            val oldKey = t.fileId?.let { db.files().get(it) }?.storageKey
            val key = if (saf) {
                val replaceUri = oldKey?.takeIf { VisibleFolderStore.isKey(it) }?.removePrefix(VisibleFolderStore.PREFIX)
                VisibleFolderStore.keyOf(visible.finalize(part!!, dir!!, t.name, replaceUri))
            } else {
                store.commit(stagingFile!!)
            }
            committedKey = key

            val result = db.withTransaction {
                val now = System.currentTimeMillis()
                val existing = t.fileId?.let { db.files().get(it) }
                val file = if (existing != null) {
                    existing.copy(storageKey = key, sizeBytes = t.totalBytes, sha256 = t.sha256, updatedAt = now)
                        .also { db.files().update(it) }
                } else {
                    SharedFile(UUID.randomUUID().toString(), deviceId, t.parentFileId, false, t.name, key, t.totalBytes, t.sha256, now, now)
                        .also { db.files().insert(it) }
                }
                db.transfers().upsert(
                    t.copy(
                        state = TransferState.COMPLETED, fileId = file.fileId, transferredBytes = received, reservedBytes = 0,
                        stagingKey = null, updatedAt = now,
                    ),
                )
                file
            }
            deleteStored(oldKey, except = key)
            audit(deviceId, "UPLOAD ${t.name}", "OK")
            result
        } catch (e: Throwable) {
            committedKey?.let { deleteStored(it) }
            withContext(NonCancellable) {
                failTransfer(transferId, if (e is kotlinx.coroutines.CancellationException) TransferState.CANCELLED else TransferState.FAILED, e.message)
                audit(deviceId, "UPLOAD ${t.name}", "FAILED")
            }
            throw e
        }
    }

    suspend fun beginDownload(deviceId: String, fileId: String, peerName: String): DownloadTicket {
        authorize(deviceId, Need.READ)
        val f = db.files().get(fileId)?.takeIf { it.ownerDeviceId == deviceId && !it.isFolder && it.storageKey != null }
            ?: throw OpException(ErrorCode.NOT_FOUND, "Not found")
        val key = f.storageKey!!
        var file: File? = null
        var uri: Uri? = null
        if (VisibleFolderStore.isKey(key)) {
            val doc = visible.fromKey(key)
            // The host can edit this folder with a file manager, so check the file is still what we stored.
            if (doc == null || !doc.exists() || doc.length() != f.sizeBytes) {
                throw OpException(ErrorCode.NOT_FOUND, "This file was changed or removed on the host")
            }
            uri = doc.uri
        } else {
            file = store.resolve(key)
            if (!file.isFile) throw OpException(ErrorCode.NOT_FOUND, "File is missing on the host")
        }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        db.transfers().upsert(
            Transfer(
                id, TransferRole.HOST, deviceId, peerName, f.fileId, f.parentFileId, f.displayName,
                TransferDirection.DOWNLOAD, TransferState.SENDING, f.sizeBytes, 0, 0, null, f.sha256, null, now, now,
            ),
        )
        audit(deviceId, "DOWNLOAD ${f.displayName}", "STARTED")
        return DownloadTicket(id, file, uri, f.displayName, f.sizeBytes, f.sha256 ?: "")
    }

    suspend fun finishTransfer(transferId: String, ok: Boolean, bytes: Long, error: String? = null) {
        val t = db.transfers().get(transferId) ?: return
        db.transfers().setState(
            transferId, if (ok) TransferState.COMPLETED else TransferState.FAILED,
            if (ok) t.totalBytes else bytes, 0, error, System.currentTimeMillis(),
        )
    }

    /** Releases the reservation and removes any staging data. Safe to call more than once. */
    suspend fun failTransfer(transferId: String, state: TransferState = TransferState.FAILED, error: String? = null) {
        val t = db.transfers().get(transferId) ?: return
        if (t.state in listOf(TransferState.COMPLETED, TransferState.FAILED, TransferState.CANCELLED)) return
        t.stagingKey?.let { k ->
            runCatching { if (VisibleFolderStore.isKey(k)) visible.delete(k) else store.stagingFile(k).delete() }
        }
        db.transfers().setState(transferId, state, t.transferredBytes, 0, error?.take(200), System.currentTimeMillis())
    }

    suspend fun abortActiveTransfers(deviceId: String) {
        db.transfers().activeHostUploadsBy(deviceId, ACTIVE_UPLOAD_STATES + TransferState.SENDING)
            .forEach { failTransfer(it.transferId, TransferState.FAILED, "Disconnected") }
    }

    /** Called once per process start, before any connection is accepted. */
    suspend fun recoverOnStart() {
        // Remove half-written ".part" files left in the visible folder by a crash, then release every reservation.
        db.transfers().activeHostUploads(ACTIVE_UPLOAD_STATES + TransferState.SENDING).forEach { t ->
            t.stagingKey?.takeIf { VisibleFolderStore.isKey(it) }?.let { runCatching { visible.delete(it) } }
        }
        db.transfers().failAllInFlight(System.currentTimeMillis())
        store.clearStaging()
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private suspend fun requireOwnedFolder(deviceId: String, folderId: String) {
        val p = db.files().get(folderId)
        if (p == null || !p.isFolder || p.ownerDeviceId != deviceId) throw OpException(ErrorCode.NOT_FOUND, "Folder not found")
    }

    private fun SharedFile.toRemote() = RemoteFile(fileId, displayName, isFolder, sizeBytes, updatedAt, parentFileId)

    companion object {
        /** Guest-supplied names are untrusted: they are only ever labels in the database, never paths. */
        fun validateName(name: String) {
            val bad = name.isBlank() || name == "." || name == ".." || name.toByteArray(Charsets.UTF_8).size > 255 ||
                name.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } || name != name.trim()
            if (bad) throw OpException(ErrorCode.BAD_REQUEST, "Invalid name")
        }
    }
}
