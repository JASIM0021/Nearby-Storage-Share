package com.nearbyshare

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.nearbyshare.core.common.TransferProgress
import com.nearbyshare.core.database.AppDatabase
import com.nearbyshare.core.database.HostConfig
import com.nearbyshare.core.network.GuestFacade
import com.nearbyshare.core.network.HostCoordinator
import com.nearbyshare.core.network.NearbyGuest
import com.nearbyshare.core.network.lan.LanGuest
import com.nearbyshare.core.network.lan.LanHost
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
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .build()
    val identity = DeviceIdentity(context)
    val scanner = StorageScanner(context)
    val store = PrivateFileStore(context)
    val progress = TransferProgress()
    val visible = VisibleFolderStore(context)
    val files = HostFileService(db, store, visible, scanner)
    private val nearbyHost = NearbyHost(context, db, files, identity, progress, scope)
    val lanHost = LanHost(context, db, files, identity, progress, scope)

    /** Hosting over Nearby and over the local network, presented as one host. */
    val host = HostCoordinator(nearbyHost, lanHost, scope)
    val notifier = TransferNotifier(context, progress)
    private val nearbyGuest = NearbyGuest(context, db, identity, progress, notifier, scope)
    private val lanGuest = LanGuest(context, db, identity, progress, notifier, scope)

    /** Connecting to hosts found by either method, used through one set of functions. */
    val guest = GuestFacade(nearbyGuest, lanGuest, scope)

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
