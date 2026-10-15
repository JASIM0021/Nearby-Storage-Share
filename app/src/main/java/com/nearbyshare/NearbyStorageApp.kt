package com.nearbyshare

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.nearbyshare.core.common.TransferProgress
import com.nearbyshare.core.database.AppDatabase
import com.nearbyshare.core.database.HostConfig
import com.nearbyshare.core.network.NearbyGuest
import com.nearbyshare.core.network.NearbyHost
import com.nearbyshare.core.security.DeviceIdentity
import com.nearbyshare.core.storage.PrivateFileStore
import com.nearbyshare.core.storage.StorageScanner
import com.nearbyshare.core.storage.VisibleFolderStore
import com.nearbyshare.domain.HostFileService
import com.nearbyshare.service.TransferNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container: one instance per process, shared by the UI and the foreground service. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("AppScope", "uncaught in background task", e) },
    )
    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "nearbyshare.db")
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()
    val identity = DeviceIdentity(context)
    val scanner = StorageScanner(context)
    val store = PrivateFileStore(context)
    val progress = TransferProgress()
    val visible = VisibleFolderStore(context)
    val files = HostFileService(db, store, visible, scanner)
    val host = NearbyHost(context, db, files, identity, progress, scope)
    val notifier = TransferNotifier(context, progress)
    val guest = NearbyGuest(context, db, identity, progress, notifier, scope)

    init {
        // The drive in the Files app lists hosts and their connection state: tell it whenever the connection changes.
        scope.launch {
            guest.state.collect {
                context.contentResolver.notifyChange(
                    android.provider.DocumentsContract.buildRootsUri("${context.packageName}.documents"), null,
                )
            }
        }
        scope.launch(Dispatchers.IO) {
            if (db.hostConfig().get() == null) db.hostConfig().upsert(HostConfig())
            // A fresh process has nothing in flight: release stale reservations and drop orphaned staging files.
            files.recoverOnStart()
        }
    }
}

class NearbyStorageApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as NearbyStorageApp).container
