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
import com.nearbyshare.core.network.GuestState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps this phone's connection to a storage host alive while the app is in the background, so the host's drive stays
 * usable from the Files app and other apps. It runs from the moment the user taps Connect until the connection ends.
 */
class GuestSessionService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = container
        if (intent?.action == ACTION_DISCONNECT) {
            c.guest.disconnect() // the state change below stops this service
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(c.guest.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        watcher?.cancel()
        watcher = c.scope.launch {
            c.guest.state.collect { s ->
                if (s is GuestState.Idle || s is GuestState.Failed) {
                    ServiceCompat.stopForeground(this@GuestSessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(s))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL_ID, "Connection to a storage host", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun build(state: GuestState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(
            this, 3, Intent(this, GuestSessionService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = when (state) {
            is GuestState.Connected -> "Connected to ${state.hostName}" to "Its storage is also available in your Files app"
            is GuestState.Connecting -> "Connecting to ${state.hostName}" to "Waiting for the host..."
            is GuestState.ConfirmPairing -> "Pairing with ${state.hostName}" to "Confirm the code on both phones"
            is GuestState.Authenticating -> "Verifying ${state.hostName}" to "Almost there..."
            else -> "Storage connection" to ""
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Disconnect", disconnect)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "guest_session"
        private const val NOTIFICATION_ID = 3
        const val ACTION_DISCONNECT = "com.nearbyshare.DISCONNECT"

        /** Called when the user taps Connect while the app is visible, which Android allows. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, GuestSessionService::class.java)) }
        }
    }
}
