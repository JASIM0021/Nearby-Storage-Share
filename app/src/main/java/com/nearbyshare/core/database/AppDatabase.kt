package com.nearbyshare.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        HostConfig::class, Device::class, DeviceAccess::class, SharedFile::class,
        Transfer::class, AuditEvent::class, KnownHost::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
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
