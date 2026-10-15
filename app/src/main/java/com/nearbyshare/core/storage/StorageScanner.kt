package com.nearbyshare.core.storage

import android.content.Context
import android.os.StatFs
import java.io.File

class StorageScanner(private val context: Context) {
    private fun stat() = StatFs(context.filesDir.absolutePath)

    fun totalBytes(): Long = stat().totalBytes
    fun freeBytes(): Long = stat().availableBytes

    /** Free space left after [incoming] bytes must still exceed the host's safety reserve. */
    fun hasSafeFreeSpace(incoming: Long, reserve: Long): Boolean = freeBytes() - incoming >= reserve

    /** Largest quota the host may offer: what is free now, minus the reserve, plus what guests already occupy. */
    fun maxOfferable(reserve: Long, alreadyStored: Long): Long =
        (freeBytes() - reserve + alreadyStored).coerceAtLeast(0)

    @Suppress("unused")
    fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
