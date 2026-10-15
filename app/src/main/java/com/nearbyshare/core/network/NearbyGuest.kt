package com.nearbyshare.core.network

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.nearbyshare.core.common.TransferProgress
import com.nearbyshare.core.common.toHex
import com.nearbyshare.core.database.AppDatabase
import com.nearbyshare.core.database.KnownHost
import com.nearbyshare.core.database.Transfer
import com.nearbyshare.core.database.TransferDirection
import com.nearbyshare.core.database.TransferRole
import com.nearbyshare.core.database.TransferState
import com.nearbyshare.core.network.protocol.ErrorCode
import com.nearbyshare.core.network.protocol.Message
import com.nearbyshare.core.network.protocol.MsgType
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.SERVICE_ID
import com.nearbyshare.core.network.protocol.StorageInfo
import com.nearbyshare.core.security.DeviceIdentity
import com.nearbyshare.core.security.KeystoreCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface GuestState {
    data object Idle : GuestState
    data class Connecting(val hostName: String) : GuestState
    /** [accepted] = this phone confirmed the code and is now waiting for the host to approve too. */
    data class ConfirmPairing(val hostName: String, val digits: String, val accepted: Boolean = false) : GuestState
    data class Authenticating(val hostName: String) : GuestState
    data class Connected(val hostId: String, val hostName: String) : GuestState
    data class Failed(val message: String) : GuestState
}

data class DiscoveredHost(val endpointId: String, val hostId: String, val name: String)

class RemoteException(val code: String, message: String) : IOException(message)

/** Turns a Nearby ApiException into text with the status code, e.g. "8034 MISSING_PERMISSION_ACCESS_COARSE_LOCATION". */
fun Throwable.nearbyMessage(): String {
    val api = this as? com.google.android.gms.common.api.ApiException ?: return message ?: javaClass.simpleName
    return "${api.statusCode} ${com.google.android.gms.nearby.connection.ConnectionsStatusCodes.getStatusCodeString(api.statusCode)}"
}

