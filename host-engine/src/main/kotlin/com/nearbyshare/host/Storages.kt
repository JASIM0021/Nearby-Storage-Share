package com.nearbyshare.host

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Hidden storage: files live under random UUID names inside the app's own folder, so their real names (and anything a guest
 * says) never reach the file system. The ONLY code that touches that folder.
 */
class PrivateStore(root: File) {
    private val objects = File(root, "objects").also { it.mkdirs() }
    private val staging = File(root, "staging").also { it.mkdirs() }

    fun newStaging(): File = File(staging, UUID.randomUUID().toString())

    fun stagingFile(key: String): File = File(staging, requireKey(key))

    /** Atomically moves a verified staging file into the object store and returns its key. */
    fun commit(staged: File): String {
        val key = UUID.randomUUID().toString()
        val dest = resolve(key)
        dest.parentFile.mkdirs()
        try {
            Files.move(staged.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
        UUID.fromString(key) // only a canonical UUID: no separators, no traversal
        require(key.length == 36)
        return key
    }
}

/**
 * Visible storage: guest files saved in a folder the host chose, with their real names, one subfolder per guest
 * ("Name (a1b2)"), so they can be browsed in Explorer. Files here are referenced as "file:<absolute path>".
 */
class VisibleStore {
    fun root(dir: String?): Path? {
        val p = dir?.takeIf { it.isNotBlank() }?.let { runCatching { Paths.get(it) }.getOrNull() } ?: return null
        return p.takeIf { Files.isDirectory(it) && Files.isWritable(it) }
    }

    /** One folder per guest, found by its short id tag so renaming the PC or phone never creates a second folder. */
    fun guestDir(root: Path, displayName: String, deviceId: String, create: Boolean): Path? {
        val tag = " (${deviceId.take(4)})"
        Files.newDirectoryStream(root).use { ds ->
            for (p in ds) if (Files.isDirectory(p) && p.fileName.toString().endsWith(tag)) return p
        }
        if (!create) return null
        return Files.createDirectory(child(root, fsSafe(displayName) + tag))
    }

    fun childDir(parent: Path, name: String, create: Boolean): Path? {
        val p = child(parent, fsSafe(name))
        if (Files.isDirectory(p)) return p
        return if (create) Files.createDirectory(p) else null
    }

    /** Hidden in-progress file so a half-written upload never looks like a finished one. */
    fun createPart(dir: Path, finalName: String): Path {
        val part = child(dir, "." + fsSafe(finalName) + ".part")
        Files.deleteIfExists(part)
        Files.createFile(part)
        runCatching { Files.setAttribute(part, "dos:hidden", true) }
        return part
    }

    /** Gives the finished part its real name. Only replaces a file this app owns ([replacePath]); a file the host put there is never overwritten. */
    fun finalize(part: Path, dir: Path, finalName: String, replacePath: String?): Path {
        val wanted = child(dir, fsSafe(finalName))
        val target = when {
            !Files.exists(wanted) -> wanted
            replacePath != null && wanted == Paths.get(replacePath) -> wanted.also { Files.delete(it) }
            else -> uniqueSibling(dir, fsSafe(finalName))
        }
        runCatching { Files.setAttribute(part, "dos:hidden", false) }
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part, target)
        }
        return target
    }

    private fun uniqueSibling(dir: Path, name: String): Path {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val p = child(dir, "$base ($i)$ext")
            if (!Files.exists(p)) return p
            i++
        }
    }

    /** Resolves [name] under [parent] and refuses anything that would leave it. */
    private fun child(parent: Path, name: String): Path {
        val p = parent.resolve(name).normalize()
        if (!p.startsWith(parent.normalize()) || p == parent.normalize()) throw IOException("Path escapes the shared folder")
        return p
    }

    fun delete(key: String?) {
        if (key == null || !isKey(key)) return
        runCatching { Files.deleteIfExists(pathOf(key)) }
    }

    fun pathOf(key: String): Path = Paths.get(key.removePrefix(PREFIX))

    companion object {
        const val PREFIX = "file:"
        fun isKey(key: String?) = key != null && key.startsWith(PREFIX)
        fun keyOf(p: Path) = PREFIX + p.toAbsolutePath().normalize().toString()

        private val reserved = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", RegexOption.IGNORE_CASE)

        /** A name that is safe as one path component on Windows (and elsewhere): no illegal characters, no reserved device names. */
        fun fsSafe(raw: String): String {
            var s = raw.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), "_").trimEnd('.', ' ').ifBlank { "_" }
            if (reserved.matches(s)) s = "_$s"
            return s.take(120)
        }
    }
}
