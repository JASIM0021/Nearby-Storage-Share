package com.nearbyshare.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        HostConfig::class, Device::class, DeviceAccess::class, SharedFile::class,
        Transfer::class, AuditEvent::class, KnownHost::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        /** v3: certificate fingerprints for the same-network (LAN) connection method. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN cert_fp TEXT")
                db.execSQL("ALTER TABLE known_hosts ADD COLUMN cert_fp TEXT")
            }
        }

        /** v2: host-selectable storage location. Existing installs keep all data and stay on private storage. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE host_config ADD COLUMN storage_mode TEXT NOT NULL DEFAULT 'PRIVATE'")
                db.execSQL("ALTER TABLE host_config ADD COLUMN visible_tree_uri TEXT")
            }
        }
    }

    abstract fun hostConfig(): HostConfigDao
    abstract fun devices(): DeviceDao
    abstract fun access(): AccessDao
    abstract fun files(): FileDao
    abstract fun transfers(): TransferDao
    abstract fun audit(): AuditDao
    abstract fun knownHosts(): KnownHostDao
}
