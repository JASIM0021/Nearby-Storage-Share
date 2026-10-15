package com.nearbyshare.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Per-device storage total, used by the host's device list. */
data class DeviceUsage(val id: String, val bytes: Long)

@Dao
interface HostConfigDao {
    @Query("SELECT * FROM host_config WHERE id = 1")
    suspend fun get(): HostConfig?

    @Query("SELECT * FROM host_config WHERE id = 1")
    fun observe(): Flow<HostConfig?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: HostConfig)
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices WHERE device_id = :id")
    suspend fun get(id: String): Device?

    @Query("SELECT * FROM devices ORDER BY first_seen_at")
    fun observeAll(): Flow<List<Device>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: Device)

    @Query("UPDATE devices SET last_seen_at = :now WHERE device_id = :id")
    suspend fun touch(id: String, now: Long)

    /** Re-pairs an existing device in place. Never REPLACE a devices row: files and access rows reference it. */
    @Query(
        """UPDATE devices SET display_name = :name, secret_sealed = :sealed, trust_state = 'APPROVED', last_seen_at = :now
           WHERE device_id = :id""",
    )
    suspend fun updatePairing(id: String, name: String, sealed: String?, now: Long)

    @Query("UPDATE devices SET secret_sealed = NULL WHERE device_id = :id")
    suspend fun clearSecret(id: String)

    @Query("SELECT * FROM devices WHERE cert_fp = :fp LIMIT 1")
    suspend fun findByCertFp(fp: String): Device?

    /** Re-pairs an existing device over the network, keeping its files and permissions. */
    @Query("UPDATE devices SET display_name = :name, cert_fp = :fp, trust_state = 'APPROVED', last_seen_at = :now WHERE device_id = :id")
    suspend fun updateCertFp(id: String, name: String, fp: String, now: Long)

    @Query("UPDATE devices SET trust_state = :state WHERE device_id = :id")
    suspend fun setTrust(id: String, state: TrustState)

    @Query("DELETE FROM devices WHERE device_id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AccessDao {
    @Query("SELECT * FROM device_access WHERE device_id = :id")
    suspend fun get(id: String): DeviceAccess?

    @Query("SELECT * FROM device_access")
    fun observeAll(): Flow<List<DeviceAccess>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(access: DeviceAccess)

    @Query("DELETE FROM device_access WHERE device_id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FileDao {
    @Query("SELECT * FROM shared_files WHERE file_id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): SharedFile?

    @Query(
        """SELECT * FROM shared_files
           WHERE owner_device_id = :owner AND deleted_at IS NULL
             AND ((:parent IS NULL AND parent_file_id IS NULL) OR parent_file_id = :parent)
           ORDER BY is_folder DESC, display_name COLLATE NOCASE""",
    )
    suspend fun list(owner: String, parent: String?): List<SharedFile>

    @Query(
        """SELECT * FROM shared_files
           WHERE owner_device_id = :owner AND deleted_at IS NULL AND display_name = :name
             AND ((:parent IS NULL AND parent_file_id IS NULL) OR parent_file_id = :parent)
           LIMIT 1""",
    )
    suspend fun findByName(owner: String, parent: String?, name: String): SharedFile?

    @Query("SELECT COUNT(*) FROM shared_files WHERE parent_file_id = :id AND deleted_at IS NULL")
    suspend fun childCount(id: String): Int

    @Query(
        """SELECT * FROM shared_files WHERE owner_device_id = :owner AND deleted_at IS NULL
           AND display_name LIKE '%' || :query || '%' ESCAPE '\' ORDER BY is_folder DESC, display_name COLLATE NOCASE LIMIT 50""",
    )
    suspend fun search(owner: String, query: String): List<SharedFile>

    @Query("UPDATE shared_files SET display_name = :name, updated_at = :now WHERE file_id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE shared_files SET storage_key = :key WHERE file_id = :id")
    suspend fun setStorageKey(id: String, key: String)

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM shared_files WHERE is_folder = 0 AND deleted_at IS NULL")
    suspend fun sumCommitted(): Long

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM shared_files WHERE is_folder = 0 AND deleted_at IS NULL")
    fun observeCommitted(): Flow<Long>

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM shared_files WHERE is_folder = 0 AND deleted_at IS NULL AND owner_device_id = :owner")
    suspend fun sumCommittedBy(owner: String): Long

    @Query("SELECT COUNT(*) FROM shared_files WHERE is_folder = 0 AND deleted_at IS NULL AND owner_device_id = :owner")
    fun observeFileCount(owner: String): Flow<Int>

    @Query("SELECT owner_device_id AS id, COALESCE(SUM(size_bytes), 0) AS bytes FROM shared_files WHERE is_folder = 0 AND deleted_at IS NULL GROUP BY owner_device_id")
    fun observeUsageByDevice(): Flow<List<DeviceUsage>>

    @Insert
    suspend fun insert(file: SharedFile)

    @Update
    suspend fun update(file: SharedFile)

    @Query("UPDATE shared_files SET deleted_at = :now WHERE file_id = :id")
    suspend fun markDeleted(id: String, now: Long)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers WHERE transfer_id = :id")
    suspend fun get(id: String): Transfer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transfer: Transfer)

    @Query(
        """UPDATE transfers SET state = :state, transferred_bytes = :transferred, reserved_bytes = :reserved,
           error = :error, updated_at = :now WHERE transfer_id = :id""",
    )
    suspend fun setState(id: String, state: TransferState, transferred: Long, reserved: Long, error: String?, now: Long)

    @Query("SELECT COALESCE(SUM(reserved_bytes), 0) FROM transfers WHERE role = 'HOST' AND state IN (:states)")
    suspend fun sumReserved(states: List<TransferState> = ACTIVE_UPLOAD_STATES): Long

    @Query("SELECT COALESCE(SUM(reserved_bytes), 0) FROM transfers WHERE role = 'HOST' AND device_id = :device AND state IN (:states)")
    suspend fun sumReservedBy(device: String, states: List<TransferState> = ACTIVE_UPLOAD_STATES): Long

    @Query("SELECT COUNT(*) FROM transfers WHERE role = 'HOST' AND device_id = :device AND state IN (:states)")
    suspend fun countActiveBy(device: String, states: List<TransferState> = ACTIVE_UPLOAD_STATES): Int

    @Query("SELECT * FROM transfers WHERE role = 'HOST' AND state IN (:states)")
    suspend fun activeHostUploads(states: List<TransferState> = ACTIVE_UPLOAD_STATES): List<Transfer>

    @Query("SELECT * FROM transfers WHERE role = 'HOST' AND device_id = :device AND state IN (:states)")
    suspend fun activeHostUploadsBy(device: String, states: List<TransferState> = ACTIVE_UPLOAD_STATES): List<Transfer>

    @Query("SELECT * FROM transfers ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Transfer>>

    @Query("SELECT * FROM transfers WHERE role = 'HOST' AND device_id = :device ORDER BY created_at DESC LIMIT :limit")
    fun observeByDevice(device: String, limit: Int): Flow<List<Transfer>>

    /** After a process restart nothing can still be in flight: release every reservation and mark the rows failed. */
    @Query(
        """UPDATE transfers SET state = 'FAILED', reserved_bytes = 0, error = 'Interrupted', updated_at = :now
           WHERE state IN ('RESERVED','RECEIVING','VERIFYING','SENDING')""",
    )
    suspend fun failAllInFlight(now: Long)

    @Query("DELETE FROM transfers WHERE state IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun clearFinished()
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(event: AuditEvent)

    @Query("SELECT * FROM audit_events ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEvent>>

    @Query("SELECT * FROM audit_events WHERE device_id = :device ORDER BY created_at DESC LIMIT :limit")
    fun observeByDevice(device: String, limit: Int): Flow<List<AuditEvent>>
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts WHERE host_id = :id")
    suspend fun get(id: String): KnownHost?

    @Query("SELECT * FROM known_hosts ORDER BY last_connected_at DESC")
    suspend fun getAll(): List<KnownHost>

    @Query("SELECT * FROM known_hosts WHERE cert_fp = :fp LIMIT 1")
    suspend fun findByCertFp(fp: String): KnownHost?

    /** Forget only the network pin; a Nearby pairing key for the same host stays. */
    @Query("UPDATE known_hosts SET cert_fp = NULL WHERE cert_fp = :fp")
    suspend fun clearCertFp(fp: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(host: KnownHost)

    @Query("DELETE FROM known_hosts WHERE host_id = :id")
    suspend fun delete(id: String)
}
