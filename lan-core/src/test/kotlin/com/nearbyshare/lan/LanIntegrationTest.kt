package com.nearbyshare.lan

import com.nearbyshare.core.network.protocol.ErrorCode
import com.nearbyshare.core.network.protocol.Message
import com.nearbyshare.core.network.protocol.MsgType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import kotlin.random.Random

class LanIntegrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backend = FakeBackend()
    private val hostId = UUID.randomUUID().toString()
    private val guestDeviceId = UUID.randomUUID().toString()
    private val hostIdentity = JvmIdentity.generateInMemory("test-host")
    private val guestIdentity = JvmIdentity.generateInMemory("test-guest")
    private lateinit var server: LanHostServer
    private var port = 0

    private class MemPins : PinStore {
        val byHost = ConcurrentHashMap<String, Pair<String, String>>() // hostId -> (name, fingerprint)
        override suspend fun expectedFingerprint(hostId: String) = byHost[hostId]?.second
        override suspend fun findByFingerprint(fingerprint: String) =
            byHost.entries.firstOrNull { it.value.second == fingerprint }?.let { PinnedHost(it.key, it.value.first) }
        override suspend fun save(hostId: String, name: String, fingerprint: String) {
            byHost[hostId] = name to fingerprint
        }
    }

    @Before
    fun start() {
        server = LanHostServer(hostIdentity, backend, hostId, { "Test Host" }, scope)
        port = server.start(0)
    }

    @After
    fun stop() {
        server.stop()
        scope.cancel()
    }

    private fun <T> test(block: suspend () -> T): T = runBlocking { withTimeout(40_000) { block() } }

    private fun client() = LanGuestClient(guestIdentity, guestDeviceId, { "Test Guest" }, scope)

    private suspend fun pairedSession(pins: MemPins = MemPins()) =
        client().connect("127.0.0.1", port, null, pins) { _, _ -> true }

    @Test
    fun codesMatchAndPinAllowsReconnectWithoutCode() = test {
        val pins = MemPins()
        var shown: String? = null
        val s1 = client().connect("127.0.0.1", port, null, pins) { code, host -> shown = code; assertEquals("Test Host", host); true }
        assertNotNull(shown)
        assertEquals("both screens must show the same code", shown, backend.lastSas)
        assertEquals(hostId, s1.hostId)
        assertEquals(hostIdentity.fingerprint, pins.byHost[hostId]?.second)
        assertEquals(guestDeviceId, backend.peers[guestIdentity.fingerprint]?.deviceId)
        s1.close()

        // Second connection: recognised by the pinned certificate, so no code is requested.
        val s2 = client().connect("127.0.0.1", port, hostId, pins) { _, _ -> fail("must not ask for a code again"); false }
        assertEquals("Test Host", s2.hostName)
        assertEquals(1, backend.pairingRequests)
        s2.close()
    }

    @Test
    fun uploadAndDownloadRoundTripLargeFile() = test {
        val s = pairedSession()
        val data = Random(7).nextBytes(3 * 1024 * 1024 + 123)
        val (size, sha) = digestOf { ByteArrayInputStream(data) }
        var lastProgress = 0L
        val id = s.upload("big.bin", null, size, sha, { ByteArrayInputStream(data) }, onProgress = { lastProgress = it })
        assertNotNull(id)
        assertEquals(size, lastProgress)

        assertEquals(listOf("big.bin"), s.list(null).map { it.name })
        assertEquals(size, s.stat(id!!).sizeBytes)

        val out = ByteArrayOutputStream()
        val meta = s.download(id, out)
        assertEquals("big.bin", meta.name)
        assertTrue("downloaded bytes must equal the original", data.contentEquals(out.toByteArray()))
        s.close()
    }

    @Test
    fun foldersRenameSearchDeleteWork() = test {
        val s = pairedSession()
        val folder = s.createFolder("docs", null)
        val (size, sha) = digestOf { ByteArrayInputStream("hello".toByteArray()) }
        val id = s.upload("note.txt", folder.fileId, size, sha, { ByteArrayInputStream("hello".toByteArray()) })!!
        assertEquals(folder.fileId, s.stat(id).parentId)
        assertEquals("renamed.txt", s.rename(id, "renamed.txt").name)
        assertEquals(listOf("renamed.txt"), s.search("RENAMED").map { it.name })
        s.delete(id)
        assertTrue(s.list(folder.fileId).isEmpty())
        s.close()
    }

    @Test
    fun hostLimitsAndPermissionsReachTheGuest() = test {
        val s = pairedSession()
        backend.quota = 1000
        val data = ByteArray(5000) { 1 }
        val (size, sha) = digestOf { ByteArrayInputStream(data) }
        val quotaError = runCatching { s.upload("x.bin", null, size, sha, { ByteArrayInputStream(data) }) }.exceptionOrNull()
        assertTrue(quotaError is LanRemoteException)
        assertEquals(ErrorCode.QUOTA_EXCEEDED, (quotaError as LanRemoteException).code)

        backend.quota = 1_000_000
        val id = s.upload("ok.txt", null, 3, sha256Hex("abc".toByteArray()), { ByteArrayInputStream("abc".toByteArray()) })!!
        backend.allowDelete = false
        val denied = runCatching { s.delete(id) }.exceptionOrNull()
        assertEquals(ErrorCode.DENIED, (denied as LanRemoteException).code)
        s.close()
    }

    @Test
    fun corruptedUploadIsRejectedByChecksum() = test {
        val s = pairedSession()
        val data = ByteArray(2000) { 5 }
        val wrongSha = sha256Hex("something else".toByteArray())
        val err = runCatching { s.upload("bad.bin", null, data.size.toLong(), wrongSha, { ByteArrayInputStream(data) }) }.exceptionOrNull()
        assertEquals(ErrorCode.INTEGRITY, (err as LanRemoteException).code)
        assertTrue(backend.files.values.none { it.name == "bad.bin" })
        s.close()
    }

    @Test
    fun hostDenyingPairingStopsTheConnection() = test {
        backend.approvePairing = false
        val err = runCatching { client().connect("127.0.0.1", port, null, MemPins()) { _, _ -> true } }.exceptionOrNull()
        assertNotNull("pairing must fail when the host says no", err)
        assertTrue(backend.peers.isEmpty())
    }

    @Test
    fun guestDecliningTheCodeStopsPairing() = test {
        val err = runCatching { client().connect("127.0.0.1", port, null, MemPins()) { _, _ -> false } }.exceptionOrNull()
        assertTrue(err is LanPairingDeclinedException)
        assertTrue("nothing may be remembered", backend.peers.isEmpty())
    }

    @Test
    fun blockedDeviceCannotPair() = test {
        backend.blockedIds = setOf(guestDeviceId)
        val err = runCatching { pairedSession() }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(backend.peers.isEmpty())
    }

    @Test
    fun changedHostIdentityIsRefusedBeforeAnythingIsSent() = test {
        val pins = MemPins()
        pins.byHost[hostId] = "Test Host" to "0".repeat(64) // we remember a different certificate for this host id
        val err = runCatching { client().connect("127.0.0.1", port, hostId, pins) { _, _ -> true } }.exceptionOrNull()
        assertTrue(err is LanIdentityChangedException)
        assertEquals("no pairing may start with an impostor", 0, backend.pairingRequests)
    }

    @Test
    fun sharingOffRejectsConnections() = test {
        backend.sharing = false
        val err = runCatching { pairedSession() }.exceptionOrNull()
        assertEquals(ErrorCode.SHARING_OFF, (err as LanRemoteException).code)
    }

    @Test
    fun revokedDeviceIsDroppedImmediately() = test {
        val s = pairedSession()
        server.disconnectDevice(guestDeviceId)
        val deadline = System.currentTimeMillis() + 5000
        while (s.isOpen && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(s.isOpen)
        assertNotNull(s.closedReason.value)
    }

    // ---- attacks on the wire, using a raw client ------------------------------------------------------------------

    private fun rawSocket(): Pair<SSLSocket, FrameIO> {
        val sock = LanTls.context(guestIdentity).socketFactory.createSocket() as SSLSocket
        sock.connect(InetSocketAddress("127.0.0.1", port), 5000)
        sock.soTimeout = 10_000
        sock.startHandshake()
        return sock to FrameIO(sock.inputStream, sock.outputStream)
    }

    private fun control(io: FrameIO) = (io.readFrame() as Frame.Control).message!!

    @Test
    fun revealNotMatchingTheCommitmentIsRejected() = test {
        val (sock, io) = rawSocket()
        val honest = randomHex(16)
        io.writeControl(Message(MsgType.HELLO, "1", false, true, JSONObject().put("deviceId", guestDeviceId).put("name", "x").put("commit", LanPairing.commitment(honest))))
        assertEquals(MsgType.PAIR_CHALLENGE, control(io).type)
        // an attacker that swapped its random value after seeing the host's cannot match the commitment
        io.writeControl(Message(MsgType.PAIR_REVEAL, "2", false, true, JSONObject().put("nonce", randomHex(16))))
        val reply = control(io)
        assertEquals(MsgType.AUTH_RESULT, reply.type)
        assertFalse(reply.ok)
        assertEquals("no approval prompt may be shown", 0, backend.pairingRequests)
        sock.close()
    }

    @Test
    fun uploadingMoreThanDeclaredIsCutOff() = test {
        val (sock, io) = rawSocket()
        val nonce = randomHex(16)
        io.writeControl(Message(MsgType.HELLO, "1", false, true, JSONObject().put("deviceId", guestDeviceId).put("name", "x").put("commit", LanPairing.commitment(nonce))))
        control(io) // PAIR_CHALLENGE
        io.writeControl(Message(MsgType.PAIR_REVEAL, "2", false, true, JSONObject().put("nonce", nonce)))
        io.writeControl(Message(MsgType.PAIR_CONFIRM, "3", false, true, JSONObject().put("accept", true)))
        assertTrue(control(io).ok) // AUTH_RESULT

        io.writeControl(Message(MsgType.UPLOAD_BEGIN, "4", false, true, JSONObject().put("name", "liar.bin").put("size", 10).put("sha256", sha256Hex(ByteArray(10))).put("parentId", "")))
        val begin = control(io)
        assertTrue(begin.ok)
        val sid = begin.body.getInt("streamId")
        io.writeData(sid, ByteArray(500), 0, 500) // declared 10 bytes, sends 500
        var sawFailure = false
        // The host answers with an abort and/or a failed upload result; stop reading as soon as either arrives.
        for (i in 1..3) {
            when (val f = io.readFrame()) {
                is Frame.Abort -> sawFailure = true
                is Frame.Control -> if (f.message?.type == MsgType.UPLOAD_RESULT && f.message?.ok == false) sawFailure = true
                else -> Unit
            }
            if (sawFailure) break
        }
        assertTrue("the host must abort an upload that exceeds its declared size", sawFailure)
        assertTrue(backend.files.values.none { it.name == "liar.bin" })
        sock.close()
    }

    @Test
    fun plainTcpWithoutTlsIsNotServed() = test {
        java.net.Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 3000
            s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            val n = runCatching { s.getInputStream().read() }.getOrDefault(-1)
            // either an immediate close or a TLS alert; never plaintext application data
            assertTrue(n == -1 || n == 0x15)
        }
        assertEquals(0, backend.pairingRequests)
    }

    @Test
    fun serverFullRejectsExtraGuests() = test {
        backend.maxGuestCount = 1
        val first = pairedSession()
        // a second, different guest identity
        val other = LanGuestClient(JvmIdentity.generateInMemory("other"), UUID.randomUUID().toString(), { "Other" }, scope)
        val err = runCatching { other.connect("127.0.0.1", port, null, MemPins()) { _, _ -> true } }.exceptionOrNull()
        assertNotNull(err)
        first.close()
    }

    // ---- pure functions --------------------------------------------------------------------------------------------

    @Test
    fun framesSurviveARoundTrip() {
        val pipe = ByteArrayOutputStream()
        val w = FrameIO(ByteArrayInputStream(ByteArray(0)), pipe)
        w.writeControl(Message(MsgType.PING, "abc"))
        w.writeData(7, byteArrayOf(1, 2, 3), 0, 3)
        w.writeEnd(7)
        w.writeAbort(9, "boom")
        val r = FrameIO(ByteArrayInputStream(pipe.toByteArray()), ByteArrayOutputStream())
        assertEquals(MsgType.PING, (r.readFrame() as Frame.Control).message!!.type)
        val d = r.readFrame() as Frame.Data
        assertEquals(7, d.streamId); assertEquals(listOf<Byte>(1, 2, 3), d.bytes.toList())
        assertEquals(7, (r.readFrame() as Frame.End).streamId)
        val a = r.readFrame() as Frame.Abort
        assertEquals(9, a.streamId); assertEquals("boom", a.reason)
        assertNull(r.readFrame())
    }

    @Test
    fun oversizedFramesAreRefused() {
        val bad = byteArrayOf(FrameIO.TYPE_DATA.toByte(), 0x7f, 0, 0, 0)
        val r = FrameIO(ByteArrayInputStream(bad), ByteArrayOutputStream())
        try {
            r.readFrame()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun sasDependsOnEveryInput() {
        val base = LanPairing.sas("aa", "bb", "n1", "n2")
        assertEquals(base, LanPairing.sas("aa", "bb", "n1", "n2"))
        assertTrue(Regex("\\d{3} \\d{3}").matches(base))
        assertNotEquals(base, LanPairing.sas("ab", "bb", "n1", "n2"))
        assertNotEquals(base, LanPairing.sas("aa", "bc", "n1", "n2"))
        assertNotEquals(base, LanPairing.sas("aa", "bb", "n3", "n2"))
        assertNotEquals(base, LanPairing.sas("aa", "bb", "n1", "n4"))
    }

    @Test
    fun beaconsRoundTripAndRejectJunk() {
        val id = UUID.randomUUID().toString()
        val bytes = Beacon.encode(id, "My Phone", 45820)
        val info = Beacon.decode(bytes, bytes.size, "192.168.1.9")!!
        assertEquals(LanHostInfo(id, "My Phone", "192.168.1.9", 45820), info)
        assertNull(Beacon.decode("not json".toByteArray(), 8, "1.2.3.4"))
        val other = JSONObject().put("app", "other").put("v", 1).put("id", id).put("name", "x").put("port", 1).toString().toByteArray()
        assertNull(Beacon.decode(other, other.size, "1.2.3.4"))
        val badPort = Beacon.encode(id, "x", 70000)
        assertNull(Beacon.decode(badPort, badPort.size, "1.2.3.4"))
    }
}
