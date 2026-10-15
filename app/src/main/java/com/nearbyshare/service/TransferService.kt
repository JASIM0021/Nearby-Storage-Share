package com.nearbyshare.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nearbyshare.container
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Foreground service that exists only while this phone is uploading or downloading as a guest. It keeps the process
 * alive if the user leaves the app mid-transfer and owns the live progress notification.
 */
class TransferService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = container
        ServiceCompat.startForeground(
            this, TransferNotifier.PROGRESS_NOTIFICATION_ID, c.notifier.build(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        watcher?.cancel()
        watcher = c.scope.launch {
            // Byte counters tick very often; redraw the notification at most a few times per second.
            combine(c.notifier.active, c.progress.bytes) { active, _ -> active }.debounce(250).collect { active ->
                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(TransferNotifier.PROGRESS_NOTIFICATION_ID, c.notifier.build())
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    companion object {
        /** Called from a user-initiated transfer while the app is visible, which Android allows. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java)) }
        }
    }
}
