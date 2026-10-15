package com.nearbyshare.core.network.lan

import android.content.Context
import com.nearbyshare.core.common.TransferProgress
import com.nearbyshare.core.database.AccessMode
import com.nearbyshare.core.database.AppDatabase
import com.nearbyshare.core.database.Device
import com.nearbyshare.core.database.DeviceAccess
import com.nearbyshare.core.database.TrustState
import com.nearbyshare.core.network.PendingApproval
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import com.nearbyshare.domain.HostFileService
import com.nearbyshare.lan.DownloadSource
import com.nearbyshare.lan.HostBackend
import com.nearbyshare.lan.PairingRequest
import com.nearbyshare.lan.PeerRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Pairing prompts for guests connecting over the network; shown by the same dialog as Nearby pairing requests. */
class LanApprovals {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val _pending = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pending: StateFlow<List<PendingApproval>> = _pending.asStateFlow()

    suspend fun ask(request: PairingRequest, rePair: Boolean): Boolean {
        val key = KEY_PREFIX + request.id
        val answer = CompletableDeferred<Boolean>()
        waiting[key] = answer
        _pending.update { it + PendingApproval(key, request.deviceId, request.name, request.sas, rePair) }
        try {
            return answer.await()
        } finally { // also runs when the guest gives up and the request is cancelled: the prompt disappears
            waiting.remove(key)
            _pending.update { list -> list.filterNot { it.endpointId == key } }
        }
    }

    fun resolve(key: String, approve: Boolean) {
        waiting[key]?.complete(approve)
    }

    companion object {
        const val KEY_PREFIX = "lan:"
    }
}

/**
 * Lets network guests use exactly the same quota, permission and storage engine as Nearby guests: every call goes through
 * [HostFileService], which re-authorizes it against the database each time.
 */
class AndroidHostBackend(
    private val context: Context,
    private val db: AppDatabase,
    private val files: HostFileService,
    private val progress: TransferProgress,
    private val approvals: LanApprovals,
) : HostBackend {

    override suspend fun sharingEnabled() = db.hostConfig().get()?.sharingEnabled == true

    override suspend fun maxGuests() = db.hostConfig().get()?.maxGuests ?: 3

    override suspend fun findPeer(certFingerprint: String): PeerRecord? {
        val d = db.devices().findByCertFp(certFingerprint) ?: return null
        return if (d.trustState == TrustState.APPROVED) PeerRecord(d.deviceId, d.displayName) else null
    }

    override suspend fun isBlocked(deviceId: String) = db.devices().get(deviceId)?.trustState == TrustState.BLOCKED

    override suspend fun requestPairingApproval(request: PairingRequest): Boolean {
        val cfg = db.hostConfig().get() ?: return false
        val known = db.devices().get(request.deviceId)
        return when {
            known?.trustState == TrustState.BLOCKED -> false
            known == null && cfg.accessMode == AccessMode.PRIVATE -> false.also { files.audit(request.deviceId, "LAN PAIR", "REJECTED: host is Private") }
            known == null && cfg.accessMode == AccessMode.OPEN -> true
            // New device, or a known device pairing again over the network: the host confirms the code, as with Nearby
            else -> approvals.ask(request, rePair = known != null)
        }
    }

    override suspend fun registerPeer(certFingerprint: String, deviceId: String, name: String) {
        val now = System.currentTimeMillis()
        if (db.devices().get(deviceId) != null) {
            // Already known (e.g. paired earlier over Nearby): keep its files and permissions, add the network key.
            db.devices().updateCertFp(deviceId, name, certFingerprint, now)
            return
        }
        val c = db.hostConfig().get() ?: return
        db.devices().upsert(Device(deviceId, name, null, TrustState.APPROVED, now, now, certFingerprint))
        db.access().upsert(
            DeviceAccess(deviceId, c.defaultCanRead, c.defaultCanWrite, c.defaultCanOverwrite, c.defaultCanDelete, c.defaultCanCreateFolders),
        )
    }

    override suspend fun storageInfo(deviceId: String): StorageInfo = files.storageInfo(deviceId)
    override suspend fun list(deviceId: String, parentId: String?): List<RemoteFile> = files.list(deviceId, parentId)
    override suspend fun createFolder(deviceId: String, name: String, parentId: String?) = files.createFolder(deviceId, name, parentId)
    override suspend fun stat(deviceId: String, fileId: String) = files.stat(deviceId, fileId)
    override suspend fun rename(deviceId: String, fileId: String, newName: String) = files.rename(deviceId, fileId, newName)
    override suspend fun search(deviceId: String, query: String) = files.search(deviceId, query)
    override suspend fun delete(deviceId: String, fileId: String) = files.delete(deviceId, fileId)

    override suspend fun beginUpload(deviceId: String, name: String, size: Long, sha256: String, parentId: String?, peerName: String) =
        files.beginUpload(deviceId, name, size, sha256, parentId, peerName)

    override suspend fun receiveUpload(deviceId: String, transferId: String, input: InputStream): String? =
        files.receiveUpload(deviceId, transferId, input).fileId

    override suspend fun beginDownload(deviceId: String, fileId: String, peerName: String): DownloadSource {
        val t = files.beginDownload(deviceId, fileId, peerName)
        return DownloadSource(t.transferId, t.name, t.sizeBytes, t.sha256) {
            if (t.file != null) FileInputStream(t.file) else context.contentResolver.openInputStream(t.uri!!) ?: throw IOException("Cannot read file")
        }
    }

    override suspend fun finishTransfer(transferId: String, ok: Boolean, bytes: Long, error: String?) {
        progress.clear(transferId)
        files.finishTransfer(transferId, ok, bytes, error)
    }

    override suspend fun abortActiveTransfers(deviceId: String) = files.abortActiveTransfers(deviceId)

    override suspend fun audit(deviceId: String?, action: String, outcome: String) = files.audit(deviceId, action, outcome)

    override fun onProgress(transferId: String, bytes: Long) = progress.set(transferId, bytes)
}
