package com.nearbyshare.drive

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import com.nearbyshare.NearbyStorageApp
import com.nearbyshare.R
import com.nearbyshare.core.common.formatBytes
import com.nearbyshare.core.network.protocol.Permissions
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows every paired storage host as a drive in the system Files app and in other apps' file pickers (the same mechanism
 * Google Drive uses). Everything goes through the normal authenticated connection, so the host still enforces its permissions:
 * the drive only advertises the actions the host allowed this phone.
 *
 * Limits of this version: the host must be nearby and connected (see GuestSessionService); opening a file downloads it first,
 * so large videos start after the download finishes.
 */
class NearbyDocumentsProvider : DocumentsProvider() {

    /** A listing result remembered so a single document can be described without another round trip. */
    private class Entry(val file: RemoteFile, val parentDocId: String)

    /** A file another app has created but not finished writing; it only exists on the host once the app closes it. */
    private class Pending(val name: String, val parentFileId: String?, val parentDocId: String, val mime: String)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val aliases = ConcurrentHashMap<String, String>() // pending doc id -> real doc id once uploaded
    private val infoByHost = ConcurrentHashMap<String, StorageInfo>()
    private val hostNames = ConcurrentHashMap<String, String>()
    private val fetchLocks = ConcurrentHashMap<String, Any>()
    private val uploadMutex = Mutex()

    private lateinit var authority: String

    private val app get() = context!!.applicationContext as NearbyStorageApp
    private val container get() = app.container
    private val guest get() = container.guest
    private val cacheDir get() = File(context!!.cacheDir, "drive").apply { mkdirs() }

