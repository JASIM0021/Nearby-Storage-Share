package com.nearbyshare.feature

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nearbyshare.container
import com.nearbyshare.core.database.Transfer
import com.nearbyshare.core.database.TransferRole
import com.nearbyshare.core.database.TransferState
import com.nearbyshare.core.network.DiscoveredHost
import com.nearbyshare.core.network.GuestState
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FilesUiState(
    val path: List<RemoteFile> = emptyList(), // folder stack, root = empty
    val items: List<RemoteFile> = emptyList(),
    val info: StorageInfo? = null,
    val loading: Boolean = false,
)

class GuestViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val guest = c.guest

    val state: StateFlow<GuestState> = guest.state
    val hosts: StateFlow<List<DiscoveredHost>> = guest.hosts
    val discovering: StateFlow<Boolean> = guest.discovering

    val transfers: StateFlow<List<Transfer>> = c.db.transfers().observeRecent(100)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val progress = c.progress.bytes

    private val _files = MutableStateFlow(FilesUiState())
    val files: StateFlow<FilesUiState> = _files.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            guest.state.collect {
                if (it is GuestState.Connected) {
                    _files.value = FilesUiState()
                    refresh()
                } else {
                    _files.value = FilesUiState()
                }
            }
        }
    }

    fun startScan() = guest.startDiscovery()

    /** Used when Bluetooth/location permissions were refused: devices on the same Wi-Fi can still be found. */
    fun startNetworkScan() = guest.startNetworkDiscovery()

    /** "192.168.1.20" or "192.168.1.20:45820". Returns an error message, or null when the connection was started. */
    fun connectByAddress(text: String): String? {
        val t = text.trim()
        if (!Regex("^[A-Za-z0-9.\\-]+(:\\d{1,5})?$").matches(t)) return "Enter an address like 192.168.1.20 or 192.168.1.20:45820"
        val host = t.substringBefore(':')
        val port = t.substringAfter(':', "").ifEmpty { null }?.toInt() ?: com.nearbyshare.lan.LanConstants.DEFAULT_PORT
        if (port !in 1..65535) return "The port must be between 1 and 65535"
        guest.connectByAddress(host, port)
        return null
    }
    fun stopScan() = guest.stopDiscovery()
    fun connect(host: DiscoveredHost) = guest.connect(host)
    fun confirmPairing(accept: Boolean) = guest.confirmPairing(accept)
    fun disconnect() = guest.disconnect()

    private fun launchOp(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit("$label: ${e.message ?: "failed"}")
            }
        }
    }

    fun refresh() {
        launchOp("Could not load files") {
            _files.value = _files.value.copy(loading = true)
            try {
                val parent = _files.value.path.lastOrNull()?.fileId
                val info = guest.storageInfo()
                val items = if (info.permissions.read) guest.list(parent) else emptyList()
                _files.value = _files.value.copy(items = items, info = info)
            } finally {
                _files.value = _files.value.copy(loading = false)
            }
        }
    }

    fun openFolder(folder: RemoteFile) {
        _files.value = _files.value.copy(path = _files.value.path + folder)
        refresh()
    }

    fun navigateTo(depth: Int) {
        _files.value = _files.value.copy(path = _files.value.path.take(depth))
        refresh()
    }

    /** Returns true when a level was popped (so the caller can consume the back press). */
    fun up(): Boolean {
        if (_files.value.path.isEmpty()) return false
        navigateTo(_files.value.path.size - 1)
        return true
    }

    /** A fetched file ready to hand to a viewer app. */
    class PreviewRequest(val uri: Uri, val mime: String, val name: String)

    private val _previews = MutableSharedFlow<PreviewRequest>(extraBufferCapacity = 1)
    val previewRequests: SharedFlow<PreviewRequest> = _previews.asSharedFlow()

    /** Name of the file currently being fetched for opening, or null. */
    private val _opening = MutableStateFlow<String?>(null)
    val opening: StateFlow<String?> = _opening.asStateFlow()

    /**
     * Fetches the file from the host into this phone's private cache (verified by the normal SHA-256 check) and asks the UI to
     * open it in whichever app handles its type. A file already fetched and unchanged is opened straight from the cache.
     */
    fun open(file: RemoteFile) {
        if (_opening.value != null) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _opening.value = file.name
            val dir = java.io.File(app.cacheDir, "previews").apply { mkdirs() }
            val safe = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
            val target = java.io.File(dir, "${file.fileId}_${file.updatedAt}_$safe")
            try {
                if (!(target.isFile && target.length() == file.sizeBytes)) {
                    target.delete()
                    guest.download(file, Uri.fromFile(target))
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
                _previews.emit(PreviewRequest(uri, mimeFor(file.name), file.name))
            } catch (e: kotlinx.coroutines.CancellationException) {
                target.delete()
                throw e
            } catch (e: Exception) {
                target.delete()
                _events.tryEmit("Could not open ${file.name}: ${e.message ?: "failed"}")
            } finally {
                _opening.value = null
            }
        }
    }

    private fun mimeFor(name: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"

    init {
        // Fetched previews are throw-away copies of the user's own files: drop the ones older than a day.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
            java.io.File(getApplication<Application>().cacheDir, "previews").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    fun upload(uri: Uri) {
        val parent = _files.value.path.lastOrNull()?.fileId
        launchOp("Upload failed") {
            guest.upload(uri, parent)
            _events.tryEmit("Upload complete")
            refresh()
        }
    }

    fun download(file: RemoteFile, dest: Uri) {
        launchOp("Download failed") {
            guest.download(file, dest)
            _events.tryEmit("Saved ${file.name}")
        }
    }

    fun delete(file: RemoteFile) = launchOp("Delete failed") {
        guest.delete(file.fileId)
        refresh()
    }

    fun createFolder(name: String) {
        val parent = _files.value.path.lastOrNull()?.fileId
        launchOp("Could not create folder") {
            guest.createFolder(name, parent)
            refresh()
        }
    }

    fun cancelTransfer(t: Transfer) {
        if (t.role == TransferRole.GUEST && t.state == TransferState.SENDING) launchOp("Cancel failed") { guest.cancelTransfer(t.transferId) }
    }

    fun clearFinished() {
        viewModelScope.launch { c.db.transfers().clearFinished() }
    }
}
