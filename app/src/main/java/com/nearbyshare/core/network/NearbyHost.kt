package com.nearbyshare.core.network

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.nearbyshare.core.common.TransferProgress
import com.nearbyshare.core.database.AccessMode
import com.nearbyshare.core.database.AppDatabase
import com.nearbyshare.core.database.Device
import com.nearbyshare.core.database.DeviceAccess
import com.nearbyshare.core.database.HostConfig
import com.nearbyshare.core.database.TransferState
import com.nearbyshare.core.database.TrustState
import com.nearbyshare.core.network.protocol.ErrorCode
import com.nearbyshare.core.network.protocol.MAX_CONTROL_BYTES
import com.nearbyshare.core.network.protocol.Message
import com.nearbyshare.core.network.protocol.MsgType
import com.nearbyshare.core.network.protocol.OpException
import com.nearbyshare.core.network.protocol.SERVICE_ID
import com.nearbyshare.core.security.DeviceIdentity
import com.nearbyshare.core.security.KeystoreCipher
import com.nearbyshare.core.security.RateLimiter
import com.nearbyshare.domain.HostFileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class PendingApproval(val endpointId: String, val deviceId: String, val name: String, val digits: String, val rePair: Boolean = false)
data class GuestSession(val endpointId: String, val deviceId: String, val name: String, val authenticated: Boolean)

/**
 * Host side of the protocol. Owns advertising, connection approval, the HMAC handshake and request dispatch.
 * Lives for the whole process; StorageHostService just starts/stops it and keeps the process foregrounded.
 */
