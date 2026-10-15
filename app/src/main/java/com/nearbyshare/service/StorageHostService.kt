package com.nearbyshare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nearbyshare.MainActivity
import com.nearbyshare.R
import com.nearbyshare.container
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps hosting alive in the background. It owns nothing itself: advertising, sessions and transfers live in
 * [com.nearbyshare.core.network.NearbyHost]; the service only holds the process in the foreground and shows the
 * persistent notification with a Stop action.
 */
class StorageHostService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }
        startSharing()
        return START_STICKY
    }

    private fun startSharing() {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(0),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        val c = container
        watcher?.cancel()
        watcher = c.scope.launch {
            // After a system restart the service comes back with a null intent: only resume if the user still wants sharing on.
            if (c.db.hostConfig().get()?.sharingEnabled != true) {
                stopSelf()
                return@launch
            }
            try {
                c.host.start()
            } catch (e: Exception) {
                c.db.hostConfig().get()?.let { c.db.hostConfig().upsert(it.copy(sharingEnabled = false)) }
                stopForegroundAndSelf()
                return@launch
            }
            c.host.guests.collect { guests ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(guests.count { it.authenticated }))
            }
        }
    }

    private fun stopSharing() {
        val c = container
        watcher?.cancel()
        c.scope.launch {
            c.db.hostConfig().get()?.let { c.db.hostConfig().upsert(it.copy(sharingEnabled = false, updatedAt = System.currentTimeMillis())) }
            c.host.stop()
            stopForegroundAndSelf()
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.host_channel_name), NotificationManager.IMPORTANCE_LOW)
        ch.description = getString(R.string.host_channel_desc)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(guestCount: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StorageHostService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("Sharing storage")
            .setContentText(if (guestCount == 0) "Visible to nearby devices" else "$guestCount device(s) connected")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop sharing", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "host"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.nearbyshare.START"
        const val ACTION_STOP = "com.nearbyshare.STOP"

        /** Must be called while the app is visible (user tapped the switch): Android 12+ blocks background FGS starts. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StorageHostService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StorageHostService::class.java).setAction(ACTION_STOP))
        }
    }
}
