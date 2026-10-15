package com.nearbyshare.core.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.OutputStream

/**
 * "Visible folder" storage: guest files live under a folder the host picked with the system folder picker
 * (Storage Access Framework), with their real names, so they show up in any file manager.
 *
 * Layout:  <picked folder>/<Guest name (a1b2)>/<guest's folders>/<file>
 * Files stored here are referenced in the database as "saf:<document uri>".
 */
class VisibleFolderStore(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun root(treeUri: String?): DocumentFile? {
        if (treeUri == null) return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
            ?.takeIf { runCatching { it.exists() && it.isDirectory && it.canWrite() }.getOrDefault(false) }
    }

    /** Free-space checks only make sense for the internal volume; for SD cards / USB the provider reports nothing. */
    fun isPrimaryVolume(treeUri: String): Boolean =
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)).startsWith("primary:") }.getOrDefault(true)

    /** One folder per guest. Found by the short id tag so renaming the phone does not create a second folder. */
    fun guestDir(root: DocumentFile, displayName: String, deviceId: String, create: Boolean): DocumentFile? {
        val tag = " (${deviceId.take(4)})"
        root.listFiles().firstOrNull { it.isDirectory && it.name?.endsWith(tag) == true }?.let { return it }
        if (!create) return null
        return root.createDirectory(safeName(displayName) + tag) ?: throw IOException("Cannot create the guest folder")
    }

    fun ensureChildDir(parent: DocumentFile, name: String): DocumentFile {
        parent.findFile(name)?.takeIf { it.isDirectory }?.let { return it }
        return parent.createDirectory(name) ?: throw IOException("Cannot create folder")
    }

    fun findChildDir(parent: DocumentFile, name: String): DocumentFile? = parent.findFile(name)?.takeIf { it.isDirectory }

    /** Hidden in-progress file (leading dot) so a half-written upload never looks like a finished one. */
    fun createPart(dir: DocumentFile, finalName: String): DocumentFile =
        dir.createFile("application/octet-stream", ".$finalName.part") ?: throw IOException("Cannot create file")

    /**
     * Gives the finished part its real name. Only replaces a file this app owns ([replaceUri]); a file the host
     * placed there themselves is never overwritten, the upload gets "name (1)" instead.
     */
    fun finalize(part: DocumentFile, dir: DocumentFile, finalName: String, replaceUri: String?): DocumentFile {
        val existing = dir.findFile(finalName)
        val name = when {
            existing == null -> finalName
            replaceUri != null && existing.uri.toString() == replaceUri -> {
                existing.delete()
                finalName
            }
            else -> uniqueName(dir, finalName)
        }
        if (!part.renameTo(name)) throw IOException("Cannot name the file")
        return dir.findFile(name) ?: throw IOException("File vanished after rename")
    }

    private fun uniqueName(dir: DocumentFile, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (dir.findFile(candidate) == null) return candidate
            i++
        }
    }

    fun fromKey(key: String): DocumentFile? =
        if (isKey(key)) runCatching { DocumentFile.fromSingleUri(context, Uri.parse(key.removePrefix(PREFIX))) }.getOrNull() else null

    fun delete(key: String?) {
        if (key == null) return
        runCatching { fromKey(key)?.delete() }
    }

    fun openOut(doc: DocumentFile): OutputStream = resolver.openOutputStream(doc.uri, "w") ?: throw IOException("Cannot write file")

    fun openFd(doc: DocumentFile): ParcelFileDescriptor = resolver.openFileDescriptor(doc.uri, "r") ?: throw IOException("Cannot read file")

    companion object {
        const val PREFIX = "saf:"
        fun isKey(key: String?) = key != null && key.startsWith(PREFIX)
        fun keyOf(doc: DocumentFile) = PREFIX + doc.uri

        fun safeName(raw: String): String = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifBlank { "device" }
    }
}
