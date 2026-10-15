package com.nearbyshare.core.storage

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * App-private object store. The ONLY code that touches the shared-storage directory.
 * Objects are addressed by server-generated UUID keys; guest-supplied names never reach the filesystem.
 */
class PrivateFileStore(context: Context) {
    private val root = File(context.filesDir, "shared_storage").also { it.mkdirs() }
    private val objects = File(root, "objects").also { it.mkdirs() }
    private val staging = File(root, "staging").also { it.mkdirs() }

    fun newStagingFile(): File = File(staging, UUID.randomUUID().toString())

    fun stagingFile(key: String): File = File(staging, requireKey(key))

    /** Atomically moves a verified staging file into the object store and returns its storage key. */
    fun commit(stagingFile: File): String {
        val key = UUID.randomUUID().toString()
        val dest = resolve(key)
        dest.parentFile!!.mkdirs()
        try {
            Files.move(stagingFile.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(stagingFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return key
    }

    fun resolve(key: String): File {
        val k = requireKey(key)
        return File(File(objects, k.substring(0, 2)), k)
    }

    fun delete(key: String?) {
        if (key == null) return
        runCatching { resolve(key).delete() }
    }

    fun clearStaging() {
        staging.listFiles()?.forEach { it.delete() }
    }

    private fun requireKey(key: String): String {
        UUID.fromString(key) // throws on anything that is not a canonical UUID: no separators, no traversal
        require(key.length == 36)
        return key
    }
}
