package com.nearbyshare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nearbyshare.MainActivity
import com.nearbyshare.R
import com.nearbyshare.core.common.TransferProgress
import com.nearbyshare.core.common.formatBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks the transfers this phone is running as a guest and renders them as one ongoing notification with a
 * determinate 0-100% bar. [TransferService] keeps the process alive while anything is active.
 */
class TransferNotifier(private val context: Context, private val progress: TransferProgress) {
    class Item(val name: String, val total: Long, val upload: Boolean)

    private val _active = MutableStateFlow<Map<String, Item>>(emptyMap())
    val active: StateFlow<Map<String, Item>> = _active.asStateFlow()

    /** [total] may be 0 while the size is not known yet (e.g. a file is still being checksummed): shown as "Preparing". */
    fun begin(id: String, name: String, total: Long, upload: Boolean) {
        val wasEmpty = _active.value.isEmpty()
        _active.update { it + (id to Item(name, total, upload)) }
        if (wasEmpty) TransferService.start(context)
    }

    /** The transfer id and size become known after the host accepts the upload. */
    fun rekey(oldId: String, newId: String, total: Long) {
        _active.update { m ->
            val item = m[oldId] ?: return@update m
            m - oldId + (newId to Item(item.name, total, item.upload))
        }
    }

    fun end(id: String, ok: Boolean, error: String? = null, announce: Boolean = true) {
        val item = _active.value[id] ?: return
        _active.update { it - id }
        progress.clear(id)
        if (announce) postResult(id, item, ok, error)
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Transfer progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Transfer results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** The ongoing progress notification for everything currently active. */
    fun build(): Notification {
        ensureChannels()
        val items = _active.value
        val bytes = progress.bytes.value
        val total = items.values.sumOf { it.total }
        val done = items.entries.sumOf { (bytes[it.key] ?: 0L).coerceIn(0L, it.value.total) }
        val known = total > 0
        val pct = if (known) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val single = items.values.singleOrNull()
        val verb = if (single?.upload == false) "Downloading" else "Uploading"
        val title = when {
            single != null -> "$verb ${single.name}" + if (known) " - $pct%" else ""
            items.isEmpty() -> "Transfer finished"
            else -> "Transferring ${items.size} files" + if (known) " - $pct%" else ""
        }
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(if (single?.upload == false) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(if (known) "$pct% - ${formatBytes(done)} of ${formatBytes(total)}" else "Preparing...")
            .setProgress(100, pct, !known)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .build()
    }

    private fun postResult(id: String, item: Item, ok: Boolean, error: String?) {
        // Finished downloads open the file themselves, so only uploads get a success notice; failures are always reported.
        if (ok && !item.upload) return
        ensureChannels()
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (ok) "Upload complete" else "${if (item.upload) "Upload" else "Download"} failed")
            .setContentText(if (ok) "${item.name} - 100%" else "${item.name}${error?.let { ": $it" } ?: ""}")
            .setProgress(if (ok) 100 else 0, if (ok) 100 else 0, false)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), n)
        } catch (_: SecurityException) {
            // notification permission not granted: the in-app Transfers screen still shows the result
        }
    }

    companion object {
        const val CHANNEL_PROGRESS = "transfers"
        const val CHANNEL_DONE = "transfers_done"
        const val PROGRESS_NOTIFICATION_ID = 2
    }
}
