package com.nearbyshare.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import com.nearbyshare.lan.BeaconListener
import com.nearbyshare.lan.JvmIdentity
import com.nearbyshare.lan.LanConstants
import com.nearbyshare.lan.LanGuestClient
import com.nearbyshare.lan.LanHostInfo
import com.nearbyshare.lan.LanIdentityChangedException
import com.nearbyshare.lan.LanPairingDeclinedException
import com.nearbyshare.lan.LanRemoteException
import com.nearbyshare.lan.LanRepairRequiredException
import com.nearbyshare.lan.LanSession
import com.nearbyshare.lan.digestOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Where this connection stands. */
sealed interface Conn {
    data object Idle : Conn
    class Connecting(val label: String) : Conn

    /** The same 6-digit code must be showing on the host. [accepted] = this side confirmed and waits for the host. */
    class Pairing(val hostName: String, val code: String, val accepted: Boolean) : Conn
    class Connected(val hostId: String, val hostName: String) : Conn
    class Failed(val message: String) : Conn
}

/** One upload or download, for the transfer list. Fields are Compose state so the UI follows them. */
class TransferItem(val name: String, val upload: Boolean) {
    var bytes by mutableStateOf(0L)
    var total by mutableStateOf(0L)

    /** "Preparing", "Active", "Done" or "Failed: ..." */
    var status by mutableStateOf("Preparing")
    val finished: Boolean get() = status == "Done" || status.startsWith("Failed")
}

/**
 * All state and behaviour of the Windows app, kept apart from the screens so it can be tested without a window.
 * [dataDir] holds this PC's identity (key and certificate), device name and the hosts it has paired with.
 */
class DesktopApp(private val scope: CoroutineScope, val dataDir: File = defaultDataDir()) {
    val device: Device = Device.loadOrCreate(File(dataDir, "device.json"))
    private val identity = JvmIdentity.loadOrCreate(dataDir, "Nearby Storage Share (${device.name})")
    val pins = FilePins(File(dataDir, "hosts.json"))
    private val client = LanGuestClient(identity, device.id, { device.name }, scope)
    private val listener = BeaconListener(scope, selfId = device.id)
    private val cacheDir = File(dataDir, "cache").apply { mkdirs() }

    /** Hosts announcing themselves on the network right now. */
    val discovered: StateFlow<List<LanHostInfo>> = listener.hosts

    var conn by mutableStateOf<Conn>(Conn.Idle)
        private set

    // ---- file browser state ------------------------------------------------------------------------------------------
    var path by mutableStateOf(listOf<RemoteFile>())
        private set
    var items by mutableStateOf(listOf<RemoteFile>())
        private set
    var info by mutableStateOf<StorageInfo?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    val transfers = mutableStateListOf<TransferItem>()

    @Volatile private var session: LanSession? = null
    @Volatile private var pairingAnswer: CompletableDeferred<Boolean>? = null
    private var connectJob: Job? = null

