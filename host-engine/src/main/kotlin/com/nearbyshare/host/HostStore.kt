package com.nearbyshare.host

import java.io.File
import java.sql.ResultSet

/** Everything the host remembers, in one SQLite file. Mirrors the tables of the Android app. */
class HostStore(file: File) : AutoCloseable {
    private val db = Sql(file)

    init {
        db.execute(
            """CREATE TABLE IF NOT EXISTS host_config(
                id INTEGER PRIMARY KEY CHECK (id = 1),
                sharing_enabled INTEGER NOT NULL, access_mode TEXT NOT NULL, quota_bytes INTEGER NOT NULL,
                safety_reserve_bytes INTEGER NOT NULL, max_guests INTEGER NOT NULL, storage_mode TEXT NOT NULL, visible_dir TEXT,
                default_can_read INTEGER NOT NULL, default_can_write INTEGER NOT NULL, default_can_overwrite INTEGER NOT NULL,
                default_can_delete INTEGER NOT NULL, default_can_create_folders INTEGER NOT NULL)""",
        )
        db.execute(
            """CREATE TABLE IF NOT EXISTS devices(
                device_id TEXT PRIMARY KEY, display_name TEXT NOT NULL, trust_state TEXT NOT NULL,
                first_seen_at INTEGER NOT NULL, last_seen_at INTEGER, cert_fp TEXT)""",
        )
        db.execute("CREATE INDEX IF NOT EXISTS idx_devices_fp ON devices(cert_fp)")
        db.execute(
            """CREATE TABLE IF NOT EXISTS device_access(
                device_id TEXT PRIMARY KEY, can_read INTEGER NOT NULL, can_write INTEGER NOT NULL, can_overwrite INTEGER NOT NULL,
                can_delete INTEGER NOT NULL, can_create_folders INTEGER NOT NULL, quota_override_bytes INTEGER,
                expires_at INTEGER, revoked_at INTEGER)""",
        )
        db.execute(
            """CREATE TABLE IF NOT EXISTS shared_files(
                file_id TEXT PRIMARY KEY, owner_device_id TEXT NOT NULL, parent_file_id TEXT, is_folder INTEGER NOT NULL,
                display_name TEXT NOT NULL, storage_key TEXT, size_bytes INTEGER NOT NULL, sha256 TEXT,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)""",
        )
        db.execute("CREATE INDEX IF NOT EXISTS idx_files_parent ON shared_files(parent_file_id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_files_owner ON shared_files(owner_device_id)")
        db.execute(
            """CREATE TABLE IF NOT EXISTS transfers(
                transfer_id TEXT PRIMARY KEY, device_id TEXT NOT NULL, peer_name TEXT NOT NULL, file_id TEXT, parent_file_id TEXT,
                name TEXT NOT NULL, direction TEXT NOT NULL, state TEXT NOT NULL, total_bytes INTEGER NOT NULL,
                transferred_bytes INTEGER NOT NULL, reserved_bytes INTEGER NOT NULL, staging_key TEXT, sha256 TEXT, error TEXT,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
        )
        db.execute("CREATE INDEX IF NOT EXISTS idx_transfers_device ON transfers(device_id, state)")
        db.execute(
            """CREATE TABLE IF NOT EXISTS audit_events(
                event_id TEXT PRIMARY KEY, device_id TEXT, action TEXT NOT NULL, outcome TEXT NOT NULL, created_at INTEGER NOT NULL)""",
        )
        db.execute("CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_events(created_at)")
        if (db.query("SELECT COUNT(*) AS n FROM host_config") { it.getInt("n") }.first() == 0) saveConfig(HostConfig())
    }

    // ---- config ---------------------------------------------------------------------------------------------------

    private fun ResultSet.config() = HostConfig(
        bool("sharing_enabled"), AccessMode.valueOf(getString("access_mode")), getLong("quota_bytes"), getLong("safety_reserve_bytes"),
        getInt("max_guests"), StorageMode.valueOf(getString("storage_mode")), stringOrNull("visible_dir"),
        bool("default_can_read"), bool("default_can_write"), bool("default_can_overwrite"), bool("default_can_delete"), bool("default_can_create_folders"),
    )

    fun config(): HostConfig = db.query("SELECT * FROM host_config WHERE id = 1") { it.config() }.first()

    fun saveConfig(c: HostConfig) {
        db.update(
            """INSERT OR REPLACE INTO host_config(id, sharing_enabled, access_mode, quota_bytes, safety_reserve_bytes, max_guests,
                storage_mode, visible_dir, default_can_read, default_can_write, default_can_overwrite, default_can_delete, default_can_create_folders)
                VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?)""",
            c.sharingEnabled, c.accessMode, c.quotaBytes, c.safetyReserveBytes, c.maxGuests, c.storageMode, c.visibleDir,
            c.defaultCanRead, c.defaultCanWrite, c.defaultCanOverwrite, c.defaultCanDelete, c.defaultCanCreateFolders,
        )
    }

    // ---- devices & access ---------------------------------------------------------------------------------------------

    private fun ResultSet.device() = Device(
        getString("device_id"), getString("display_name"), TrustState.valueOf(getString("trust_state")),
        getLong("first_seen_at"), longOrNull("last_seen_at"), stringOrNull("cert_fp"),
    )

    fun device(id: String): Device? = db.query("SELECT * FROM devices WHERE device_id = ?", id) { it.device() }.firstOrNull()
    fun deviceByFp(fp: String): Device? = db.query("SELECT * FROM devices WHERE cert_fp = ? LIMIT 1", fp) { it.device() }.firstOrNull()
    fun devices(): List<Device> = db.query("SELECT * FROM devices ORDER BY first_seen_at") { it.device() }

    fun insertDevice(d: Device) {
        db.update(
            "INSERT OR REPLACE INTO devices(device_id, display_name, trust_state, first_seen_at, last_seen_at, cert_fp) VALUES (?,?,?,?,?,?)",
            d.deviceId, d.displayName, d.trustState, d.firstSeenAt, d.lastSeenAt, d.certFp,
        )
    }

    /** Re-pair an existing device in place, keeping its files and permissions. */
    fun updatePairing(id: String, name: String, fp: String, now: Long) {
        db.update("UPDATE devices SET display_name = ?, cert_fp = ?, trust_state = 'APPROVED', last_seen_at = ? WHERE device_id = ?", name, fp, now, id)
    }

    fun clearCertFp(id: String) { db.update("UPDATE devices SET cert_fp = NULL WHERE device_id = ?", id) }
    fun touchDevice(id: String, now: Long) { db.update("UPDATE devices SET last_seen_at = ? WHERE device_id = ?", now, id) }
    fun setTrust(id: String, state: TrustState) { db.update("UPDATE devices SET trust_state = ? WHERE device_id = ?", state, id) }

    private fun ResultSet.access() = Access(
        getString("device_id"), bool("can_read"), bool("can_write"), bool("can_overwrite"), bool("can_delete"), bool("can_create_folders"),
        longOrNull("quota_override_bytes"), longOrNull("expires_at"), longOrNull("revoked_at"),
    )

    fun access(id: String): Access? = db.query("SELECT * FROM device_access WHERE device_id = ?", id) { it.access() }.firstOrNull()
    fun accesses(): List<Access> = db.query("SELECT * FROM device_access") { it.access() }

    fun saveAccess(a: Access) {
        db.update(
            "INSERT OR REPLACE INTO device_access(device_id, can_read, can_write, can_overwrite, can_delete, can_create_folders, quota_override_bytes, expires_at, revoked_at) VALUES (?,?,?,?,?,?,?,?,?)",
            a.deviceId, a.canRead, a.canWrite, a.canOverwrite, a.canDelete, a.canCreateFolders, a.quotaOverrideBytes, a.expiresAt, a.revokedAt,
        )
    }

    // ---- files ---------------------------------------------------------------------------------------------------------

    private fun ResultSet.file() = FileRec(
        getString("file_id"), getString("owner_device_id"), stringOrNull("parent_file_id"), bool("is_folder"), getString("display_name"),
        stringOrNull("storage_key"), getLong("size_bytes"), stringOrNull("sha256"), getLong("created_at"), getLong("updated_at"), longOrNull("deleted_at"),
    )

    fun file(id: String): FileRec? = db.query("SELECT * FROM shared_files WHERE file_id = ? AND deleted_at IS NULL", id) { it.file() }.firstOrNull()

    fun recentFiles(limit: Int): List<FileRec> = db.query(
        "SELECT * FROM shared_files WHERE is_folder = 0 AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT ?", limit,
    ) { it.file() }

    fun list(owner: String, parent: String?): List<FileRec> = db.query(
        """SELECT * FROM shared_files WHERE owner_device_id = ? AND deleted_at IS NULL
           AND ((? IS NULL AND parent_file_id IS NULL) OR parent_file_id = ?)
           ORDER BY is_folder DESC, display_name COLLATE NOCASE""",
        owner, parent, parent,
    ) { it.file() }

    fun findByName(owner: String, parent: String?, name: String): FileRec? = db.query(
        """SELECT * FROM shared_files WHERE owner_device_id = ? AND deleted_at IS NULL AND display_name = ?
           AND ((? IS NULL AND parent_file_id IS NULL) OR parent_file_id = ?) LIMIT 1""",
        owner, name, parent, parent,
    ) { it.file() }.firstOrNull()

    fun childCount(id: String): Int = db.query("SELECT COUNT(*) AS n FROM shared_files WHERE parent_file_id = ? AND deleted_at IS NULL", id) { it.getInt("n") }.first()

    fun sumCommitted(): Long = db.query("SELECT COALESCE(SUM(size_bytes),0) AS s FROM shared_files WHERE is_folder = 0 AND deleted_at IS NULL") { it.getLong("s") }.first()

    fun sumCommittedBy(owner: String): Long = db.query(
        "SELECT COALESCE(SUM(size_bytes),0) AS s FROM shared_files WHERE is_folder = 0 AND deleted_at IS NULL AND owner_device_id = ?", owner,
    ) { it.getLong("s") }.first()

    fun fileCount(owner: String): Int = db.query(
        "SELECT COUNT(*) AS n FROM shared_files WHERE is_folder = 0 AND deleted_at IS NULL AND owner_device_id = ?", owner,
    ) { it.getInt("n") }.first()

    fun usageByDevice(): Map<String, Long> = db.query(
        "SELECT owner_device_id AS id, COALESCE(SUM(size_bytes),0) AS s FROM shared_files WHERE is_folder = 0 AND deleted_at IS NULL GROUP BY owner_device_id",
    ) { it.getString("id") to it.getLong("s") }.toMap()

    fun search(owner: String, escapedQuery: String): List<FileRec> = db.query(
        """SELECT * FROM shared_files WHERE owner_device_id = ? AND deleted_at IS NULL AND display_name LIKE '%' || ? || '%' ESCAPE '\'
           ORDER BY is_folder DESC, display_name COLLATE NOCASE LIMIT 50""",
        owner, escapedQuery,
    ) { it.file() }

    fun insertFile(f: FileRec) {
        db.update(
            "INSERT INTO shared_files(file_id, owner_device_id, parent_file_id, is_folder, display_name, storage_key, size_bytes, sha256, created_at, updated_at, deleted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            f.fileId, f.ownerDeviceId, f.parentFileId, f.isFolder, f.displayName, f.storageKey, f.sizeBytes, f.sha256, f.createdAt, f.updatedAt, f.deletedAt,
        )
    }

    fun updateFileContent(id: String, key: String, size: Long, sha: String?, now: Long) {
        db.update("UPDATE shared_files SET storage_key = ?, size_bytes = ?, sha256 = ?, updated_at = ? WHERE file_id = ?", key, size, sha, now, id)
    }

    fun renameFile(id: String, name: String, now: Long) { db.update("UPDATE shared_files SET display_name = ?, updated_at = ? WHERE file_id = ?", name, now, id) }
    fun setStorageKey(id: String, key: String) { db.update("UPDATE shared_files SET storage_key = ? WHERE file_id = ?", key, id) }
    fun markDeleted(id: String, now: Long) { db.update("UPDATE shared_files SET deleted_at = ? WHERE file_id = ?", now, id) }

    // ---- transfers --------------------------------------------------------------------------------------------------------

    private fun ResultSet.transfer() = TransferRec(
        getString("transfer_id"), getString("device_id"), getString("peer_name"), stringOrNull("file_id"), stringOrNull("parent_file_id"),
        getString("name"), Direction.valueOf(getString("direction")), TransferState.valueOf(getString("state")), getLong("total_bytes"),
        getLong("transferred_bytes"), getLong("reserved_bytes"), stringOrNull("staging_key"), stringOrNull("sha256"), stringOrNull("error"),
        getLong("created_at"), getLong("updated_at"),
    )

    fun transfer(id: String): TransferRec? = db.query("SELECT * FROM transfers WHERE transfer_id = ?", id) { it.transfer() }.firstOrNull()

    fun saveTransfer(t: TransferRec) {
        db.update(
            """INSERT OR REPLACE INTO transfers(transfer_id, device_id, peer_name, file_id, parent_file_id, name, direction, state, total_bytes,
                transferred_bytes, reserved_bytes, staging_key, sha256, error, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            t.transferId, t.deviceId, t.peerName, t.fileId, t.parentFileId, t.name, t.direction, t.state, t.totalBytes,
            t.transferredBytes, t.reservedBytes, t.stagingKey, t.sha256, t.error, t.createdAt, t.updatedAt,
        )
    }

