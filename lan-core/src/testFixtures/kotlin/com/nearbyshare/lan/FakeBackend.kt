package com.nearbyshare.lan

import com.nearbyshare.core.network.protocol.ErrorCode
import com.nearbyshare.core.network.protocol.OpException
import com.nearbyshare.core.network.protocol.Permissions
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** An in-memory host so the server and client can be tested end to end without Android. */
class FakeBackend : HostBackend {
    class F(val id: String, val owner: String, val parent: String?, var name: String, val folder: Boolean, var data: ByteArray)
    private class Up(val owner: String, val name: String, val size: Long, val sha: String, val parent: String?)

    val peers = ConcurrentHashMap<String, PeerRecord>() // certificate fingerprint -> device
    val files = ConcurrentHashMap<String, F>()
    val audits = CopyOnWriteArrayList<String>()
    val progress = ConcurrentHashMap<String, Long>()
    private val uploads = ConcurrentHashMap<String, Up>()

    @Volatile var sharing = true
    @Volatile var maxGuestCount = 3
    @Volatile var approvePairing = true
    @Volatile var blockedIds = setOf<String>()
    @Volatile var quota = 50_000_000L
    @Volatile var allowDelete = true
    @Volatile var lastSas: String? = null
    @Volatile var pairingRequests = 0

    private fun used() = files.values.sumOf { it.data.size.toLong() }

    override suspend fun sharingEnabled() = sharing
    override suspend fun maxGuests() = maxGuestCount
    override suspend fun findPeer(certFingerprint: String) = peers[certFingerprint]
    override suspend fun isBlocked(deviceId: String) = deviceId in blockedIds

    override suspend fun requestPairingApproval(request: PairingRequest): Boolean {
        pairingRequests++
        lastSas = request.sas
        return approvePairing
    }

    override suspend fun registerPeer(certFingerprint: String, deviceId: String, name: String) {
        peers[certFingerprint] = PeerRecord(deviceId, name)
    }

    override suspend fun storageInfo(deviceId: String): StorageInfo {
        val mine = files.values.filter { it.owner == deviceId }.sumOf { it.data.size.toLong() }
        return StorageInfo(quota, mine, (quota - used()).coerceAtLeast(0), Permissions(true, true, false, allowDelete, true))
    }

    private fun remote(f: F) = RemoteFile(f.id, f.name, f.folder, f.data.size.toLong(), 1L, f.parent)

    override suspend fun list(deviceId: String, parentId: String?) =
        files.values.filter { it.owner == deviceId && it.parent == parentId }.sortedBy { it.name }.map(::remote)

    override suspend fun createFolder(deviceId: String, name: String, parentId: String?): RemoteFile {
        val f = F(UUID.randomUUID().toString(), deviceId, parentId, name, true, ByteArray(0))
        files[f.id] = f
        return remote(f)
    }

    private fun mine(deviceId: String, fileId: String) =
        files[fileId]?.takeIf { it.owner == deviceId } ?: throw OpException(ErrorCode.NOT_FOUND, "Not found")

    override suspend fun stat(deviceId: String, fileId: String) = remote(mine(deviceId, fileId))

    override suspend fun rename(deviceId: String, fileId: String, newName: String): RemoteFile {
        val f = mine(deviceId, fileId)
        f.name = newName
        return remote(f)
    }

    override suspend fun search(deviceId: String, query: String) =
        files.values.filter { it.owner == deviceId && it.name.contains(query, ignoreCase = true) }.map(::remote)

    override suspend fun delete(deviceId: String, fileId: String) {
        if (!allowDelete) throw OpException(ErrorCode.DENIED, "Permission denied")
        mine(deviceId, fileId)
        files.remove(fileId)
    }

    override suspend fun beginUpload(deviceId: String, name: String, size: Long, sha256: String, parentId: String?, peerName: String): String {
        if (size > quota - used()) throw OpException(ErrorCode.QUOTA_EXCEEDED, "Shared storage quota exceeded")
        val id = UUID.randomUUID().toString()
        uploads[id] = Up(deviceId, name, size, sha256, parentId)
        return id
    }

    override suspend fun receiveUpload(deviceId: String, transferId: String, input: InputStream): String? {
        val up = uploads.remove(transferId) ?: throw OpException(ErrorCode.BAD_REQUEST, "Unknown transfer")
        val out = ByteArrayOutputStream()
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > up.size) throw OpException(ErrorCode.QUOTA_EXCEEDED, "More data than declared")
            md.update(buf, 0, n)
            out.write(buf, 0, n)
        }
        if (total != up.size) throw OpException(ErrorCode.INTEGRITY, "Incomplete upload")
        if (!md.digest().toHexString().equals(up.sha, ignoreCase = true)) throw OpException(ErrorCode.INTEGRITY, "Checksum mismatch")
        val f = F(UUID.randomUUID().toString(), deviceId, up.parent, up.name, false, out.toByteArray())
        files[f.id] = f
        return f.id
    }

    override suspend fun beginDownload(deviceId: String, fileId: String, peerName: String): DownloadSource {
        val f = mine(deviceId, fileId)
        val sha = sha256Hex(f.data)
        return DownloadSource(UUID.randomUUID().toString(), f.name, f.data.size.toLong(), sha) { ByteArrayInputStream(f.data) }
    }

    override suspend fun finishTransfer(transferId: String, ok: Boolean, bytes: Long, error: String?) {
        audits += "finish $ok $bytes"
    }

    override suspend fun abortActiveTransfers(deviceId: String) {
        audits += "abort $deviceId"
    }

    override suspend fun audit(deviceId: String?, action: String, outcome: String) {
        audits += "$action $outcome"
    }

    override fun onProgress(transferId: String, bytes: Long) {
        progress[transferId] = bytes
    }
}
