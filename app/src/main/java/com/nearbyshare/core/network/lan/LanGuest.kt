package com.nearbyshare.core.network.lan

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.nearbyshare.core.common.TransferProgress
import com.nearbyshare.core.database.AppDatabase
import com.nearbyshare.core.database.KnownHost
import com.nearbyshare.core.database.Transfer
import com.nearbyshare.core.database.TransferDirection
import com.nearbyshare.core.database.TransferRole
import com.nearbyshare.core.database.TransferState
import com.nearbyshare.core.network.DiscoveredHost
import com.nearbyshare.core.network.GuestState
import com.nearbyshare.core.network.GuestTransport
import com.nearbyshare.core.network.protocol.ErrorCode
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import com.nearbyshare.core.security.DeviceIdentity
import com.nearbyshare.lan.BeaconListener
import com.nearbyshare.lan.LanConstants
import com.nearbyshare.lan.LanGuestClient
import com.nearbyshare.lan.LanIdentityChangedException
import com.nearbyshare.lan.LanPairingDeclinedException
import com.nearbyshare.lan.LanRemoteException
import com.nearbyshare.lan.LanRepairRequiredException
import com.nearbyshare.lan.LanSession
import com.nearbyshare.lan.PinStore
import com.nearbyshare.lan.PinnedHost
import com.nearbyshare.lan.digestOf
import com.nearbyshare.service.GuestSessionService
import com.nearbyshare.service.TransferNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Connecting to hosts over the local network (same Wi-Fi or hotspot). Discovery is a UDP beacon; the connection is TLS with
 * a pinned certificate. Offers the same operations as [com.nearbyshare.core.network.NearbyGuest].
 */