    init {
        // opened files are throw-away copies: drop the ones older than a day
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        cacheDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun startScanning() = listener.start()
    fun stopScanning() = listener.stop()

    fun shutdown() {
        listener.stop()
        disconnect()
    }

    // ---- connecting ----------------------------------------------------------------------------------------------------

    fun connect(host: LanHostInfo) = connectTo(host.address, host.port, host.id, host.name)

    /** "192.168.1.20" or "192.168.1.20:45820". Returns an error text, or null when the connection was started. */
    fun connectByAddress(text: String): String? {
        val t = text.trim()
        if (!Regex("^[A-Za-z0-9.\\-]+(:\\d{1,5})?$").matches(t)) return "Enter an address like 192.168.1.20 or 192.168.1.20:45820"
        val port = t.substringAfter(':', "").ifEmpty { null }?.toInt() ?: LanConstants.DEFAULT_PORT
        if (port !in 1..65535) return "The port must be between 1 and 65535"
        connectTo(t.substringBefore(':'), port, null, t)
        return null
    }

    fun connectSaved(saved: SavedHost) = connectTo(saved.address, saved.port, saved.id, saved.name)

    private fun connectTo(address: String, port: Int, hostId: String?, label: String) {
        if (conn !is Conn.Idle && conn !is Conn.Failed) return
        conn = Conn.Connecting(label)
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                try {
                    open(address, port, hostId)
                } catch (e: LanRepairRequiredException) {
                    // the host forgot this PC: forget its pin here too and pair again
                    pins.forgetFingerprint(e.fingerprint)
                    open(address, port, hostId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    private suspend fun open(address: String, port: Int, hostId: String?) {
        val s = client.connect(address, port, hostId, pins) { code, hostName ->
            val answer = CompletableDeferred<Boolean>()
            pairingAnswer = answer
            conn = Conn.Pairing(hostName, code, accepted = false)
            val ok = withTimeoutOrNull(LanConstants.PAIRING_TIMEOUT_MS.toLong()) { answer.await() } ?: false
            if (ok) conn = Conn.Pairing(hostName, code, accepted = true)
            ok
        }
        pairingAnswer = null
        pins.rememberAddress(s.hostId, address, port)
        session = s
        path = emptyList()
        items = emptyList()
        conn = Conn.Connected(s.hostId, s.hostName)
        refresh()
        scope.launch {
            val reason = s.closedReason.filterNotNull().first()
            if (session === s) {
                session = null
                conn = Conn.Failed(reason)
            }
        }
    }

    private fun fail(e: Throwable) {
        pairingAnswer = null
        conn = when (e) {
            is LanPairingDeclinedException -> Conn.Idle
            is SocketTimeoutException -> Conn.Failed("Could not reach the host. Make sure both devices are on the same network.")
            is ConnectException -> Conn.Failed("The host refused the connection. Is sharing switched on there?")
            is LanIdentityChangedException, is LanRemoteException -> Conn.Failed(e.message ?: "Connection failed")
            else -> Conn.Failed(e.message ?: "Connection failed")
        }
    }

    /** The user compared the code with the host's screen. */
    fun confirmPairing(accept: Boolean) {
        pairingAnswer?.complete(accept)
    }

    fun disconnect() {
        connectJob?.cancel()
        pairingAnswer?.complete(false)
        val s = session
        session = null
        conn = Conn.Idle
        path = emptyList()
        items = emptyList()
        info = null
        s?.close()
    }

    // ---- browsing --------------------------------------------------------------------------------------------------------

    private fun sess(): LanSession = session ?: throw IllegalStateException("Not connected")

    private fun launchOp(label: String, block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = "$label: ${e.message ?: "failed"}"
            }
        }
    }

    fun refresh() = launchOp("Could not load files") {
        loading = true
        try {
            val s = sess()
            val i = s.storageInfo()
            info = i
            items = if (i.permissions.read) s.list(path.lastOrNull()?.fileId) else emptyList()
        } finally {
            loading = false
        }
    }

    fun openFolder(folder: RemoteFile) {
        path = path + folder
        refresh()
    }

    fun goTo(depth: Int) {
        path = path.take(depth)
        refresh()
    }

    fun up(): Boolean {
        if (path.isEmpty()) return false
        goTo(path.size - 1)
        return true
    }

    fun createFolder(name: String) = launchOp("Could not create folder") {
        sess().createFolder(name.trim(), path.lastOrNull()?.fileId)
        refresh()
    }

    fun rename(file: RemoteFile, newName: String) = launchOp("Could not rename") {
        sess().rename(file.fileId, newName.trim())
        refresh()
    }

    fun delete(file: RemoteFile) = launchOp("Could not delete") {
        sess().delete(file.fileId)
        refresh()
    }

    // ---- transfers ---------------------------------------------------------------------------------------------------------

    fun upload(files: List<File>) {
        val parent = path.lastOrNull()?.fileId
        // the host takes one upload at a time per device, so they run one after another
        scope.launch(Dispatchers.IO) {
            for (f in files) {
                val item = TransferItem(f.name, upload = true)
                transfers.add(0, item)
                try {
                    val (size, sha) = digestOf { f.inputStream() }
                    item.total = size
                    item.status = "Active"
                    sess().upload(f.name, parent, size, sha, { f.inputStream() }, onProgress = { item.bytes = it })
                    item.status = "Done"
                } catch (e: CancellationException) {
                    item.status = "Failed: cancelled"
                    throw e
                } catch (e: Throwable) {
                    item.status = "Failed: ${e.message ?: "error"}"
                }
            }
            refresh()
        }
    }

    /** Saves [file] to [dest]; a damaged or partial file is never left behind. */
    fun download(file: RemoteFile, dest: File, onDone: (File) -> Unit = {}) {
        val item = TransferItem(file.name, upload = false)
        transfers.add(0, item)
        scope.launch(Dispatchers.IO) {
            try {
                fetch(file, dest, item)
                onDone(dest)
            } catch (e: CancellationException) {
                item.status = "Failed: cancelled"
                throw e
            } catch (e: Throwable) {
                item.status = "Failed: ${e.message ?: "error"}"
                message = "Could not download ${file.name}: ${e.message}"
            }
        }
    }

    /** Downloads into the cache (unless an identical copy is there) and hands the file to [onReady] to open. */
    fun openInCache(file: RemoteFile, onReady: (File) -> Unit) {
        val safe = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val target = File(cacheDir, "${file.fileId}_${file.updatedAt}_$safe")
        if (target.isFile && target.length() == file.sizeBytes) {
            onReady(target)
            return
        }
        download(file, target, onReady)
    }

    private suspend fun fetch(file: RemoteFile, dest: File, item: TransferItem) {
        try {
            item.total = file.sizeBytes
            item.status = "Active"
            dest.outputStream().use { out ->
                sess().download(file.fileId, out, onProgress = { item.bytes = it })
            }
            item.status = "Done"
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
    }

    fun clearFinishedTransfers() = transfers.removeAll { it.finished }

    companion object {
        fun defaultDataDir(): File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "NearbyStorageShare")
    }
}
