package com.nearbyshare.lan

import com.nearbyshare.core.network.protocol.ErrorCode
import com.nearbyshare.core.network.protocol.Message
import com.nearbyshare.core.network.protocol.MsgType
import com.nearbyshare.core.network.protocol.OpException
import com.nearbyshare.core.security.RateLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** A guest currently connected over the network. */
class LanGuestInfo(val connId: String, val deviceId: String?, val name: String, val address: String, val authenticated: Boolean)

/** A request's answer: the body to send back (null if the handler already replied) and an action to run after it is sent. */
private class Reply(val body: JSONObject?, val after: (() -> Unit)? = null)

/** Feeds file bytes that arrive as frames to code that expects an ordinary [InputStream] (with back-pressure). */
internal class ChannelInputStream(private val channel: Channel<ByteArray>) : InputStream() {
    private var current: ByteArray? = null
    private var pos = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (current == null || pos >= current!!.size) {
            val r = runBlocking { channel.receiveCatching() }
            if (r.isClosed) {
                val cause = r.exceptionOrNull()
                if (cause != null) throw IOException(cause.message ?: "stream aborted", cause)
                return -1
            }
            current = r.getOrNull()
            pos = 0
        }
        val cur = current!!
        val n = minOf(len, cur.size - pos)
        System.arraycopy(cur, pos, b, off, n)
        pos += n
        return n
    }
}

/**
 * The host's side of the network connection method: accepts TLS connections, pairs new devices (code comparison),
 * authenticates known ones by their pinned certificate, and serves requests through a [HostBackend].
 */
