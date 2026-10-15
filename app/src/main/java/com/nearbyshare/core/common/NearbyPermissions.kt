package com.nearbyshare.core.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions needed by Nearby Connections, by Android version. Requested only when hosting/scanning starts. */
object NearbyPermissions {
    fun required(): Array<String> = buildList {
        // Location is requested on every version: Nearby reports 8034 (missing COARSE_LOCATION) without it, even on Android 13+.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun missing(context: Context): List<String> =
        required().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    /** The notification permission is optional: the foreground service still runs without it. */
    fun missingCritical(context: Context): List<String> = missing(context) - Manifest.permission.POST_NOTIFICATIONS
}
