package com.nearbyshare.lan

import com.nearbyshare.core.network.protocol.ErrorCode
import com.nearbyshare.core.network.protocol.Message
import com.nearbyshare.core.network.protocol.MsgType
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.channels.Channel

/** A host this device has paired with before. */
class PinnedHost(val hostId: String, val name: String)

/** Where a guest remembers the hosts it paired with, so later connections need no code. */
interface PinStore {
    /** Fingerprint pinned for a host id, or null if never paired. */
    suspend fun expectedFingerprint(hostId: String): String?
    suspend fun findByFingerprint(fingerprint: String): PinnedHost?
    suspend fun save(hostId: String, name: String, fingerprint: String)
}

/** The server error that carries a protocol error code. */
class LanRemoteException(val code: String, message: String) : IOException(message)

/** A host we paired with earlier now presents a different certificate: refuse, it may be an impostor. */
class LanIdentityChangedException(val hostName: String) :
    IOException("The identity of $hostName has changed. If you did not expect this, someone may be impersonating it.")

/** The host no longer knows this device (it was removed there); pair again. */
class LanRepairRequiredException(val fingerprint: String) : IOException("The host no longer recognises this device. Pair again.")

/** The person on this device declined the pairing code. */
class LanPairingDeclinedException : IOException("Pairing was cancelled")

class DownloadMeta(val transferId: String, val name: String, val size: Long, val sha256: String)

class LanGuestClient(
    private val identity: LanIdentity,
    private val deviceId: String,
    private val deviceName: () -> String,
    private val scope: CoroutineScope,
) {
    /**
     * Connects, authenticates and (the first time) pairs. [onSas] is asked to show the 6-digit code and return whether the
     * user confirmed that it matches the host's screen. [expectedHostId] (from discovery) lets a changed host identity be
     * caught before anything is sent.
     */
    suspend fun connect(
        address: String,
        port: Int,
        expectedHostId: String?,
        pins: PinStore,
        onSas: suspend (code: String, hostName: String) -> Boolean,
    ): LanSession = withContext(Dispatchers.IO) {
        val socket = LanTls.context(identity).socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(address, port), LanConstants.CONNECT_TIMEOUT_MS)
            socket.enabledProtocols = LanTls.modernProtocols(socket.supportedProtocols)
            socket.soTimeout = LanConstants.HANDSHAKE_TIMEOUT_MS
            socket.keepAlive = true
            socket.startHandshake()

            val hostFingerprint = LanTls.peerFingerprint(socket)
            val pinnedForId = expectedHostId?.let { pins.expectedFingerprint(it) }
            if (pinnedForId != null && pinnedForId != hostFingerprint) throw LanIdentityChangedException("this host")
            val known = pins.findByFingerprint(hostFingerprint)

            val io = FrameIO(socket.inputStream, socket.outputStream)
            val guestNonce = randomHex(16)
            val hello = JSONObject().put("deviceId", deviceId).put("name", deviceName())
            if (known == null) hello.put("commit", LanPairing.commitment(guestNonce))
            val helloId = newId()
            io.writeControl(Message(MsgType.HELLO, helloId, false, true, hello))

            var reply = readControl(io)
            if (reply.type == MsgType.PAIR_CHALLENGE) {
                if (known != null) throw LanRepairRequiredException(hostFingerprint) // we sent no commitment: pairing cannot proceed
                val hostNonce = reply.body.getString("nonce")
                val hostName = sanitizeDeviceName(reply.body.optString("hostName"))
                // Reveal straight away so the host can show the same code at the same time.
                io.writeControl(Message(MsgType.PAIR_REVEAL, newId(), false, true, JSONObject().put("nonce", guestNonce)))
                val code = LanPairing.sas(identity.fingerprint, hostFingerprint, guestNonce, hostNonce)
                socket.soTimeout = LanConstants.PAIRING_TIMEOUT_MS
                val accepted = onSas(code, hostName)
                io.writeControl(Message(MsgType.PAIR_CONFIRM, newId(), false, true, JSONObject().put("accept", accepted)))
                if (!accepted) throw LanPairingDeclinedException()
                reply = readControl(io)
            }
            if (reply.type != MsgType.AUTH_RESULT) throw IOException("Unexpected reply from host")
            if (!reply.ok) {
                val (code, msg) = reply.error()
                throw LanRemoteException(code, msg.ifBlank { "Host refused the connection" })
            }
            val hostId = reply.body.getString("hostId")
            val hostName = sanitizeDeviceName(reply.body.optString("hostName"))
            if (!isUuid(hostId)) throw IOException("Bad host id")
            if (known != null && known.hostId != hostId) throw LanIdentityChangedException(known.name)
            if (known == null) pins.save(hostId, hostName, hostFingerprint)

            socket.soTimeout = 0 // reads block until data or close; the ping below detects a dead peer
            LanSession(hostId, hostName, socket, io, scope)
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun readControl(io: FrameIO): Message {
        val frame = io.readFrame() ?: throw IOException("Host closed the connection")
        return (frame as? Frame.Control)?.message ?: throw IOException("Unexpected data from host")
    }
}

