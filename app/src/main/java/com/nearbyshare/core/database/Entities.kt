package com.nearbyshare.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nearbyshare.core.common.GIB

enum class AccessMode { PRIVATE, APPROVAL, OPEN }

/** PRIVATE: hidden app storage (default). VISIBLE: a folder the host picked, real file names, shown in file managers. */
enum class StorageMode { PRIVATE, VISIBLE }
enum class TrustState { APPROVED, BLOCKED }
enum class TransferRole { HOST, GUEST }
enum class TransferDirection { UPLOAD, DOWNLOAD }
enum class TransferState { RESERVED, RECEIVING, VERIFYING, SENDING, COMPLETED, FAILED, CANCELLED }

/** States in which a host-side upload holds quota. */
val ACTIVE_UPLOAD_STATES = listOf(TransferState.RESERVED, TransferState.RECEIVING, TransferState.VERIFYING)

@Entity(tableName = "host_config")
data class HostConfig(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "sharing_enabled") val sharingEnabled: Boolean = false,
    /** PRIVATE: only known devices. APPROVAL: host confirms new devices. OPEN: new devices get default permissions automatically. */
    @ColumnInfo(name = "access_mode") val accessMode: AccessMode = AccessMode.APPROVAL,
    @ColumnInfo(name = "quota_bytes") val quotaBytes: Long = 5 * GIB,
    @ColumnInfo(name = "safety_reserve_bytes") val safetyReserveBytes: Long = 2 * GIB,
    @ColumnInfo(name = "max_guests") val maxGuests: Int = 3,
    @ColumnInfo(name = "storage_mode") val storageMode: StorageMode = StorageMode.PRIVATE,
    /** Persisted SAF tree uri chosen by the host; used when [storageMode] is VISIBLE. */
    @ColumnInfo(name = "visible_tree_uri") val visibleTreeUri: String? = null,
    @ColumnInfo(name = "default_can_read") val defaultCanRead: Boolean = true,
    @ColumnInfo(name = "default_can_write") val defaultCanWrite: Boolean = true,
    @ColumnInfo(name = "default_can_overwrite") val defaultCanOverwrite: Boolean = false,
    @ColumnInfo(name = "default_can_delete") val defaultCanDelete: Boolean = false,
    @ColumnInfo(name = "default_can_create_folders") val defaultCanCreateFolders: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "devices")
data class Device(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    /** Keystore-sealed pairing key material (see KeystoreCipher). Null until pairing completes. */
    @ColumnInfo(name = "secret_sealed") val secretSealed: String?,
    @ColumnInfo(name = "trust_state") val trustState: TrustState,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,
)

@Entity(
    tableName = "device_access",
    foreignKeys = [ForeignKey(Device::class, ["device_id"], ["device_id"])],
)
data class DeviceAccess(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "can_read") val canRead: Boolean,
    @ColumnInfo(name = "can_write") val canWrite: Boolean,
    @ColumnInfo(name = "can_overwrite") val canOverwrite: Boolean,
    @ColumnInfo(name = "can_delete") val canDelete: Boolean,
    @ColumnInfo(name = "can_create_folders") val canCreateFolders: Boolean,
    @ColumnInfo(name = "quota_override_bytes") val quotaOverrideBytes: Long? = null,
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    @ColumnInfo(name = "revoked_at") val revokedAt: Long? = null,
)

@Entity(
    tableName = "shared_files",
    foreignKeys = [ForeignKey(Device::class, ["device_id"], ["owner_device_id"])],
    indices = [Index("parent_file_id"), Index("owner_device_id")],
)
data class SharedFile(
    @PrimaryKey @ColumnInfo(name = "file_id") val fileId: String,
    @ColumnInfo(name = "owner_device_id") val ownerDeviceId: String,
    @ColumnInfo(name = "parent_file_id") val parentFileId: String?,
    @ColumnInfo(name = "is_folder") val isFolder: Boolean,
    @ColumnInfo(name = "display_name") val displayName: String,
    /** Random UUID of the object on disk; null for folders. Never derived from guest input. */
    @ColumnInfo(name = "storage_key") val storageKey: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "sha256") val sha256: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(tableName = "transfers", indices = [Index("device_id", "state"), Index("created_at")])
data class Transfer(
    @PrimaryKey @ColumnInfo(name = "transfer_id") val transferId: String,
    val role: TransferRole,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "peer_name") val peerName: String,
    @ColumnInfo(name = "file_id") val fileId: String?,
    @ColumnInfo(name = "parent_file_id") val parentFileId: String?,
    val name: String,
    val direction: TransferDirection,
    val state: TransferState,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "transferred_bytes") val transferredBytes: Long = 0,
    /** Bytes of quota held while the upload is in flight (host role only). */
    @ColumnInfo(name = "reserved_bytes") val reservedBytes: Long = 0,
    @ColumnInfo(name = "staging_key") val stagingKey: String? = null,
    val sha256: String? = null,
    val error: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "audit_events", indices = [Index("created_at")])
data class AuditEvent(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "device_id") val deviceId: String?,
    val action: String,
    val outcome: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Guest side: hosts this phone has paired with. */
@Entity(tableName = "known_hosts")
data class KnownHost(
    @PrimaryKey @ColumnInfo(name = "host_id") val hostId: String,
    val name: String,
    @ColumnInfo(name = "secret_sealed") val secretSealed: String,
    @ColumnInfo(name = "last_connected_at") val lastConnectedAt: Long,
)