    fun setTransferState(id: String, state: TransferState, transferred: Long, reserved: Long, error: String?, now: Long) {
        db.update("UPDATE transfers SET state = ?, transferred_bytes = ?, reserved_bytes = ?, error = ?, updated_at = ? WHERE transfer_id = ?", state, transferred, reserved, error, now, id)
    }

    private val activePlaceholders = ACTIVE_UPLOAD_STATES.joinToString(",") { "'${it.name}'" }

    fun sumReserved(): Long = db.query("SELECT COALESCE(SUM(reserved_bytes),0) AS s FROM transfers WHERE state IN ($activePlaceholders)") { it.getLong("s") }.first()

    fun sumReservedBy(device: String): Long = db.query(
        "SELECT COALESCE(SUM(reserved_bytes),0) AS s FROM transfers WHERE device_id = ? AND state IN ($activePlaceholders)", device,
    ) { it.getLong("s") }.first()

    fun countActiveBy(device: String): Int = db.query(
        "SELECT COUNT(*) AS n FROM transfers WHERE device_id = ? AND state IN ($activePlaceholders)", device,
    ) { it.getInt("n") }.first()

    /** Uploads and downloads that are in flight (used for clean-up). */
    fun inFlight(deviceId: String? = null): List<TransferRec> {
        val states = (ACTIVE_UPLOAD_STATES + TransferState.SENDING).joinToString(",") { "'${it.name}'" }
        return if (deviceId == null) db.query("SELECT * FROM transfers WHERE state IN ($states)") { it.transfer() }
        else db.query("SELECT * FROM transfers WHERE device_id = ? AND state IN ($states)", deviceId) { it.transfer() }
    }