class NearbyHost(
    private val context: Context,
    private val db: AppDatabase,
    private val files: HostFileService,
    private val identity: DeviceIdentity,
    private val progress: TransferProgress,
    private val scope: CoroutineScope,
) {
    private val client = Nearby.getConnectionsClient(context)

    private class Session(val endpointId: String, val deviceId: String, val name: String) {
        @Volatile var authenticated = false
        @Volatile var issuedSecret: String? = null // set only for a first-time pairing confirmed by the host user
        var nonceG: String? = null
        var nonceH: String? = null
        val limiter = RateLimiter()
        @Volatile var upload: UploadCtx? = null
        val outgoing = ConcurrentHashMap<Long, String>() // payloadId -> transferId
        val incoming = ConcurrentHashMap<Long, Payload>()
    }

    private class UploadCtx(val transferId: String, val size: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val pendingInfo = ConcurrentHashMap<String, PendingApproval>()

    private val _guests = MutableStateFlow<List<GuestSession>>(emptyList())
    val guests: StateFlow<List<GuestSession>> = _guests.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pending: StateFlow<List<PendingApproval>> = _pending.asStateFlow()

    private val _advertising = MutableStateFlow(false)
    val advertising: StateFlow<Boolean> = _advertising.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun publish() {
        _guests.value = sessions.values.map { GuestSession(it.endpointId, it.deviceId, it.name, it.authenticated) }
        _pending.value = pendingInfo.values.toList()
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------

    suspend fun start() {
        if (_advertising.value) return
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        try {
            client.startAdvertising(identity.endpointName(), SERVICE_ID, lifecycle, options).await()
            _advertising.value = true
            _error.value = null
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertising failed", e)
            _error.value = "Could not start advertising: ${e.nearbyMessage()}"
            throw e
        }
    }

    fun stop() {
        val deviceIds = sessions.values.map { it.deviceId }
        client.stopAdvertising()
        client.stopAllEndpoints()
        sessions.clear()
        pendingInfo.clear()
        _advertising.value = false
        publish()
        scope.launch { deviceIds.forEach { files.abortActiveTransfers(it) } }
    }

    /** Host user tapped Approve/Deny on the pairing dialog. */
    fun resolvePairing(endpointId: String, approve: Boolean) {
        val p = pendingInfo.remove(endpointId) ?: return
        publish()
        if (!approve) {
            client.rejectConnection(endpointId)
            scope.launch { files.audit(p.deviceId, "PAIR", "DENIED") }
            return
        }
        scope.launch { admit(p.endpointId, p.deviceId, p.name, brandNew = true) }
    }

    /** Called when the host revokes/blocks a device: drop live sessions immediately. */
    fun disconnectDevice(deviceId: String) {
        sessions.values.filter { it.deviceId == deviceId }.forEach { dropSession(it.endpointId, notify = true) }
    }

    private fun dropSession(endpointId: String, notify: Boolean = false) {
        val s = sessions.remove(endpointId)
        if (s != null) {
            if (notify) send(endpointId, Message(MsgType.REVOKED, "0"))
            scope.launch { files.abortActiveTransfers(s.deviceId) }
        }
        client.disconnectFromEndpoint(endpointId)
        publish()
    }

    // ---- connection lifecycle ------------------------------------------------------------------------------------

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            scope.launch {
                val parsed = DeviceIdentity.parseEndpointName(info.endpointName)
                val cfg = db.hostConfig().get()
                val live = sessions.values.count { it.authenticated } + pendingInfo.size
                val refusal = when {
                    parsed == null -> "unrecognised device name"
                    cfg == null || !cfg.sharingEnabled -> "sharing is off"
                    live >= cfg.maxGuests -> "guest limit (${cfg.maxGuests}) reached"
                    else -> null
                }
                if (refusal != null || parsed == null || cfg == null) {
                    Log.w(TAG, "rejecting connection: $refusal")
                    client.rejectConnection(endpointId)
                    files.audit(parsed?.second, "CONNECT", "REJECTED: $refusal")
                    return@launch
                }
                val (name, deviceId) = parsed
                // A reconnecting device replaces its older session.
                sessions.values.filter { it.deviceId == deviceId }.forEach { dropSession(it.endpointId) }
                val known = db.devices().get(deviceId)
                when {
                    known?.trustState == TrustState.BLOCKED -> {
                        client.rejectConnection(endpointId)
                        files.audit(deviceId, "CONNECT", "BLOCKED")
                    }
                    // Known device that still holds its key: accept; it must still prove possession (HMAC) before any request.
                    known?.trustState == TrustState.APPROVED && known.secretSealed != null && DeviceIdentity.hasKeyFlag(info.endpointName) ->
                        admit(endpointId, deviceId, name, brandNew = false)
                    known == null && cfg.accessMode == AccessMode.PRIVATE -> {
                        client.rejectConnection(endpointId)
                        files.audit(deviceId, "CONNECT", "REJECTED: host is Private and this device is not approved")
                    }
                    known == null && cfg.accessMode == AccessMode.OPEN -> admit(endpointId, deviceId, name, brandNew = true, cfg = cfg)
                    // New device, or a known device that lost its key: the host confirms the code on screen (automatic re-pair).
                    // Re-pairing never auto-approves, even in Open mode, because it inherits the device's existing files.
                    else -> {
                        pendingInfo[endpointId] = PendingApproval(endpointId, deviceId, name, info.authenticationDigits, rePair = known != null)
                        publish()
                    }
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            Log.i(TAG, "onConnectionResult status=${result.status.statusCode}")
            if (!result.status.isSuccess) {
                _error.value = "Connection failed: ${result.status.statusCode}"
                sessions.remove(endpointId)
                pendingInfo.remove(endpointId)
                publish()
                return
            }
            publish()
            // Anything that has not completed the handshake shortly after connecting is dropped.
            scope.launch {
                delay(HANDSHAKE_TIMEOUT_MS)
                if (sessions[endpointId]?.authenticated == false) dropSession(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            pendingInfo.remove(endpointId)
            val s = sessions.remove(endpointId)
            if (s != null) scope.launch { files.abortActiveTransfers(s.deviceId) }
            publish()
        }
    }

    private suspend fun admit(endpointId: String, deviceId: String, name: String, brandNew: Boolean, cfg: HostConfig? = null) {
        val session = Session(endpointId, deviceId, name)
        if (brandNew) {
            val c = cfg ?: db.hostConfig().get() ?: return
            val secret = DeviceIdentity.randomToken()
            val now = System.currentTimeMillis()
            val sealed = KeystoreCipher.seal(secret)
            if (db.devices().get(deviceId) != null) {
                // Re-pairing: keep the device's files and permissions, only replace the key.
                db.devices().updatePairing(deviceId, name, sealed, now)
            } else {
                db.devices().upsert(Device(deviceId, name, sealed, TrustState.APPROVED, now, now))
                db.access().upsert(
                    DeviceAccess(
                        deviceId, c.defaultCanRead, c.defaultCanWrite, c.defaultCanOverwrite, c.defaultCanDelete, c.defaultCanCreateFolders,
                    ),
                )
            }
            session.issuedSecret = secret
            files.audit(deviceId, "PAIR", "APPROVED")
        }
        sessions[endpointId] = session
        publish()
        client.acceptConnection(endpointId, payloads).addOnFailureListener {
            Log.w(TAG, "acceptConnection failed", it)
            _error.value = "Could not accept ${name}: ${it.nearbyMessage()}"
            sessions.remove(endpointId)
            publish()
        }
    }

    // ---- payloads --------------------------------------------------------------------------------------------------

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val s = sessions[endpointId] ?: return
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    if (bytes.size > MAX_CONTROL_BYTES) return dropSession(endpointId)
                    val msg = Message.decode(bytes)
                    if (msg == null) {
                        send(endpointId, Message.error("ERROR", "0", ErrorCode.BAD_REQUEST, "Malformed message"))
                        return
                    }
                    scope.launch { handle(s, msg) }
                }
                Payload.Type.FILE -> {
                    // Only an authenticated guest with an open, quota-reserved upload may send file data.
                    if (!s.authenticated || s.upload == null || s.incoming.isNotEmpty()) {
                        client.cancelPayload(payload.id)
                        return
                    }
                    s.incoming[payload.id] = payload
                }
                else -> client.cancelPayload(payload.id)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val s = sessions[endpointId] ?: return
            val outgoingTransfer = s.outgoing[update.payloadId]
            if (outgoingTransfer != null) {
                progress.set(outgoingTransfer, update.bytesTransferred)
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> finishOutgoing(s, update.payloadId, outgoingTransfer, true, update.bytesTransferred)
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED ->
                        finishOutgoing(s, update.payloadId, outgoingTransfer, false, update.bytesTransferred)
                }
                return
            }
            val payload = s.incoming[update.payloadId] ?: return
            val ctx = s.upload ?: return
            progress.set(ctx.transferId, update.bytesTransferred)
            // Enforce the reserved size as bytes arrive: a guest cannot declare 100 MB and stream 20 GB.
            if (update.totalBytes != ctx.size || update.bytesTransferred > ctx.size) {
                client.cancelPayload(update.payloadId)
                abortUpload(s, "Size does not match the reservation", TransferState.FAILED)
                return
            }
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> scope.launch { completeUpload(s, ctx, payload) }
                PayloadTransferUpdate.Status.FAILURE -> abortUpload(s, "Transfer failed", TransferState.FAILED)
                PayloadTransferUpdate.Status.CANCELED -> abortUpload(s, "Transfer cancelled", TransferState.CANCELLED)
            }
        }
    }

    private fun finishOutgoing(s: Session, payloadId: Long, transferId: String, ok: Boolean, bytes: Long) {
        s.outgoing.remove(payloadId)
        progress.clear(transferId)
        scope.launch { files.finishTransfer(transferId, ok, bytes, if (ok) null else "Transfer failed") }
    }

    private fun abortUpload(s: Session, reason: String, state: TransferState) {
        val ctx = s.upload ?: return
        s.upload = null
        s.incoming.keys.toList().forEach { client.cancelPayload(it) }
        s.incoming.clear()
        progress.clear(ctx.transferId)
        scope.launch {
            files.failTransfer(ctx.transferId, state, reason)
            send(s.endpointId, Message.error(MsgType.UPLOAD_RESULT, ctx.transferId, ErrorCode.INTEGRITY, reason))
        }
    }

    private suspend fun completeUpload(s: Session, ctx: UploadCtx, payload: Payload) {
        s.upload = null
        s.incoming.clear()
        val file = payload.asFile()
        try {
            val pfd: ParcelFileDescriptor = file?.asParcelFileDescriptor() ?: throw OpException(ErrorCode.INTERNAL, "No data received")
            val saved = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { files.receiveUpload(s.deviceId, ctx.transferId, it) }
            send(s.endpointId, Message(MsgType.UPLOAD_RESULT, ctx.transferId, true, true, JSONObject().put("fileId", saved.fileId)))
        } catch (e: OpException) {
            send(s.endpointId, Message.error(MsgType.UPLOAD_RESULT, ctx.transferId, e.code, e.message ?: ""))
        } catch (e: Exception) {
            Log.w(TAG, "upload failed", e)
            send(s.endpointId, Message.error(MsgType.UPLOAD_RESULT, ctx.transferId, ErrorCode.INTERNAL, "Upload failed"))
        } finally {
            // On Android 8-9 Nearby writes received files to a public folder; never leave guest data there.
            runCatching { file?.asJavaFile()?.delete() }
            progress.clear(ctx.transferId)
        }
    }

    // ---- request dispatch --------------------------------------------------------------------------------------------

    private fun send(endpointId: String, msg: Message) {
        runCatching { client.sendPayload(endpointId, Payload.fromBytes(msg.encode())) }
    }

    private suspend fun handle(s: Session, msg: Message) {
        if (!s.limiter.tryAcquire()) {
            send(s.endpointId, Message.error(msg.type, msg.id, ErrorCode.RATE_LIMITED, "Too many requests"))
            return
        }
        try {
            when (msg.type) {
                MsgType.HELLO -> return hello(s, msg)
                MsgType.AUTH -> return auth(s, msg)
            }
            if (!s.authenticated) throw OpException(ErrorCode.UNAUTHENTICATED, "Authenticate first")
            dispatch(s, msg)?.let { send(s.endpointId, Message(msg.type, msg.id, true, true, it)) }
        } catch (e: OpException) {
            files.audit(s.deviceId, msg.type, "DENIED:${e.code}")
            send(s.endpointId, Message.error(msg.type, msg.id, e.code, e.message ?: ""))
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${msg.type}", e)
            send(s.endpointId, Message.error(msg.type, msg.id, ErrorCode.INTERNAL, "Request failed"))
        }
    }

    /** Returns the response body, or null when the handler already sent its own reply. */
    private suspend fun dispatch(s: Session, msg: Message): JSONObject? {
        val b = msg.body
        return when (msg.type) {
            MsgType.STORAGE_INFO -> files.storageInfo(s.deviceId).toJson()
            MsgType.LIST_DIRECTORY -> {
                val items = files.list(s.deviceId, b.optString("parentId").ifEmpty { null })
                JSONObject().put("items", JSONArray(items.map { it.toJson() }))
            }
            MsgType.STAT -> files.stat(s.deviceId, b.getString("fileId")).toJson()
            MsgType.RENAME -> files.rename(s.deviceId, b.getString("fileId"), b.getString("name")).toJson()
            MsgType.SEARCH -> JSONObject().put("items", JSONArray(files.search(s.deviceId, b.getString("q")).map { it.toJson() }))
            MsgType.MKDIR -> files.createFolder(s.deviceId, b.getString("name"), b.optString("parentId").ifEmpty { null }).toJson()
            MsgType.DELETE_FILE -> JSONObject().also { files.delete(s.deviceId, b.getString("fileId")) }
            MsgType.UPLOAD_BEGIN -> {
                if (s.upload != null) throw OpException(ErrorCode.BUSY, "Another upload is in progress")
                val size = b.getLong("size")
                val tid = files.beginUpload(
                    s.deviceId, b.getString("name"), size, b.getString("sha256"), b.optString("parentId").ifEmpty { null }, s.name,
                )
                s.upload = UploadCtx(tid, size)
                JSONObject().put("transferId", tid)
            }
            MsgType.DOWNLOAD_BEGIN -> {
                val t = files.beginDownload(s.deviceId, b.getString("fileId"), s.name)
                val payload = if (t.file != null) {
                    Payload.fromFile(t.file)
                } else {
                    // Visible-folder file: hand Nearby a descriptor opened through the document provider.
                    val pfd = context.contentResolver.openFileDescriptor(t.uri!!, "r")
                        ?: throw OpException(ErrorCode.NOT_FOUND, "This file was changed or removed on the host")
                    Payload.fromFile(pfd)
                }
                s.outgoing[payload.id] = t.transferId
                // Reply first so the guest can bind the incoming FILE payload id, then stream the file.
                send(
                    s.endpointId,
                    Message(
                        msg.type, msg.id, true, true,
                        JSONObject().put("transferId", t.transferId).put("payloadId", payload.id).put("size", t.sizeBytes)
                            .put("sha256", t.sha256).put("name", t.name),
                    ),
                )
                client.sendPayload(s.endpointId, payload)
                null
            }
            MsgType.TRANSFER_CANCEL -> {
                val tid = b.getString("transferId")
                if (s.upload?.transferId == tid) abortUpload(s, "Cancelled by guest", TransferState.CANCELLED)
                s.outgoing.entries.firstOrNull { it.value == tid }?.let {
                    client.cancelPayload(it.key)
                }
                JSONObject()
            }
            else -> throw OpException(ErrorCode.BAD_REQUEST, "Unknown request")
        }
    }

    // ---- handshake ------------------------------------------------------------------------------------------------------

    private suspend fun hello(s: Session, msg: Message) {
        Log.i(TAG, "HELLO received, freshPairing=${s.issuedSecret != null}")
        val claimed = msg.body.optString("deviceId")
        if (claimed != s.deviceId) return failAuth(s, msg, "Identity mismatch")
        val fresh = s.issuedSecret
        if (fresh != null) {
            // First pairing: digits were compared by both users, so the secret can travel over this session.
            s.issuedSecret = null
            s.authenticated = true
            db.devices().touch(s.deviceId, System.currentTimeMillis())
            publish()
            send(
                s.endpointId,
                Message(
                    MsgType.AUTH_RESULT, msg.id, true, true,
                    JSONObject().put("paired", true).put("secret", fresh).put("hostId", identity.deviceId).put("hostName", identity.displayName),
                ),
            )
            return
        }
        val device = db.devices().get(s.deviceId)
        val sealed = device?.secretSealed
        if (device == null || device.trustState != TrustState.APPROVED || sealed == null) return failAuth(s, msg, "Not paired")
        val nonceG = msg.body.optString("nonce")
        if (nonceG.length !in 16..64) return failAuth(s, msg, "Bad nonce")
        val nonceH = DeviceIdentity.randomToken(16)
        s.nonceG = nonceG
        s.nonceH = nonceH
        val key = DeviceIdentity.keyFromToken(KeystoreCipher.open(sealed))
        send(
            s.endpointId,
            Message(
                MsgType.CHALLENGE, msg.id, true, true,
                JSONObject().put("nonce", nonceH).put("proof", DeviceIdentity.hmac(key, "host", nonceG, nonceH)),
            ),
        )
    }

    private suspend fun auth(s: Session, msg: Message) {
        val nG = s.nonceG
        val nH = s.nonceH
        val sealed = db.devices().get(s.deviceId)?.secretSealed
        if (nG == null || nH == null || sealed == null) return failAuth(s, msg, "No challenge")
        val key = DeviceIdentity.keyFromToken(KeystoreCipher.open(sealed))
        val expected = DeviceIdentity.hmac(key, "guest", nH, nG)
        if (!DeviceIdentity.constantTimeEquals(expected, msg.body.optString("proof"))) return failAuth(s, msg, "Bad proof")
        s.nonceG = null
        s.nonceH = null
        s.authenticated = true
        db.devices().touch(s.deviceId, System.currentTimeMillis())
        publish()
        send(s.endpointId, Message(MsgType.AUTH_RESULT, msg.id, true, true, JSONObject().put("paired", false)))
    }

    private suspend fun failAuth(s: Session, msg: Message, reason: String) {
        files.audit(s.deviceId, "AUTH", "FAILED:$reason")
        send(s.endpointId, Message.error(MsgType.AUTH_RESULT, msg.id, ErrorCode.UNAUTHENTICATED, "Authentication failed"))
        dropSession(s.endpointId)
    }

    companion object {
        private const val TAG = "NearbyHost"
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
    }
}