class LanGuest(
    context: Context,
    private val db: AppDatabase,
    private val identity: DeviceIdentity,
    private val progress: TransferProgress,
    private val notifier: TransferNotifier,
    private val scope: CoroutineScope,
) : GuestTransport {
    private val appContext = context.applicationContext
    private val listener = BeaconListener(scope, selfId = identity.deviceId)

    // The Keystore key is created the first time a connection is made.
    private val client by lazy { LanGuestClient(AndroidLanIdentity.get(), identity.deviceId, { identity.displayName }, scope) }

    private val _state = MutableStateFlow<GuestState>(GuestState.Idle)
    val state: StateFlow<GuestState> = _state.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    val hosts: StateFlow<List<DiscoveredHost>> = listener.hosts
        .map { list -> list.map { DiscoveredHost("lan:${it.id}", it.id, it.name, it.address, it.port) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    @Volatile private var session: LanSession? = null
    @Volatile private var pairingAnswer: CompletableDeferred<Boolean>? = null
    private var connectJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ---- discovery --------------------------------------------------------------------------------------------------

    fun startDiscovery() {
        if (_discovering.value) return
        // Some phones drop broadcast packets unless an app holds a multicast lock.
        multicastLock = runCatching {
            (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("nss-lan").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        listener.start()
        _discovering.value = true
    }

    fun stopDiscovery() {
        listener.stop()
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
        _discovering.value = false
    }

    // ---- connection -------------------------------------------------------------------------------------------------

    fun connect(host: DiscoveredHost) {
        val address = host.address ?: return
        connectTo(address, host.port, host.hostId, host.name)
    }

    /** For when discovery is blocked (some routers hide devices from each other): type the host's address. */
    fun connectByAddress(address: String, port: Int = LanConstants.DEFAULT_PORT) = connectTo(address.trim(), port, null, address.trim())

    private fun connectTo(address: String, port: Int, hostId: String?, label: String) {
        when (_state.value) {
            is GuestState.Idle, is GuestState.Failed -> Unit
            else -> return
        }
        _state.value = GuestState.Connecting(label)
        GuestSessionService.start(appContext)
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                try {
                    openSession(address, port, hostId)
                } catch (e: LanRepairRequiredException) {
                    // The host forgot this phone: drop our stale pin and pair again from scratch.
                    db.knownHosts().clearCertFp(e.fingerprint)
                    openSession(address, port, hostId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    private suspend fun openSession(address: String, port: Int, hostId: String?) {
        val s = client.connect(address, port, hostId, DbPins()) { code, hostName ->
            val answer = CompletableDeferred<Boolean>()
            pairingAnswer = answer
            _state.value = GuestState.ConfirmPairing(hostName, code)
            val ok = withTimeoutOrNull(LanConstants.PAIRING_TIMEOUT_MS.toLong()) { answer.await() } ?: false
            if (ok) _state.value = GuestState.ConfirmPairing(hostName, code, accepted = true)
            ok
        }
        pairingAnswer = null
        session = s
        db.knownHosts().get(s.hostId)?.let { db.knownHosts().upsert(it.copy(lastConnectedAt = System.currentTimeMillis())) }
        _state.value = GuestState.Connected(s.hostId, s.hostName)
        scope.launch {
            val reason = s.closedReason.filterNotNull().first()
            onSessionClosed(s, reason)
        }
    }

    private fun onSessionClosed(s: LanSession, reason: String) {
        if (session !== s) return // we closed it ourselves
        session = null
        _state.value = GuestState.Failed(reason)
    }

    private fun fail(e: Throwable) {
        Log.w(TAG, "LAN connect failed", e)
        pairingAnswer = null
        _state.value = when (e) {
            is LanPairingDeclinedException -> GuestState.Idle
            is SocketTimeoutException -> GuestState.Failed("Could not reach the host. Make sure both devices are on the same Wi-Fi network.")
            is ConnectException -> GuestState.Failed("The host refused the connection. Is sharing switched on there?")
            is LanIdentityChangedException, is LanRemoteException -> GuestState.Failed(e.message ?: "Connection failed")
            else -> GuestState.Failed(e.message ?: "Connection failed")
        }
    }

    fun confirmPairing(accept: Boolean) {
        pairingAnswer?.complete(accept)
    }

    fun disconnect() {
        connectJob?.cancel()
        pairingAnswer?.complete(false)
        val s = session
        session = null
        _state.value = GuestState.Idle
        s?.close()
    }

    private inner class DbPins : PinStore {
        override suspend fun expectedFingerprint(hostId: String) = db.knownHosts().get(hostId)?.certFp
        override suspend fun findByFingerprint(fingerprint: String) =
            db.knownHosts().findByCertFp(fingerprint)?.let { PinnedHost(it.hostId, it.name) }

        override suspend fun save(hostId: String, name: String, fingerprint: String) {
            val existing = db.knownHosts().get(hostId)
            // keep a Nearby pairing key if this host was paired that way before
            db.knownHosts().upsert(KnownHost(hostId, name, existing?.secretSealed ?: "", System.currentTimeMillis(), fingerprint))
        }
    }

    // ---- file API ---------------------------------------------------------------------------------------------------

    private fun sess(): LanSession = session ?: throw IOException("Not connected")

    override suspend fun storageInfo(): StorageInfo = sess().storageInfo()
    override suspend fun list(parentId: String?): List<RemoteFile> = sess().list(parentId)
    override suspend fun createFolder(name: String, parentId: String?): RemoteFile = sess().createFolder(name, parentId)
    override suspend fun stat(fileId: String): RemoteFile = sess().stat(fileId)
    override suspend fun rename(fileId: String, newName: String): RemoteFile = sess().rename(fileId, newName)
    override suspend fun search(query: String): List<RemoteFile> = sess().search(query)
    override suspend fun delete(fileId: String) = sess().delete(fileId)
    override suspend fun cancelTransfer(transferId: String) = sess().cancelTransfer(transferId)

    private fun displayName(uri: Uri): String =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "upload-${System.currentTimeMillis()}"

    override suspend fun upload(uri: Uri, parentId: String?, name: String?): String? {
        val s = sess()
        val resolver = appContext.contentResolver
        val display = name ?: withContext(Dispatchers.IO) { displayName(uri) }
        val local = "pending-${UUID.randomUUID()}"
        var key = local
        var tid: String? = null
        var total = 0L
        var ok = false
        var error: String? = null
        var cancelled = false
        notifier.begin(local, display, 0, upload = true) // shown at once as "Preparing..." while the file is checksummed
        try {
            val (size, sha) = withContext(Dispatchers.IO) {
                digestOf { resolver.openInputStream(uri) ?: throw IOException("Cannot open file") }
            }
            if (size == 0L) throw LanRemoteException(ErrorCode.BAD_REQUEST, "Empty files cannot be uploaded")
            total = size
            val fileId = s.upload(
                display, parentId, size, sha,
                input = { resolver.openInputStream(uri) ?: throw IOException("Cannot open file") },
                onAccepted = { t ->
                    tid = t
                    key = t
                    notifier.rekey(local, t, size)
                    val now = System.currentTimeMillis()
                    db.transfers().upsert(
                        Transfer(
                            t, TransferRole.GUEST, s.hostId, s.hostName, null, parentId, display, TransferDirection.UPLOAD,
                            TransferState.SENDING, size, 0, 0, null, sha, null, now, now,
                        ),
                    )
                },
                onProgress = { bytes -> tid?.let { progress.set(it, bytes) } },
            )
            ok = true
            tid?.let { db.transfers().setState(it, TransferState.COMPLETED, size, 0, null, System.currentTimeMillis()) }
            return fileId
        } catch (e: Throwable) {
            error = e.message
            cancelled = e is CancellationException
            withContext(NonCancellable) {
                tid?.let {
                    db.transfers().setState(it, if (cancelled) TransferState.CANCELLED else TransferState.FAILED, 0, 0, e.message?.take(200), System.currentTimeMillis())
                }
            }
            throw e
        } finally {
            tid?.let { progress.clear(it) }
            notifier.end(key, ok, error, announce = !cancelled)
        }
    }

    override suspend fun download(file: RemoteFile, dest: Uri) {
        val s = sess()
        val resolver = appContext.contentResolver
        var tid: String? = null
        var done = false
        try {
            val out = resolver.openOutputStream(dest, "wt") ?: throw IOException("Cannot write destination")
            out.use {
                s.download(
                    file.fileId, it,
                    onMeta = { m ->
                        tid = m.transferId
                        notifier.begin(m.transferId, file.name, m.size, upload = false)
                        val now = System.currentTimeMillis()
                        db.transfers().upsert(
                            Transfer(
                                m.transferId, TransferRole.GUEST, s.hostId, s.hostName, file.fileId, null, file.name, TransferDirection.DOWNLOAD,
                                TransferState.SENDING, m.size, 0, 0, null, m.sha256, null, now, now,
                            ),
                        )
                    },
                    onProgress = { bytes -> tid?.let { t -> progress.set(t, bytes) } },
                )
            }
            done = true
            tid?.let { db.transfers().setState(it, TransferState.COMPLETED, file.sizeBytes, 0, null, System.currentTimeMillis()) }
        } catch (e: Throwable) {
            // never leave a damaged or partial file behind
            runCatching { if (dest.scheme == "file") File(dest.path!!).delete() else DocumentsContract.deleteDocument(resolver, dest) }
            withContext(NonCancellable) {
                tid?.let {
                    db.transfers().setState(it, if (e is CancellationException) TransferState.CANCELLED else TransferState.FAILED, 0, 0, e.message?.take(200), System.currentTimeMillis())
                }
            }
            throw e
        } finally {
            tid?.let { progress.clear(it); notifier.end(it, done, if (done) null else "interrupted", announce = !done) }
        }
    }

    private companion object {
        const val TAG = "LanGuest"
    }
}