    fun failAllInFlight(now: Long) {
        db.update(
            "UPDATE transfers SET state = 'FAILED', reserved_bytes = 0, error = 'Interrupted', updated_at = ? WHERE state IN ('RESERVED','RECEIVING','VERIFYING','SENDING')", now,
        )
    }

    fun transfersFor(device: String, limit: Int): List<TransferRec> =
        db.query("SELECT * FROM transfers WHERE device_id = ? ORDER BY created_at DESC LIMIT ?", device, limit) { it.transfer() }

    fun recentTransfers(limit: Int): List<TransferRec> = db.query("SELECT * FROM transfers ORDER BY created_at DESC LIMIT ?", limit) { it.transfer() }

    fun clearFinishedTransfers() { db.update("DELETE FROM transfers WHERE state IN ('COMPLETED','FAILED','CANCELLED')") }

    // ---- audit -----------------------------------------------------------------------------------------------------------------

    private fun ResultSet.audit() = AuditRec(getString("event_id"), stringOrNull("device_id"), getString("action"), getString("outcome"), getLong("created_at"))

    fun addAudit(e: AuditRec) {
        db.update("INSERT INTO audit_events(event_id, device_id, action, outcome, created_at) VALUES (?,?,?,?,?)", e.eventId, e.deviceId, e.action, e.outcome, e.createdAt)
    }

    fun recentAudit(limit: Int): List<AuditRec> = db.query("SELECT * FROM audit_events ORDER BY created_at DESC LIMIT ?", limit) { it.audit() }
    fun auditFor(device: String, limit: Int): List<AuditRec> =
        db.query("SELECT * FROM audit_events WHERE device_id = ? ORDER BY created_at DESC LIMIT ?", device, limit) { it.audit() }

    // ---- transactions ----------------------------------------------------------------------------------------------------------

    fun <T> tx(block: () -> T): T = db.tx(block)

    override fun close() = db.close()
}
