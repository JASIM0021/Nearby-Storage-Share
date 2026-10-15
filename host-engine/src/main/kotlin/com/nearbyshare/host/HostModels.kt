package com.nearbyshare.host

const val KIB = 1024L
const val MIB = 1024L * KIB
const val GIB = 1024L * MIB

/** PRIVATE: only devices already approved. APPROVAL: the host confirms each new device. OPEN: new devices get default permissions. */
enum class AccessMode { PRIVATE, APPROVAL, OPEN }

/** PRIVATE: hidden app storage (UUID names). VISIBLE: a folder the host chose, real names, one subfolder per guest. */
enum class StorageMode { PRIVATE, VISIBLE }

enum class TrustState { APPROVED, BLOCKED }
enum class TransferState { RESERVED, RECEIVING, VERIFYING, SENDING, COMPLETED, FAILED, CANCELLED }
enum class Direction { UPLOAD, DOWNLOAD }

/** States in which an upload holds quota. */
val ACTIVE_UPLOAD_STATES = listOf(TransferState.RESERVED, TransferState.RECEIVING, TransferState.VERIFYING)

data class HostConfig(
    val sharingEnabled: Boolean = false,
    val accessMode: AccessMode = AccessMode.APPROVAL,
    val quotaBytes: Long = 5 * GIB,
    val safetyReserveBytes: Long = 2 * GIB,
    val maxGuests: Int = 3,
    val storageMode: StorageMode = StorageMode.PRIVATE,
    /** Folder chosen for VISIBLE storage. */
    val visibleDir: String? = null,
    val defaultCanRead: Boolean = true,
    val defaultCanWrite: Boolean = true,
    val defaultCanOverwrite: Boolean = false,
    val defaultCanDelete: Boolean = false,
    val defaultCanCreateFolders: Boolean = true,
)

data class Device(
    val deviceId: String,
    val displayName: String,
    val trustState: TrustState,
    val firstSeenAt: Long,
    val lastSeenAt: Long?,
    /** SHA-256 of the TLS certificate pinned when the device paired. */
    val certFp: String?,
)

data class Access(
    val deviceId: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canOverwrite: Boolean,
    val canDelete: Boolean,
    val canCreateFolders: Boolean,
    val quotaOverrideBytes: Long? = null,
    val expiresAt: Long? = null,
    val revokedAt: Long? = null,
)

data class DeviceRow(val device: Device, val access: Access?)

data class FileRec(
    val fileId: String,
    val ownerDeviceId: String,
    val parentFileId: String?,
    val isFolder: Boolean,
    val displayName: String,
    /** UUID of the hidden object, or "file:<absolute path>" in visible storage. Null for folders. */
    val storageKey: String?,
    val sizeBytes: Long,
    val sha256: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

data class TransferRec(
    val transferId: String,
    val deviceId: String,
    val peerName: String,
    val fileId: String?,
    val parentFileId: String?,
    val name: String,
    val direction: Direction,
    val state: TransferState,
    val totalBytes: Long,
    val transferredBytes: Long = 0,
    val reservedBytes: Long = 0,
    val stagingKey: String? = null,
    val sha256: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AuditRec(val eventId: String, val deviceId: String?, val action: String, val outcome: String, val createdAt: Long)

/** A pairing waiting for the host's decision. [rePair] = an already-known device pairing again. */
data class PendingPairing(val key: String, val deviceId: String, val name: String, val code: String, val rePair: Boolean)
