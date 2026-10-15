package com.nearbyshare.core.common

import java.util.Locale

/** All quotas and sizes are binary: 1 GB in this app means 1 GiB (1,073,741,824 bytes). */
const val KIB = 1024L
const val MIB = 1024L * KIB
const val GIB = 1024L * MIB

fun formatBytes(bytes: Long): String {
    if (bytes < KIB) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = -1
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[i])
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