class LanHostServer(
    private val identity: LanIdentity,
    private val backend: HostBackend,
    private val hostId: String,
    private val hostName: () -> String,
    private val scope: CoroutineScope,
) {
    private var server: SSLServerSocket? = null
    private var acceptJob: Job? = null
    private val conns = ConcurrentHashMap<String, Conn>()

    private val _guests = MutableStateFlow<List<LanGuestInfo>>(emptyList())
    val guests: StateFlow<List<LanGuestInfo>> = _guests.asStateFlow()

    @Volatile
    var port: Int = 0
        private set

    val running: Boolean get() = server != null

    /** Starts listening on [preferredPort], or any free port if that one is taken. Returns the port in use. */
    fun start(preferredPort: Int = LanConstants.DEFAULT_PORT): Int {
        server?.let { return port }
        val factory = LanTls.context(identity).serverSocketFactory
        val ss = (try {
            factory.createServerSocket(preferredPort)
        } catch (e: BindException) {
            factory.createServerSocket(0)
        }) as SSLServerSocket
        ss.enabledProtocols = LanTls.modernProtocols(ss.supportedProtocols)
        ss.needClientAuth = true // guests must present their certificate: that is how they are recognised
        server = ss
        port = ss.localPort
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = try {
                    ss.accept() as SSLSocket
                } catch (e: IOException) {
                    break
                }
                scope.launch(Dispatchers.IO) { Conn(socket).run() }
            }
        }
        return port
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { server?.close() }
        server = null
        conns.values.toList().forEach { it.close() }
        publish()
    }

    /** Immediately drops a device's connections (revoked or blocked). */
    fun disconnectDevice(deviceId: String) {
        conns.values.filter { it.deviceId == deviceId }.forEach {
            runCatching { it.io.writeControl(Message(MsgType.REVOKED, "0")) }
            it.close()
        }
    }

    private fun publish() {
        _guests.value = conns.values.map { LanGuestInfo(it.id, it.deviceId, it.name, it.address, it.authenticated) }
    }

    private inner class UploadState(val streamId: Int, val transferId: String, val size: Long) {
        val channel = Channel<ByteArray>(16)

        @Volatile
        var received = 0L
    }

    private inner class Conn(val socket: SSLSocket) {
        val id: String = UUID.randomUUID().toString()
        val address: String = socket.inetAddress?.hostAddress ?: "?"
        lateinit var io: FrameIO

        @Volatile
        var deviceId: String? = null

        @Volatile
        var name: String = "Pairing..."

        @Volatile
        var authenticated = false

        private val limiter = RateLimiter(40, 20.0)
        private val nextStream = AtomicInteger(1)
        private val downloads = ConcurrentHashMap<String, Job>()

        @Volatile
        private var upload: UploadState? = null

        fun close() {
            runCatching { socket.close() }
        }

        suspend fun run() {
            conns[id] = this
            publish()
            try {
                socket.enabledProtocols = LanTls.modernProtocols(socket.supportedProtocols)
                socket.soTimeout = LanConstants.HANDSHAKE_TIMEOUT_MS
                withContext(Dispatchers.IO) { socket.startHandshake() }
                io = FrameIO(socket.inputStream, socket.outputStream)
                val fingerprint = LanTls.peerFingerprint(socket)
                if (!handshake(fingerprint)) return
                socket.soTimeout = LanConstants.IDLE_TIMEOUT_MS
                loop()
            } catch (_: CancellationException) {
                // shutting down
            } catch (_: Exception) {
                // peer vanished, timed out, or spoke garbage: just end the connection
            } finally {
                cleanup()
            }
        }

        private suspend fun cleanup() {
            upload?.channel?.close(IOException("connection closed"))
            downloads.values.forEach { it.cancel() }
            val dev = deviceId
            conns.remove(id)
            close()
            publish()
            if (authenticated && dev != null) {
                runCatching { backend.abortActiveTransfers(dev) }
                runCatching { backend.audit(dev, "LAN DISCONNECT", "OK") }
            }
        }

        private suspend fun readControl(): Message? {
            val frame = withContext(Dispatchers.IO) { io.readFrame() } ?: return null
            return (frame as? Frame.Control)?.message
        }

        private fun reject(request: Message, code: String, text: String) {
            runCatching { io.writeControl(Message.error(MsgType.AUTH_RESULT, request.id, code, text)) }
        }

        private fun authOk(requestId: String, paired: Boolean) {
            io.writeControl(
                Message(
                    MsgType.AUTH_RESULT, requestId, true, true,
                    JSONObject().put("paired", paired).put("hostId", hostId).put("hostName", hostName()),
                ),
            )
        }

        /** Returns true once the peer is authenticated (already approved, or just paired). */
        private suspend fun handshake(fingerprint: String): Boolean {
            val hello = readControl() ?: return false
            if (hello.type != MsgType.HELLO) return false.also { reject(hello, ErrorCode.BAD_REQUEST, "Expected HELLO") }
            val claimedId = hello.body.optString("deviceId")
            if (!isUuid(claimedId)) return false.also { reject(hello, ErrorCode.BAD_REQUEST, "Bad device id") }
            val claimedName = sanitizeDeviceName(hello.body.optString("name"))

            if (!backend.sharingEnabled()) return false.also { reject(hello, ErrorCode.SHARING_OFF, "Sharing is turned off") }
            if (conns.size > backend.maxGuests()) return false.also { reject(hello, ErrorCode.BUSY, "The host is full") }

            val known = backend.findPeer(fingerprint)
            if (known != null) {
                // Recognised by the certificate pinned at pairing. A claimed id in the message is ignored.
                deviceId = known.deviceId
                name = known.name
                authenticated = true
                authOk(hello.id, paired = false)
                backend.audit(known.deviceId, "LAN CONNECT", "OK")
                publish()
                return true
            }

            if (backend.isBlocked(claimedId)) {
                backend.audit(claimedId, "LAN PAIR", "BLOCKED")
                return false.also { reject(hello, ErrorCode.DENIED, "Access denied") }
            }
            val commit = hello.body.optString("commit")
            if (commit.length != 64) return false.also { reject(hello, ErrorCode.BAD_REQUEST, "Missing pairing commitment") }

            name = claimedName
            publish()
            val hostNonce = randomHex(16)
            io.writeControl(
                Message(
                    MsgType.PAIR_CHALLENGE, hello.id, true, true,
                    JSONObject().put("nonce", hostNonce).put("hostId", hostId).put("hostName", hostName()),
                ),
            )
            socket.soTimeout = LanConstants.PAIRING_TIMEOUT_MS
            val reveal = readControl()
            if (reveal == null || reveal.type != MsgType.PAIR_REVEAL) return false
            val guestNonce = reveal.body.optString("nonce")
            if (!constantTimeEquals(LanPairing.commitment(guestNonce), commit)) {
                backend.audit(claimedId, "LAN PAIR", "FAILED: commitment mismatch")
                return false.also { reject(hello, ErrorCode.DENIED, "Pairing check failed") }
            }
            val sas = LanPairing.sas(fingerprint, identity.fingerprint, guestNonce, hostNonce)

            // The host's user and the guest's user both have to say yes. Whichever refuses first ends it.
            val approval = scope.async { backend.requestPairingApproval(PairingRequest(id, claimedId, claimedName, sas)) }
            val confirm = scope.async(Dispatchers.IO) { readControl() }
            val accepted = try {
                select<Boolean> {
                    approval.onAwait { ok -> ok && confirm.await()?.takeIf { it.type == MsgType.PAIR_CONFIRM }?.body?.optBoolean("accept") == true }
                    confirm.onAwait { c ->
                        val yes = c?.type == MsgType.PAIR_CONFIRM && c.body.optBoolean("accept")
                        yes && approval.await()
                    }
                }
            } finally {
                approval.cancel()
                confirm.cancel()
            }
            if (!accepted) {
                backend.audit(claimedId, "LAN PAIR", "DENIED")
                return false.also { reject(hello, ErrorCode.DENIED, "Pairing was not approved") }
            }

            backend.registerPeer(fingerprint, claimedId, claimedName)
            deviceId = claimedId
            authenticated = true
            socket.soTimeout = LanConstants.IDLE_TIMEOUT_MS
            authOk(hello.id, paired = true)
            backend.audit(claimedId, "LAN PAIR", "APPROVED")
            publish()
            return true
        }

        private suspend fun loop() {
            while (true) {
                val frame = withContext(Dispatchers.IO) { io.readFrame() } ?: break
                when (frame) {
                    is Frame.Control -> {
                        val m = frame.message
                        when {
                            m == null -> io.writeControl(Message.error("ERROR", "0", ErrorCode.BAD_REQUEST, "Malformed message"))
                            m.type == MsgType.PING -> io.writeControl(Message(MsgType.PONG, m.id, true))
                            // handled off the read loop so a slow request never stalls incoming file data
                            else -> scope.launch(Dispatchers.IO) { handleControl(m) }
                        }
                    }
                    is Frame.Data -> onData(frame)
                    is Frame.End -> upload?.takeIf { it.streamId == frame.streamId }?.channel?.close()
                    is Frame.Abort -> upload?.takeIf { it.streamId == frame.streamId }?.channel?.close(IOException(frame.reason))
                }
            }
        }

        private suspend fun onData(frame: Frame.Data) {
            val st = upload
            if (st == null || st.streamId != frame.streamId) {
                runCatching { io.writeAbort(frame.streamId, "no upload in progress") }
                return
            }
            st.received += frame.bytes.size
            // Enforce the reserved size as bytes arrive: a guest cannot declare 1 MB and send 1 GB.
            if (st.received > st.size) {
                st.channel.close(IOException("more data than declared"))
                runCatching { io.writeAbort(st.streamId, "more data than declared") }
                return
            }
            backend.onProgress(st.transferId, st.received)
            try {
                st.channel.send(frame.bytes)
            } catch (_: Exception) {
                // the upload already failed; remaining frames are dropped
            }
        }

        private suspend fun handleControl(m: Message) {
            if (!limiter.tryAcquire()) {
                io.writeControl(Message.error(m.type, m.id, ErrorCode.RATE_LIMITED, "Too many requests"))
                return
            }
            try {
                val reply = dispatch(m)
                reply.body?.let { io.writeControl(Message(m.type, m.id, true, true, it)) }
                reply.after?.invoke()
            } catch (e: OpException) {
                backend.audit(deviceId, m.type, "DENIED:${e.code}")
                runCatching { io.writeControl(Message.error(m.type, m.id, e.code, e.message ?: "")) }
            } catch (_: CancellationException) {
                // connection closing
            } catch (e: Exception) {
                runCatching { io.writeControl(Message.error(m.type, m.id, ErrorCode.INTERNAL, "Request failed")) }
            }
        }

        private suspend fun dispatch(m: Message): Reply {
            val dev = deviceId ?: throw OpException(ErrorCode.UNAUTHENTICATED, "Authenticate first")
            val b = m.body
            fun parent() = b.optString("parentId").ifEmpty { null }
            return when (m.type) {
                MsgType.STORAGE_INFO -> Reply(backend.storageInfo(dev).toJson())
                MsgType.LIST_DIRECTORY -> Reply(JSONObject().put("items", JSONArray(backend.list(dev, parent()).map { it.toJson() })))
                MsgType.MKDIR -> Reply(backend.createFolder(dev, b.getString("name"), parent()).toJson())
                MsgType.STAT -> Reply(backend.stat(dev, b.getString("fileId")).toJson())
                MsgType.RENAME -> Reply(backend.rename(dev, b.getString("fileId"), b.getString("name")).toJson())
                MsgType.SEARCH -> Reply(JSONObject().put("items", JSONArray(backend.search(dev, b.getString("q")).map { it.toJson() })))
                MsgType.DELETE_FILE -> Reply(JSONObject().also { backend.delete(dev, b.getString("fileId")) })
                MsgType.UPLOAD_BEGIN -> startUpload(dev, b)
                MsgType.DOWNLOAD_BEGIN -> startDownload(dev, b)
                MsgType.TRANSFER_CANCEL -> {
                    val tid = b.getString("transferId")
                    upload?.takeIf { it.transferId == tid }?.channel?.close(IOException("cancelled by guest"))
                    downloads[tid]?.cancel()
                    Reply(JSONObject())
                }
                else -> throw OpException(ErrorCode.BAD_REQUEST, "Unknown request")
            }
        }

        private suspend fun startUpload(dev: String, b: JSONObject): Reply {
            if (upload != null) throw OpException(ErrorCode.BUSY, "Another upload is in progress")
            val size = b.getLong("size")
            val tid = backend.beginUpload(dev, b.getString("name"), size, b.getString("sha256"), b.optString("parentId").ifEmpty { null }, name)
            val st = UploadState(nextStream.getAndIncrement(), tid, size)
            upload = st
            scope.launch(Dispatchers.IO) { runUpload(dev, st) }
            return Reply(JSONObject().put("transferId", tid).put("streamId", st.streamId))
        }

        private suspend fun runUpload(dev: String, st: UploadState) {
            try {
                val fileId = backend.receiveUpload(dev, st.transferId, ChannelInputStream(st.channel))
                io.writeControl(
                    Message(MsgType.UPLOAD_RESULT, st.transferId, true, true, JSONObject().put("fileId", fileId ?: "")),
                )
            } catch (e: OpException) {
                runCatching { io.writeControl(Message.error(MsgType.UPLOAD_RESULT, st.transferId, e.code, e.message ?: "")) }
            } catch (_: CancellationException) {
                // connection closing: the backend already released the reservation
            } catch (e: Exception) {
                runCatching { io.writeControl(Message.error(MsgType.UPLOAD_RESULT, st.transferId, ErrorCode.INTERNAL, "Upload failed")) }
            } finally {
                if (upload === st) upload = null
            }
        }

        private suspend fun startDownload(dev: String, b: JSONObject): Reply {
            val src = backend.beginDownload(dev, b.getString("fileId"), name)
            val sid = nextStream.getAndIncrement()
            val body = JSONObject().put("transferId", src.transferId).put("streamId", sid).put("size", src.size)
                .put("sha256", src.sha256).put("name", src.name)
            // The reply goes out first, then the bytes start flowing.
            return Reply(body) {
                downloads[src.transferId] = scope.launch(Dispatchers.IO) { streamFile(src, sid) }
            }
        }

        private suspend fun streamFile(src: DownloadSource, streamId: Int) {
            var sent = 0L
            try {
                src.open().use { input ->
                    val buf = ByteArray(LanConstants.CHUNK_BYTES)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        io.writeData(streamId, buf, 0, n)
                        sent += n
                        backend.onProgress(src.transferId, sent)
                    }
                }
                io.writeEnd(streamId)
                backend.finishTransfer(src.transferId, true, sent, null)
            } catch (e: CancellationException) {
                runCatching { io.writeAbort(streamId, "cancelled") }
                backend.finishTransfer(src.transferId, false, sent, "cancelled")
            } catch (e: Exception) {
                runCatching { io.writeAbort(streamId, "read failed") }
                backend.finishTransfer(src.transferId, false, sent, e.message)
            } finally {
                downloads.remove(src.transferId)
            }
        }
    }
}