private fun newId() = UUID.randomUUID().toString().take(16)

/** An authenticated, encrypted connection to one host. */
class LanSession internal constructor(
    val hostId: String,
    val hostName: String,
    private val socket: SSLSocket,
    private val io: FrameIO,
    private val scope: CoroutineScope,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Message>>()
    private val uploadResults = ConcurrentHashMap<String, CompletableDeferred<Message>>()
    private val streams = ConcurrentHashMap<Int, Channel<ByteArray>>()

    private val _closed = MutableStateFlow<String?>(null)

    /** Null while connected; the reason once the connection has ended. */
    val closedReason: StateFlow<String?> = _closed.asStateFlow()
    val isOpen: Boolean get() = _closed.value == null

    init {
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) {
            while (isActive && isOpen) {
                delay(LanConstants.PING_INTERVAL_MS)
                runCatching { io.writeControl(Message(MsgType.PING, newId())) }.onFailure { shutdown("Connection lost") }
            }
        }
    }

    private fun stream(id: Int): Channel<ByteArray> = streams.computeIfAbsent(id) { Channel(16) }

    private suspend fun readLoop() {
        var reason = "Connection closed"
        try {
            while (true) {
                when (val f = io.readFrame() ?: break) {
                    is Frame.Control -> route(f.message ?: continue)
                    is Frame.Data -> stream(f.streamId).send(f.bytes)
                    is Frame.End -> stream(f.streamId).close()
                    is Frame.Abort -> stream(f.streamId).close(IOException(f.reason))
                }
            }
        } catch (e: Exception) {
            reason = e.message ?: reason
        } finally {
            shutdown(reason)
        }
    }

    private fun route(m: Message) {
        when {
            m.type == MsgType.REVOKED -> shutdown("The host revoked your access")
            m.type == MsgType.UPLOAD_RESULT -> uploadResults[m.id]?.complete(m)
            m.type == MsgType.PONG -> Unit
            m.isResponse -> pending[m.id]?.complete(m)
        }
    }

    private fun shutdown(reason: String) {
        if (!_closed.compareAndSet(null, reason)) return
        runCatching { socket.close() }
        val gone = IOException(reason)
        pending.values.forEach { it.completeExceptionally(gone) }
        uploadResults.values.forEach { it.completeExceptionally(gone) }
        streams.values.forEach { it.close(gone) }
    }

    fun close() = shutdown("Disconnected")

    private suspend fun request(type: String, body: JSONObject = JSONObject(), timeoutMs: Long = 30_000): Message {
        if (!isOpen) throw IOException(_closed.value ?: "Not connected")
        val id = newId()
        val waiter = CompletableDeferred<Message>()
        pending[id] = waiter
        try {
            withContext(Dispatchers.IO) { io.writeControl(Message(type, id, false, true, body)) }
            return withTimeout(timeoutMs) { waiter.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun call(type: String, body: JSONObject = JSONObject()): JSONObject {
        val r = request(type, body)
        if (!r.ok) {
            val (code, msg) = r.error()
            throw LanRemoteException(code, msg.ifBlank { code })
        }
        return r.body
    }

    // ---- file system API -----------------------------------------------------------------------------------------

    suspend fun storageInfo(): StorageInfo = StorageInfo.fromJson(call(MsgType.STORAGE_INFO))

    suspend fun list(parentId: String?): List<RemoteFile> =
        RemoteFile.listFromJson(call(MsgType.LIST_DIRECTORY, JSONObject().put("parentId", parentId ?: "")).getJSONArray("items"))

    suspend fun createFolder(name: String, parentId: String?): RemoteFile =
        RemoteFile.fromJson(call(MsgType.MKDIR, JSONObject().put("name", name).put("parentId", parentId ?: "")))

    suspend fun stat(fileId: String): RemoteFile = RemoteFile.fromJson(call(MsgType.STAT, JSONObject().put("fileId", fileId)))

    suspend fun rename(fileId: String, newName: String): RemoteFile =
        RemoteFile.fromJson(call(MsgType.RENAME, JSONObject().put("fileId", fileId).put("name", newName)))

    suspend fun search(query: String): List<RemoteFile> =
        RemoteFile.listFromJson(call(MsgType.SEARCH, JSONObject().put("q", query)).getJSONArray("items"))

    suspend fun delete(fileId: String) {
        call(MsgType.DELETE_FILE, JSONObject().put("fileId", fileId))
    }

    suspend fun cancelTransfer(transferId: String) {
        runCatching { call(MsgType.TRANSFER_CANCEL, JSONObject().put("transferId", transferId)) }
    }

    // ---- transfers ---------------------------------------------------------------------------------------------------

    /**
     * Uploads [size] bytes read from [input] (the SHA-256 [sha256] must be computed beforehand, e.g. with [digestOf]).
     * [onAccepted] gets the transfer id once the host has reserved space. Returns the new file's id on the host.
     */
    suspend fun upload(
        name: String,
        parentId: String?,
        size: Long,
        sha256: String,
        input: () -> InputStream,
        onAccepted: suspend (transferId: String) -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): String? {
        val begin = call(
            MsgType.UPLOAD_BEGIN,
            JSONObject().put("name", name).put("size", size).put("sha256", sha256).put("parentId", parentId ?: ""),
        )
        val tid = begin.getString("transferId")
        val sid = begin.getInt("streamId")
        val result = CompletableDeferred<Message>()
        uploadResults[tid] = result
        onAccepted(tid)
        try {
            withContext(Dispatchers.IO) {
                input().use { ins ->
                    val buf = ByteArray(LanConstants.CHUNK_BYTES)
                    var sent = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        io.writeData(sid, buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                    if (sent != size) {
                        runCatching { io.writeAbort(sid, "size changed") }
                        throw IOException("The file changed while uploading")
                    }
                    io.writeEnd(sid)
                }
            }
            val res = withTimeout(10 * 60_000L) { result.await() }
            if (!res.ok) {
                val (code, msg) = res.error()
                throw LanRemoteException(code, msg.ifBlank { "Upload failed" })
            }
            return res.body.optString("fileId").ifEmpty { null }
        } catch (e: CancellationException) {
            runCatching { io.writeAbort(sid, "cancelled") }
            throw e
        } finally {
            uploadResults.remove(tid)
        }
    }

    /** Downloads a file into [out], verifying its size and SHA-256. [onMeta] is called as soon as the host accepts. */
    suspend fun download(
        fileId: String,
        out: OutputStream,
        onMeta: suspend (DownloadMeta) -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): DownloadMeta {
        val begin = call(MsgType.DOWNLOAD_BEGIN, JSONObject().put("fileId", fileId))
        val sid = begin.getInt("streamId")
        val meta = DownloadMeta(begin.getString("transferId"), begin.getString("name"), begin.getLong("size"), begin.getString("sha256"))
        onMeta(meta)
        val digest = MessageDigest.getInstance("SHA-256")
        var got = 0L
        val channel = stream(sid)
        try {
            withContext(Dispatchers.IO) {
                for (chunk in channel) {
                    digest.update(chunk)
                    out.write(chunk)
                    got += chunk.size
                    onProgress(got)
                }
                out.flush()
            }
        } catch (e: CancellationException) {
            runCatching { cancelTransfer(meta.transferId) }
            throw e
        } finally {
            streams.remove(sid)
        }
        if (got != meta.size || !digest.digest().toHexString().equals(meta.sha256, ignoreCase = true)) {
            throw LanRemoteException(ErrorCode.INTEGRITY, "The file was damaged in transit")
        }
        return meta
    }
}

/** Size and SHA-256 of a stream, read once; needed before an upload so the host can verify it. */
fun digestOf(open: () -> InputStream): Pair<Long, String> {
    val md = MessageDigest.getInstance("SHA-256")
    var size = 0L
    open().use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            size += n
        }
    }
    return size to md.digest().toHexString()
}