    override fun onCreate(): Boolean {
        authority = "${context!!.packageName}.documents"
        // Fetched copies are throw-away: drop anything older than a day.
        Thread {
            val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
            runCatching { File(context!!.cacheDir, "drive").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() } }
        }.start()
        return true
    }

    // ---- ids -------------------------------------------------------------------------------------------------------

    private fun rootDocId(hostId: String) = "$hostId:root"
    private fun parse(docId: String): Pair<String, String> {
        val i = docId.indexOf(':')
        if (i <= 0) throw FileNotFoundException("Bad document id")
        return docId.substring(0, i) to docId.substring(i + 1)
    }
    private fun resolve(docId: String) = aliases[docId] ?: docId

    private fun <T> io(timeoutMs: Long = 60_000, block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) { withTimeout(timeoutMs) { block() } }

    private fun requireConnected(hostId: String) {
        if (guest.connectedHostId != hostId) {
            throw FileNotFoundException("Not connected to ${hostName(hostId)}. Open Nearby Storage Share and connect.")
        }
    }

    private fun hostName(hostId: String): String = hostNames[hostId]
        ?: runCatching { io { container.db.knownHosts().get(hostId)?.name } }.getOrNull()?.also { hostNames[hostId] = it }
        ?: "the host"

    private fun notifyChildren(parentDocId: String) {
        context?.contentResolver?.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentDocId), null)
    }

    private fun mimeOf(f: RemoteFile): String = if (f.isFolder) Document.MIME_TYPE_DIR else mimeForName(f.name)

    private fun mimeForName(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Small images only: each thumbnail is a full download over the nearby link. */
    private fun thumbnailable(f: RemoteFile) = !f.isFolder && f.sizeBytes in 1..THUMBNAIL_MAX_BYTES && mimeOf(f).startsWith("image/")

    // ---- roots -----------------------------------------------------------------------------------------------------

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val hosts = runCatching { io { container.db.knownHosts().getAll() } }.getOrDefault(emptyList())
        val connectedId = guest.connectedHostId
        for (h in hosts) {
            hostNames[h.hostId] = h.name
            val connected = connectedId == h.hostId
            val info = infoByHost[h.hostId]
            result.newRow()
                .add(Root.COLUMN_ROOT_ID, h.hostId)
                .add(Root.COLUMN_DOCUMENT_ID, rootDocId(h.hostId))
                .add(Root.COLUMN_TITLE, h.name)
                .add(
                    Root.COLUMN_SUMMARY,
                    when {
                        connected && info != null -> "${formatBytes(info.usedBytes)} of ${formatBytes(info.quotaBytes)} used"
                        connected -> "Connected"
                        else -> "Not connected - open Nearby Storage Share"
                    },
                )
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH)
                .add(Root.COLUMN_AVAILABLE_BYTES, if (connected) info?.remainingBytes else null)
                .add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return result
    }

    // ---- documents -------------------------------------------------------------------------------------------------

    private fun addRootRow(c: MatrixCursor, hostId: String) {
        val perms = infoByHost[hostId]?.permissions
        val flags = if (perms?.write == true || perms?.folders == true) Document.FLAG_DIR_SUPPORTS_CREATE else 0
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, rootDocId(hostId))
            .add(Document.COLUMN_DISPLAY_NAME, hostName(hostId))
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_FLAGS, flags)
            .add(Document.COLUMN_SIZE, null)
            .add(Document.COLUMN_LAST_MODIFIED, null)
    }

    private fun addFileRow(c: MatrixCursor, hostId: String, f: RemoteFile, parentDocId: String, perms: Permissions?) {
        val docId = "$hostId:${f.fileId}"
        entries[docId] = Entry(f, parentDocId)
        var flags = 0
        if (f.isFolder) {
            if (perms?.write == true || perms?.folders == true) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            if (perms?.overwrite == true) flags = flags or Document.FLAG_SUPPORTS_WRITE
            if (perms?.read == true && thumbnailable(f)) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }
        if (perms?.delete == true) flags = flags or Document.FLAG_SUPPORTS_DELETE
        if (perms?.write == true) flags = flags or Document.FLAG_SUPPORTS_RENAME
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, docId)
            .add(Document.COLUMN_DISPLAY_NAME, f.name)
            .add(Document.COLUMN_SIZE, if (f.isFolder) null else f.sizeBytes)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(f))
            .add(Document.COLUMN_LAST_MODIFIED, f.updatedAt)
            .add(Document.COLUMN_FLAGS, flags)
    }

    private fun statEntry(hostId: String, fileId: String): Entry {
        requireConnected(hostId)
        val f = try {
            io { guest.stat(fileId) }
        } catch (e: Exception) {
            throw FileNotFoundException(e.message ?: "Not found")
        }
        return Entry(f, f.parentId?.let { "$hostId:$it" } ?: rootDocId(hostId)).also { entries["$hostId:$fileId"] = it }
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val id = resolve(documentId)
        val (hostId, fileId) = parse(id)
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        when {
            fileId == "root" -> addRootRow(c, hostId)
            pending.containsKey(id) -> {
                val p = pending.getValue(id)
                c.newRow()
                    .add(Document.COLUMN_DOCUMENT_ID, id)
                    .add(Document.COLUMN_DISPLAY_NAME, p.name)
                    .add(Document.COLUMN_MIME_TYPE, p.mime)
                    .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE)
                    .add(Document.COLUMN_SIZE, 0L)
                    .add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            }
            else -> {
                val e = entries[id] ?: statEntry(hostId, fileId)
                addFileRow(c, hostId, e.file, e.parentDocId, infoByHost[hostId]?.permissions)
            }
        }
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val parentId = resolve(parentDocumentId)
        val (hostId, folderId) = parse(parentId)
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        c.setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(authority, parentId))
        if (guest.connectedHostId != hostId) {
            c.setExtras(Bundle().apply {
                putString(DocumentsContract.EXTRA_ERROR, "Not connected to ${hostName(hostId)}. Open Nearby Storage Share and connect.")
            })
            return c
        }
        try {
            val info = io { guest.storageInfo() }.also { infoByHost[hostId] = it }
            if (info.permissions.read) {
                io { guest.list(folderId.takeUnless { it == "root" }) }.forEach { addFileRow(c, hostId, it, parentId, info.permissions) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "list failed", e)
            c.setExtras(Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, e.message ?: "Could not load files") })
        }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = resolve(parentDocumentId)
        var cur = resolve(documentId)
        if (parent == cur) return true
        repeat(64) {
            val p = entries[cur]?.parentDocId ?: return parse(cur).first == parse(parent).first && parent.endsWith(":root")
            if (p == parent) return true
            cur = p
        }
        return false
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (guest.connectedHostId != rootId) return c
        try {
            val info = io { guest.storageInfo() }.also { infoByHost[rootId] = it }
            io { guest.search(query) }.forEach {
                addFileRow(c, rootId, it, it.parentId?.let { p -> "$rootId:$p" } ?: rootDocId(rootId), info.permissions)
            }
        } catch (e: Exception) {
            Log.w(TAG, "search failed", e)
        }
        return c
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    private fun cacheFileFor(hostId: String, f: RemoteFile) = File(cacheDir, "${hostId.take(8)}_${f.fileId}_${f.updatedAt}")

    /** Downloads [file] into [target] unless an identical copy is already there. Blocks the calling binder thread. */
    private fun ensureFetched(file: RemoteFile, target: File, signal: CancellationSignal?) {
        synchronized(fetchLocks.computeIfAbsent(target.path) { Any() }) {
            if (target.isFile && target.length() == file.sizeBytes) return
            val job = container.scope.async(Dispatchers.IO) {
                target.delete()
                guest.download(file, Uri.fromFile(target))
            }
            signal?.setOnCancelListener { job.cancel() }
            try {
                runBlocking { withTimeout(FETCH_TIMEOUT_MS) { job.await() } }
            } catch (e: Exception) {
                target.delete()
                throw FileNotFoundException("Could not download ${file.name}: ${e.message}")
            }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val id = resolve(documentId)
        val (hostId, fileId) = parse(id)
        requireConnected(hostId)
        if (mode.contains('w')) return openForWrite(id, hostId, fileId, mode)
        val entry = entries[id] ?: statEntry(hostId, fileId)
        val target = cacheFileFor(hostId, entry.file)
        ensureFetched(entry.file, target, signal)
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val id = resolve(documentId)
        val (hostId, fileId) = parse(id)
        requireConnected(hostId)
        val entry = entries[id] ?: statEntry(hostId, fileId)
        if (!thumbnailable(entry.file)) throw FileNotFoundException("No thumbnail")
        val target = cacheFileFor(hostId, entry.file)
        ensureFetched(entry.file, target, signal)
        return AssetFileDescriptor(ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    // ---- writing ---------------------------------------------------------------------------------------------------

    /** The calling app writes into a local temp file; when it closes it, the file is uploaded to the host. */
    private fun openForWrite(docId: String, hostId: String, fileId: String, mode: String): ParcelFileDescriptor {
        val pend = pending[docId]
        val existing = if (pend == null) entries[docId] ?: statEntry(hostId, fileId) else null
        val temp = File(cacheDir, "up-${UUID.randomUUID()}")
        // Read-modify-write (e.g. "rw" without truncate) needs the current content first.
        if (existing != null && mode.contains('r') && !mode.contains('t')) ensureFetched(existing.file, temp, null)
        val listener = ParcelFileDescriptor.OnCloseListener { error ->
            if (error != null) {
                temp.delete()
            } else {
                container.scope.launch(Dispatchers.IO) { finishUpload(docId, hostId, pend, existing, temp) }
            }
        }
        return ParcelFileDescriptor.open(temp, ParcelFileDescriptor.parseMode(mode), Handler(Looper.getMainLooper()), listener)
    }

    private suspend fun finishUpload(docId: String, hostId: String, pend: Pending?, existing: Entry?, temp: File) {
        uploadMutex.withLock {
            try {
                // The host does not store empty files: an app that created a file and wrote nothing leaves nothing behind.
                if (temp.length() == 0L) return@withLock
                val name = pend?.name ?: existing!!.file.name
                val parentFileId = pend?.parentFileId ?: existing!!.file.parentId
                val parentDocId = pend?.parentDocId ?: existing!!.parentDocId
                val newId = guest.upload(Uri.fromFile(temp), parentFileId, name)
                if (pend != null && newId != null) aliases[docId] = "$hostId:$newId"
                notifyChildren(parentDocId)
            } catch (e: Exception) {
                // The upload notification already reports the failure to the user.
                Log.w(TAG, "upload from another app failed", e)
            } finally {
                pending.remove(docId)
                temp.delete()
            }
        }
    }

    private fun uniqueName(hostId: String, parentFileId: String?, wanted: String): String {
        val taken = runCatching { io { guest.list(parentFileId) }.map { it.name }.toSet() }.getOrDefault(emptySet())
        if (wanted !in taken) return wanted
        val dot = wanted.lastIndexOf('.')
        val base = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        var i = 1
        while ("$base ($i)$ext" in taken) i++
        return "$base ($i)$ext"
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parentId = resolve(parentDocumentId)
        val (hostId, folder) = parse(parentId)
        requireConnected(hostId)
        val parentFileId = folder.takeUnless { it == "root" }
        val perms = try {
            io { guest.storageInfo() }.also { infoByHost[hostId] = it }.permissions
        } catch (e: Exception) {
            throw FileNotFoundException(e.message ?: "Could not reach the host")
        }
        val isDir = mimeType == Document.MIME_TYPE_DIR
        if (isDir && !perms.folders) throw SecurityException("The host did not allow creating folders")
        if (!isDir && !perms.write) throw SecurityException("The host did not allow uploading files")
        val name = uniqueName(hostId, parentFileId, displayName)
        if (isDir) {
            val f = try {
                io { guest.createFolder(name, parentFileId) }
            } catch (e: Exception) {
                throw FileNotFoundException(e.message ?: "Could not create the folder")
            }
            val docId = "$hostId:${f.fileId}"
            entries[docId] = Entry(f, parentId)
            notifyChildren(parentId)
            return docId
        }
        val docId = "$hostId:pending-${UUID.randomUUID()}"
        pending[docId] = Pending(name, parentFileId, parentId, mimeType)
        return docId
    }

    override fun deleteDocument(documentId: String) {
        val id = resolve(documentId)
        val (hostId, fileId) = parse(id)
        if (pending.remove(id) != null) return
        requireConnected(hostId)
        try {
            io { guest.delete(fileId) }
        } catch (e: Exception) {
            throw FileNotFoundException(e.message ?: "Could not delete")
        }
        entries.remove(id)?.let { notifyChildren(it.parentDocId) }
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val id = resolve(documentId)
        val (hostId, fileId) = parse(id)
        requireConnected(hostId)
        val old = entries[id] ?: statEntry(hostId, fileId)
        val renamed = try {
            io { guest.rename(fileId, displayName) }
        } catch (e: Exception) {
            throw FileNotFoundException(e.message ?: "Could not rename")
        }
        entries[id] = Entry(renamed, old.parentDocId)
        notifyChildren(old.parentDocId)
        return null // the document id does not change
    }

    companion object {
        private const val TAG = "NearbyDrive"
        private const val THUMBNAIL_MAX_BYTES = 2L * 1024 * 1024
        private const val FETCH_TIMEOUT_MS = 30 * 60_000L

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_MIME_TYPES,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
        )
    }
}