/** Guest side: discovery, pairing handshake and the remote file API used by the UI. */
class NearbyGuest(
    context: Context,
    private val db: AppDatabase,
    private val identity: DeviceIdentity,
    private val progress: TransferProgress,
    private val notifier: com.nearbyshare.service.TransferNotifier,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)

    private val _state = MutableStateFlow<GuestState>(GuestState.Idle)
    val state: StateFlow<GuestState> = _state.asStateFlow()

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    @Volatile private var endpointId: String? = null
    @Volatile private var currentHost: DiscoveredHost? = null
    @Volatile private var knownSecret: String? = null

    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<Message>>()
    private val uploadResults = ConcurrentHashMap<String, CompletableDeferred<Message>>()
    private val uploadPayloads = ConcurrentHashMap<Long, String>() // payloadId -> transferId
    private val downloadTransfers = ConcurrentHashMap<Long, String>() // payloadId -> transferId

    private class Incoming {
        val done = CompletableDeferred<Boolean>()
        @Volatile var payload: Payload? = null
    }

    private val incoming = ConcurrentHashMap<Long, Incoming>()

    // ---- discovery & connection -------------------------------------------------------------------------------------

    fun startDiscovery() {
        if (_discovering.value) return
        _hosts.value = emptyList()
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(SERVICE_ID, discovery, options)
            .addOnSuccessListener { _discovering.value = true }
            .addOnFailureListener {
                Log.w(TAG, "startDiscovery failed", it)
                _state.value = GuestState.Failed("Could not start scanning: ${it.nearbyMessage()}")
            }
    }

    fun stopDiscovery() {
        client.stopDiscovery()
        _discovering.value = false
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            val (name, hostId) = DeviceIdentity.parseEndpointName(info.endpointName) ?: return
            _hosts.value = _hosts.value.filter { it.endpointId != id } + DiscoveredHost(id, hostId, name)
        }

        override fun onEndpointLost(id: String) {
            _hosts.value = _hosts.value.filter { it.endpointId != id }
        }
    }

    fun connect(host: DiscoveredHost) {
        if (_state.value is GuestState.Connecting || _state.value is GuestState.Connected) return
        currentHost = host
        _state.value = GuestState.Connecting(host.name)
        armWatchdog(90_000)
        com.nearbyshare.service.GuestSessionService.start(appContext)
        scope.launch {
            knownSecret = db.knownHosts().get(host.hostId)?.let { runCatching { KeystoreCipher.open(it.secretSealed) }.getOrNull() }
            client.requestConnection(identity.endpointName(hasKey = knownSecret != null), host.endpointId, lifecycle)
                .addOnFailureListener {
                    Log.w(TAG, "requestConnection failed", it)
                    _state.value = GuestState.Failed("Could not connect: ${it.nearbyMessage()}")
                }
        }
    }

    /** User compared the authentication digits with the host's screen. */
    fun confirmPairing(accept: Boolean) {
        val ep = pendingEndpoint ?: return
        if (accept) {
            client.acceptConnection(ep, payloads).addOnFailureListener {
                Log.w(TAG, "acceptConnection failed", it)
                reset(GuestState.Failed("Could not accept the connection: ${it.nearbyMessage()}"))
            }
            (_state.value as? GuestState.ConfirmPairing)?.let { _state.value = it.copy(accepted = true) }
            armWatchdog(45_000)
        } else {
            client.rejectConnection(ep)
            _state.value = GuestState.Idle
        }
    }

    @Volatile private var pendingEndpoint: String? = null

    fun disconnect() {
        (endpointId ?: pendingEndpoint)?.let { client.disconnectFromEndpoint(it) }
        reset(GuestState.Idle)
    }

    @Volatile private var watchdog: kotlinx.coroutines.Job? = null

    /** A pairing that never completes (host never accepted, radio dropped) must not leave the UI spinning forever. */
    private fun armWatchdog(ms: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            kotlinx.coroutines.delay(ms)
            val s = _state.value
            if (s is GuestState.Connecting || s is GuestState.ConfirmPairing || s is GuestState.Authenticating) {
                Log.w(TAG, "pairing timed out in state $s")
                (endpointId ?: pendingEndpoint)?.let { client.disconnectFromEndpoint(it) }
                reset(GuestState.Failed("Timed out waiting for the host. Make sure the host tapped Allow, then try again."))
            }
        }
    }

    private fun reset(newState: GuestState) {
        watchdog?.cancel()
        endpointId = null
        pendingEndpoint = null
        knownSecret = null
        val gone = IOException("Disconnected")
        pendingResponses.values.forEach { it.completeExceptionally(gone) }
        pendingResponses.clear()
        uploadResults.values.forEach { it.complete(Message.error(MsgType.UPLOAD_RESULT, "", ErrorCode.INTERNAL, "Disconnected")) }
        uploadResults.clear()
        incoming.values.forEach { it.done.complete(false) }
        incoming.clear()
        _state.value = newState
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            Log.i(TAG, "onConnectionInitiated known=${knownSecret != null}")
            pendingEndpoint = id
            if (knownSecret != null) {
                // Previously paired: no digits to compare; the host must prove itself with an HMAC instead.
                client.acceptConnection(id, payloads).addOnFailureListener {
                    reset(GuestState.Failed("Could not accept the connection: ${it.nearbyMessage()}"))
                }
            } else {
                _state.value = GuestState.ConfirmPairing(currentHost?.name ?: "host", info.authenticationDigits)
            }
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            Log.i(TAG, "onConnectionResult status=${result.status.statusCode}")
            if (!result.status.isSuccess) {
                val why = when (result.status.statusCode) {
                    com.google.android.gms.nearby.connection.ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                        "Connection rejected (8004). The host tapped Deny or its limits refused it - check Access > Security log on the host for the reason."
                    else -> "Connection failed: ${result.status.statusCode} " +
                        com.google.android.gms.nearby.connection.ConnectionsStatusCodes.getStatusCodeString(result.status.statusCode)
                }
                reset(GuestState.Failed(why))
                return
            }
            endpointId = id
            scope.launch { handshake() }
        }

        override fun onDisconnected(id: String) {
            if (id == endpointId || id == pendingEndpoint) {
                val wasConnected = _state.value is GuestState.Connected
                reset(if (wasConnected) GuestState.Failed("Connection lost") else GuestState.Idle)
            }
        }
    }

    private suspend fun handshake() {
        val host = currentHost ?: return
        _state.value = GuestState.Authenticating(host.name)
        try {
            val nonce = DeviceIdentity.randomToken(16)
            val reply = request(
                MsgType.HELLO,
                JSONObject().put("deviceId", identity.deviceId).put("name", identity.displayName).put("nonce", nonce),
            )
            if (!reply.ok) throw RemoteException(ErrorCode.UNAUTHENTICATED, "Host refused this device")
            val secret = knownSecret
            if (secret != null) {
                if (reply.type != MsgType.CHALLENGE) {
                    // Host forgot this phone: drop our stale key and pair again from scratch (shows the code dialog).
                    Log.i(TAG, "host no longer recognises us; re-pairing automatically")
                    db.knownHosts().delete(host.hostId)
                    endpointId?.let { client.disconnectFromEndpoint(it) }
                    reset(GuestState.Idle)
                    kotlinx.coroutines.delay(1_000)
                    connect(host)
                    return
                }
                val key = DeviceIdentity.keyFromToken(secret)
                val nonceH = reply.body.getString("nonce")
                val expected = DeviceIdentity.hmac(key, "host", nonce, nonceH)
                if (!DeviceIdentity.constantTimeEquals(expected, reply.body.optString("proof"))) {
                    throw RemoteException(ErrorCode.UNAUTHENTICATED, "Could not verify the host's identity")
                }
                val result = request(MsgType.AUTH, JSONObject().put("proof", DeviceIdentity.hmac(key, "guest", nonceH, nonce)))
                if (!result.ok) throw RemoteException(ErrorCode.UNAUTHENTICATED, "Authentication failed")
                db.knownHosts().upsert(KnownHost(host.hostId, host.name, KeystoreCipher.seal(secret), System.currentTimeMillis()))
            } else {
                if (reply.type == MsgType.CHALLENGE) {
                    throw RemoteException(
                        ErrorCode.UNAUTHENTICATED,
                        "The host already has this phone paired, but this phone lost its key. On the host open Access, tap Re-pair for this device, then connect again.",
                    )
                }
                if (reply.type != MsgType.AUTH_RESULT || !reply.body.optBoolean("paired")) {
                    throw RemoteException(ErrorCode.UNAUTHENTICATED, "Pairing failed")
                }
                val newSecret = reply.body.getString("secret")
                db.knownHosts().upsert(KnownHost(host.hostId, host.name, KeystoreCipher.seal(newSecret), System.currentTimeMillis()))
            }
            watchdog?.cancel()
            Log.i(TAG, "handshake complete")
            _state.value = GuestState.Connected(host.hostId, host.name)
        } catch (e: Exception) {
            Log.w(TAG, "handshake failed", e)
            endpointId?.let { client.disconnectFromEndpoint(it) }
            reset(GuestState.Failed(e.message ?: "Authentication failed"))
        }
    }

    // ---- payload handling ---------------------------------------------------------------------------------------------

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val msg = payload.asBytes()?.let { Message.decode(it) } ?: return
                    when {
                        msg.type == MsgType.REVOKED -> {
                            endpointId?.let { client.disconnectFromEndpoint(it) }
                            reset(GuestState.Failed("The host revoked your access"))
                        }
                        msg.type == MsgType.UPLOAD_RESULT -> uploadResults[msg.id]?.complete(msg)
                        msg.isResponse -> pendingResponses[msg.id]?.complete(msg)
                    }
                }
                // Only accepted for a download we asked for (payload id announced in the DOWNLOAD_BEGIN reply).
                Payload.Type.FILE -> incoming.computeIfAbsent(payload.id) { Incoming() }.payload = payload
                else -> client.cancelPayload(payload.id)
            }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            uploadPayloads[update.payloadId]?.let { progress.set(it, update.bytesTransferred) }
            downloadTransfers[update.payloadId]?.let { progress.set(it, update.bytesTransferred) }

            val failed = update.status == PayloadTransferUpdate.Status.FAILURE || update.status == PayloadTransferUpdate.Status.CANCELED
            incoming[update.payloadId]?.let { inc ->
                if (update.status == PayloadTransferUpdate.Status.SUCCESS) inc.done.complete(true) else if (failed) inc.done.complete(false)
            }
            if (failed) {
                uploadPayloads[update.payloadId]?.let { tid ->
                    uploadResults[tid]?.complete(Message.error(MsgType.UPLOAD_RESULT, tid, ErrorCode.INTERNAL, "Transfer interrupted"))
                }
            }
        }
    }

    // ---- request/response ------------------------------------------------------------------------------------------------

    private suspend fun request(type: String, body: JSONObject, timeoutMs: Long = 30_000): Message {
        val ep = endpointId ?: throw IOException("Not connected")
        val id = UUID.randomUUID().toString().take(16)
        val waiter = CompletableDeferred<Message>()
        pendingResponses[id] = waiter
        try {
            client.sendPayload(ep, Payload.fromBytes(Message(type, id, false, true, body).encode())).await()
            return withTimeout(timeoutMs) { waiter.await() }
        } finally {
            pendingResponses.remove(id)
        }
    }

    private suspend fun call(type: String, body: JSONObject = JSONObject()): JSONObject {
        val r = request(type, body)
        if (!r.ok) {
            val (code, msg) = r.error()
            throw RemoteException(code, msg.ifBlank { code })
        }
        return r.body
    }

    // ---- remote file API ---------------------------------------------------------------------------------------------------

    suspend fun storageInfo(): StorageInfo = StorageInfo.fromJson(call(MsgType.STORAGE_INFO))

    suspend fun list(parentId: String?): List<RemoteFile> =
        RemoteFile.listFromJson(call(MsgType.LIST_DIRECTORY, JSONObject().put("parentId", parentId ?: "")).getJSONArray("items"))

    suspend fun createFolder(name: String, parentId: String?): RemoteFile =
        RemoteFile.fromJson(call(MsgType.MKDIR, JSONObject().put("name", name).put("parentId", parentId ?: "")))

    /** Details of one item (including its parent folder). */
    suspend fun stat(fileId: String): RemoteFile = RemoteFile.fromJson(call(MsgType.STAT, JSONObject().put("fileId", fileId)))

    suspend fun rename(fileId: String, newName: String): RemoteFile =
        RemoteFile.fromJson(call(MsgType.RENAME, JSONObject().put("fileId", fileId).put("name", newName)))

    suspend fun search(query: String): List<RemoteFile> =
        RemoteFile.listFromJson(call(MsgType.SEARCH, JSONObject().put("q", query)).getJSONArray("items"))

    /** Id of the host this phone is connected and authenticated to right now, or null. */
    val connectedHostId: String? get() = (_state.value as? GuestState.Connected)?.hostId

    suspend fun delete(fileId: String) {
        call(MsgType.DELETE_FILE, JSONObject().put("fileId", fileId))
    }

    suspend fun cancelTransfer(transferId: String) {
        uploadPayloads.entries.firstOrNull { it.value == transferId }?.let { client.cancelPayload(it.key) }
        downloadTransfers.entries.firstOrNull { it.value == transferId }?.let { client.cancelPayload(it.key) }
        runCatching { call(MsgType.TRANSFER_CANCEL, JSONObject().put("transferId", transferId)) }
    }

    private fun displayName(uri: Uri): String =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "upload-${System.currentTimeMillis()}"

    /** Uploads [uri] to the host, showing a 0-100% notification (and keeping the process alive) until it finishes. */
    suspend fun upload(uri: Uri, parentId: String?, name: String? = null): String? {
        val local = "pending-${UUID.randomUUID()}"
        var key = local
        var ok = false
        var error: String? = null
        var cancelled = false
        var newFileId: String? = null
        // Starts before the file is checksummed so the notification appears immediately ("Preparing...").
        notifier.begin(local, name ?: withContext(Dispatchers.IO) { displayName(uri) }, 0, upload = true)
        try {
            newFileId = uploadInternal(uri, parentId, name) { tid, size ->
                notifier.rekey(local, tid, size)
                key = tid
            }
            ok = true
        } catch (e: Throwable) {
            error = e.message
            cancelled = e is kotlinx.coroutines.CancellationException
            throw e
        } finally {
            notifier.end(key, ok, error, announce = !cancelled)
        }
        return newFileId
    }

    /** Returns the host's id for the stored file. [nameOverride] is for sources without a display name (plain file:// uris). */
    private suspend fun uploadInternal(uri: Uri, parentId: String?, nameOverride: String?, onAccepted: (transferId: String, size: Long) -> Unit): String? = withContext(Dispatchers.IO) {
        val ep = endpointId ?: throw IOException("Not connected")
        val host = currentHost ?: throw IOException("Not connected")
        val resolver = appContext.contentResolver
        val name = nameOverride ?: displayName(uri)

        // Hash first (streamed), so the host can verify integrity after receiving the file.
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        (resolver.openInputStream(uri) ?: throw IOException("Cannot open file")).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                size += n
            }
        }
        if (size == 0L) throw RemoteException(ErrorCode.BAD_REQUEST, "Empty files cannot be uploaded")
        val sha = digest.digest().toHex()

        val begin = call(
            MsgType.UPLOAD_BEGIN,
            JSONObject().put("name", name).put("size", size).put("sha256", sha).put("parentId", parentId ?: ""),
        )
        val tid = begin.getString("transferId")
        onAccepted(tid, size)
        val now = System.currentTimeMillis()
        db.transfers().upsert(
            Transfer(
                tid, TransferRole.GUEST, host.hostId, host.name, null, parentId, name, TransferDirection.UPLOAD,
                TransferState.SENDING, size, 0, 0, null, sha, null, now, now,
            ),
        )

        val result = CompletableDeferred<Message>()
        uploadResults[tid] = result
        var pfd: ParcelFileDescriptor? = null
        var newFileId: String? = null
        try {
            pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
            if (pfd.statSize >= 0 && pfd.statSize != size) throw IOException("File changed while uploading")
            val payload = Payload.fromFile(pfd)
            uploadPayloads[payload.id] = tid
            client.sendPayload(ep, payload).await()
            val res = result.await()
            if (!res.ok) {
                val (code, msg) = res.error()
                throw RemoteException(code, msg.ifBlank { "Upload failed" })
            }
            newFileId = res.body.optString("fileId").ifEmpty { null }
            db.transfers().setState(tid, TransferState.COMPLETED, size, 0, null, System.currentTimeMillis())
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                val st = if (e is kotlinx.coroutines.CancellationException) TransferState.CANCELLED else TransferState.FAILED
                db.transfers().setState(tid, st, 0, 0, e.message?.take(200), System.currentTimeMillis())
            }
            throw e
        } finally {
            uploadResults.remove(tid)
            uploadPayloads.values.remove(tid)
            progress.clear(tid)
            runCatching { pfd?.close() }
        }
        newFileId
    }

    suspend fun download(file: RemoteFile, dest: Uri): Unit = withContext(Dispatchers.IO) {
        val host = currentHost ?: throw IOException("Not connected")
        val resolver = appContext.contentResolver
        val begin = call(MsgType.DOWNLOAD_BEGIN, JSONObject().put("fileId", file.fileId))
        val tid = begin.getString("transferId")
        val payloadId = begin.getLong("payloadId")
        val size = begin.getLong("size")
        val expectedSha = begin.getString("sha256")
        val now = System.currentTimeMillis()
        db.transfers().upsert(
            Transfer(
                tid, TransferRole.GUEST, host.hostId, host.name, file.fileId, null, file.name, TransferDirection.DOWNLOAD,
                TransferState.SENDING, size, 0, 0, null, expectedSha, null, now, now,
            ),
        )
        val inc = incoming.computeIfAbsent(payloadId) { Incoming() }
        downloadTransfers[payloadId] = tid
        var javaFile: java.io.File? = null
        var downloaded = false
        notifier.begin(tid, file.name, size, upload = false)
        try {
            if (!inc.done.await()) throw IOException("Download interrupted")
            val nearbyFile = inc.payload?.asFile() ?: throw IOException("No data received")
            javaFile = nearbyFile.asJavaFile()
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            ParcelFileDescriptor.AutoCloseInputStream(nearbyFile.asParcelFileDescriptor()).use { input ->
                (resolver.openOutputStream(dest, "wt") ?: throw IOException("Cannot write destination")).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        copied += n
                    }
                }
            }
            if (copied != size || !digest.digest().toHex().equals(expectedSha, ignoreCase = true)) {
                runCatching { DocumentsContract.deleteDocument(resolver, dest) }
                throw RemoteException(ErrorCode.INTEGRITY, "Checksum mismatch - file discarded")
            }
            downloaded = true
            db.transfers().setState(tid, TransferState.COMPLETED, size, 0, null, System.currentTimeMillis())
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                val st = if (e is kotlinx.coroutines.CancellationException) TransferState.CANCELLED else TransferState.FAILED
                db.transfers().setState(tid, st, 0, 0, e.message?.take(200), System.currentTimeMillis())
            }
            throw e
        } finally {
            incoming.remove(payloadId)
            downloadTransfers.remove(payloadId)
            notifier.end(tid, downloaded, if (downloaded) null else "interrupted", announce = !downloaded)
            runCatching { javaFile?.delete() }
        }
    }

    companion object {
        private const val TAG = "NearbyGuest"
    }
}
