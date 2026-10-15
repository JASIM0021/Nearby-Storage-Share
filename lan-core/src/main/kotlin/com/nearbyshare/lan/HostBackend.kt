package com.nearbyshare.lan

import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import java.io.InputStream

/** A device the host already approved. */
class PeerRecord(val deviceId: String, val name: String)

/** A new device asking to pair. [sas] is the code that must also be showing on the guest's screen. */
class PairingRequest(val id: String, val deviceId: String, val name: String, val sas: String)

/** What the server needs to send a file to a guest. */
class DownloadSource(val transferId: String, val name: String, val size: Long, val sha256: String, val open: () -> InputStream)

/**
 * Everything the LAN server needs from the app that owns the storage. The Android app implements it on top of its quota,
 * permission and file engine, so the rules enforced for Nearby guests apply identically to guests on the network. Every
 * operation takes the authenticated [deviceId] (from the certificate pinned at pairing, never from a claim in a message)
 * and must authorize it again, throwing OpException when something is not allowed.
 */
interface HostBackend {
    suspend fun sharingEnabled(): Boolean
    suspend fun maxGuests(): Int

    /** The approved device that pinned this certificate fingerprint, or null if unknown. */
    suspend fun findPeer(certFingerprint: String): PeerRecord?
    suspend fun isBlocked(deviceId: String): Boolean

    /** Suspends until the host's user approves or denies. Cancelled if the guest gives up; clean up any prompt then. */
    suspend fun requestPairingApproval(request: PairingRequest): Boolean
    suspend fun registerPeer(certFingerprint: String, deviceId: String, name: String)

    suspend fun storageInfo(deviceId: String): StorageInfo
    suspend fun list(deviceId: String, parentId: String?): List<RemoteFile>
    suspend fun createFolder(deviceId: String, name: String, parentId: String?): RemoteFile
    suspend fun stat(deviceId: String, fileId: String): RemoteFile
    suspend fun rename(deviceId: String, fileId: String, newName: String): RemoteFile
    suspend fun search(deviceId: String, query: String): List<RemoteFile>
    suspend fun delete(deviceId: String, fileId: String)

    /** Validates the request and reserves quota. Returns the transfer id. */
    suspend fun beginUpload(deviceId: String, name: String, size: Long, sha256: String, parentId: String?, peerName: String): String

    /** Reads exactly the declared bytes from [input], verifies size and checksum, commits. Returns the new file id. */
    suspend fun receiveUpload(deviceId: String, transferId: String, input: InputStream): String?

    suspend fun beginDownload(deviceId: String, fileId: String, peerName: String): DownloadSource
    suspend fun finishTransfer(transferId: String, ok: Boolean, bytes: Long, error: String?)
    suspend fun abortActiveTransfers(deviceId: String)
    suspend fun audit(deviceId: String?, action: String, outcome: String)

    /** Live byte counter for a transfer, for progress displays. */
    fun onProgress(transferId: String, bytes: Long) {}
}
