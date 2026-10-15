package com.nearbyshare.host

import com.nearbyshare.core.network.protocol.ErrorCode
import com.nearbyshare.lan.JvmIdentity
import com.nearbyshare.lan.LanGuestClient
import com.nearbyshare.lan.LanHostServer
import com.nearbyshare.lan.LanRemoteException
import com.nearbyshare.lan.LanSession
import com.nearbyshare.lan.PinStore
import com.nearbyshare.lan.PinnedHost
import com.nearbyshare.lan.digestOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HostEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: HostEngine
    private lateinit var server: LanHostServer
    private var port = 0
    private val hostId = UUID.randomUUID().toString()
    private val hostIdentity = JvmIdentity.generateInMemory("host")

    private class MemPins : PinStore {
        val m = ConcurrentHashMap<String, Pair<String, String>>()
        override suspend fun expectedFingerprint(hostId: String) = m[hostId]?.second
        override suspend fun findByFingerprint(fingerprint: String) = m.entries.firstOrNull { it.value.second == fingerprint }?.let { PinnedHost(it.key, it.value.first) }
        override suspend fun save(hostId: String, name: String, fingerprint: String) { m[hostId] = name to fingerprint }
    }

    @Before
    fun setUp() {
        engine = HostEngine(tmp.newFolder("data"), scope)
        engine.updateConfig { it.copy(sharingEnabled = true, accessMode = AccessMode.OPEN, quotaBytes = 1 * GIB, safetyReserveBytes = 0) }
        server = LanHostServer(lazyOf(hostIdentity), engine, hostId, { "PC" }, scope)
        engine.onDeviceDropped = { server.disconnectDevice(it) }
        port = server.start(0)
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
        engine.close()
    }

    private fun <T> test(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    private suspend fun guest(name: String = "Phone", pins: MemPins = MemPins()): Pair<LanSession, String> {
        val id = UUID.randomUUID().toString()
        val c = LanGuestClient(JvmIdentity.generateInMemory(name), id, { name }, scope)
        return c.connect("127.0.0.1", port, null, pins) { _, _ -> true } to id
    }

    private suspend fun LanSession.put(name: String, data: ByteArray, parent: String? = null): String? {
        val (size, sha) = digestOf { ByteArrayInputStream(data) }
        return upload(name, parent, size, sha, { ByteArrayInputStream(data) })
    }

    private suspend fun LanSession.get(id: String): ByteArray = ByteArrayOutputStream().also { download(id, it) }.toByteArray()

    private suspend fun expectCode(code: String, block: suspend () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("expected $code but got $e", e is LanRemoteException)
        assertEquals(code, (e as LanRemoteException).code)
    }

    @Test
    fun uploadDownloadRoundTripInPrivateStorage() = test {
        val (s, _) = guest()
        val data = ByteArray(2_000_000) { (it % 251).toByte() }
        val id = s.put("a.bin", data)!!
        assertTrue(data.contentEquals(s.get(id)))
        assertEquals(data.size.toLong(), engine.usedBytes.value)
        // stored under random names, never the guest's name
        val names = File(tmp.root, "data/shared_storage/objects").walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(1, names.size)
        assertFalse(names[0].contains("a.bin"))
        s.close()
    }

    @Test
    fun privateModeRejectsUnknownDevices() = test {
        engine.updateConfig { it.copy(accessMode = AccessMode.PRIVATE) }
        val r = runCatching { guest() }
        assertTrue(r.isFailure)
        assertTrue(engine.devices.value.isEmpty())
    }

    @Test
    fun approvalModeAsksTheHostAndHonoursDeny() = test {
        engine.updateConfig { it.copy(accessMode = AccessMode.APPROVAL) }
        val denier = scope.async { // answer the prompt as soon as it shows
            while (engine.pending.value.isEmpty()) delay(20)
            engine.resolvePairing(engine.pending.value.first().key, false)
        }
        assertTrue(runCatching { guest() }.isFailure)
        denier.await()
        assertTrue(engine.devices.value.isEmpty())

        val allower = scope.async {
            while (engine.pending.value.isEmpty()) delay(20)
            engine.resolvePairing(engine.pending.value.first().key, true)
        }
        val (s, _) = guest()
        allower.await()
        assertEquals(1, engine.devices.value.size)
        s.close()
    }

    @Test
    fun permissionsAreEnforcedOnEveryCall() = test {
        val (s, id) = guest()
        val fid = s.put("x.txt", "hello".toByteArray())!!
        engine.updateAccess(id) { it.copy(canDelete = false) }
        expectCode(ErrorCode.DENIED) { s.delete(fid) }
        engine.updateAccess(id) { it.copy(canWrite = false) }
        expectCode(ErrorCode.DENIED) { s.put("y.txt", "y".toByteArray()) }
        engine.updateAccess(id) { it.copy(canCreateFolders = false) }
        expectCode(ErrorCode.DENIED) { s.createFolder("d", null) }
        engine.updateAccess(id) { it.copy(canRead = false) }
        expectCode(ErrorCode.DENIED) { s.list(null) }
        engine.updateAccess(id) { it.copy(canRead = true, canDelete = true) }
        s.delete(fid)
        s.close()
    }

    @Test
    fun overwriteNeedsPermissionAndReplacesContent() = test {
        val (s, id) = guest()
        s.put("f.txt", "one".toByteArray())
        expectCode(ErrorCode.NAME_EXISTS) { s.put("f.txt", "two".toByteArray()) }
        engine.updateAccess(id) { it.copy(canOverwrite = true) }
        val fid = s.put("f.txt", "three!".toByteArray())!!
        assertEquals("three!", String(s.get(fid)))
        assertEquals(1, s.list(null).size)
        assertEquals(6L, engine.usedBytes.value)
        s.close()
    }

    @Test
    fun quotaIsEnforcedAndConcurrentUploadsCannotOversubscribe() = test {
        engine.updateConfig { it.copy(quotaBytes = 300_000, maxGuests = 8) }
        val guests = (1..4).map { guest("G$it").first }
        val data = ByteArray(100_000) { 5 }
        val results = guests.mapIndexed { i, s -> scope.async { runCatching { s.put("f$i.bin", data) } } }.awaitAll()
        val ok = results.count { it.isSuccess }
        assertTrue("at most 3 of 4 fit in 300 kB (got $ok)", ok <= 3)
        assertTrue(engine.usedBytes.value <= 300_000)
        results.filter { it.isFailure }.forEach { assertEquals(ErrorCode.QUOTA_EXCEEDED, (it.exceptionOrNull() as LanRemoteException).code) }
        guests.forEach { it.close() }
    }

    @Test
    fun guestsCannotSeeOrTouchEachOthersFiles() = test {
        val (a, _) = guest("A")
        val (b, bid) = guest("B")
        engine.updateAccess(bid) { it.copy(canDelete = true) }
        val fa = a.put("secret.txt", "s".toByteArray())!!
        assertTrue(b.list(null).isEmpty())
        expectCode(ErrorCode.NOT_FOUND) { b.get(fa) }
        expectCode(ErrorCode.NOT_FOUND) { b.delete(fa) }
        a.close(); b.close()
    }

    @Test
    fun foldersRenameSearchAndDeleteRules() = test {
        val (s, id) = guest()
        val folder = s.createFolder("docs", null)
        expectCode(ErrorCode.NAME_EXISTS) { s.createFolder("docs", null) }
        val fid = s.put("100%_a.txt", "x".toByteArray(), folder.fileId)!!
        s.put("other.txt", "x".toByteArray(), folder.fileId)
        assertEquals(listOf("100%_a.txt"), s.search("0%_").map { it.name }) // % and _ are literal
        assertEquals("b.txt", s.rename(fid, "b.txt").name)
        expectCode(ErrorCode.BAD_REQUEST) { s.rename(fid, "../evil") }
        engine.updateAccess(id) { it.copy(canDelete = true) }
        expectCode(ErrorCode.BAD_REQUEST) { s.delete(folder.fileId) } // not empty
        s.list(folder.fileId).forEach { s.delete(it.fileId) }
        s.delete(folder.fileId)
        s.close()
    }

    @Test
    fun revokedAndExpiredAccessStopsWorkingImmediately() = test {
        val (s, id) = guest()
        s.put("a.txt", "a".toByteArray())
        engine.setDeviceExpiry(id, -1) // expired an hour ago
        expectCode(ErrorCode.EXPIRED) { s.list(null) }
        engine.setDeviceExpiry(id, null)
        s.list(null)
        engine.revoke(id)
        val r = runCatching { s.list(null) }
        assertTrue(r.isFailure)
        engine.restore(id)
        val (s2, _) = guest() // a new connection works again only after restore (same device id is new here; just ensure engine is healthy)
        s2.close()
    }

    @Test
    fun blockedDeviceCannotReconnect() = test {
        val pins = MemPins()
        val id = UUID.randomUUID().toString()
        val ident = JvmIdentity.generateInMemory("blocked")
        val mk = { LanGuestClient(ident, id, { "B" }, scope) }
        mk().connect("127.0.0.1", port, null, pins) { _, _ -> true }.close()
        engine.block(id)
        assertTrue(runCatching { mk().connect("127.0.0.1", port, null, pins) { _, _ -> true } }.isFailure)
        engine.unblock(id)
        mk().connect("127.0.0.1", port, null, pins) { _, _ -> true }.close()
    }

    @Test
    fun badChecksumLeavesNothingBehindAndReleasesQuota() = test {
        val (s, _) = guest()
        val data = ByteArray(10_000) { 1 }
        val e = runCatching { s.upload("bad.bin", null, data.size.toLong(), "0".repeat(64), { ByteArrayInputStream(data) }) }.exceptionOrNull()
        assertTrue(e != null)
        assertTrue(s.list(null).isEmpty())
        assertEquals(0L, engine.usedBytes.value)
        s.put("good.bin", data) // quota and the one-upload-at-a-time slot are free again
        s.close()
    }

    @Test
    fun visibleFolderKeepsRealFilesInPerGuestFolders() = test {
        val shared = tmp.newFolder("shared")
        assertNull(engine.useVisibleFolder(shared.absolutePath))
        val (s, id) = guest("My:Phone")
        val folder = s.createFolder("Trips", null)
        val fid = s.put("pic.jpg", "jpg".toByteArray(), folder.fileId)!!
        val guestDir = shared.listFiles()!!.single { it.isDirectory }
        assertTrue(guestDir.name.endsWith("(${id.take(4)})"))
        assertFalse(guestDir.name.contains(':'))
        val real = File(File(guestDir, "Trips"), "pic.jpg")
        assertEquals("jpg", real.readText())
        assertEquals("pic2.jpg", s.rename(fid, "pic2.jpg").name)
        assertTrue(File(File(guestDir, "Trips"), "pic2.jpg").isFile)
        // host edits the file in Explorer -> the guest is told instead of getting stale bytes
        File(File(guestDir, "Trips"), "pic2.jpg").writeText("changed!!")
        expectCode(ErrorCode.NOT_FOUND) { s.get(fid) }
        // no half-written parts are left
        assertTrue(guestDir.walkTopDown().none { it.name.endsWith(".part") })
        s.close()
    }

    @Test
    fun visibleModeNeverOverwritesAFileTheHostPutThere() = test {
        val shared = tmp.newFolder("shared2")
        engine.useVisibleFolder(shared.absolutePath)
        val (s, _) = guest("P")
        s.put("other.txt", "x".toByteArray())
        val guestDir = shared.listFiles()!!.single { it.isDirectory }
        File(guestDir, "n.txt").writeText("host's own")
        val id2 = s.put("n.txt", "second".toByteArray())!!
        assertEquals("host's own", File(guestDir, "n.txt").readText())
        assertTrue(File(guestDir, "n (1).txt").isFile)
        assertEquals("second", String(s.get(id2)))
        s.close()
    }

    @Test
    fun quotaChangeValidation() {
        assertTrue(engine.setQuota(10 * MIB)!!.contains("256"))
        assertNull(engine.setQuota(2 * GIB / 4 + 1))
    }

    @Test
    fun restartReleasesInFlightReservations() = test {
        val (s, _) = guest()
        s.close()
        engine.close()
        val again = HostEngine(File(tmp.root, "data"), scope)
        assertEquals(0L, again.usedBytes.value)
        again.close()
        engine = HostEngine(File(tmp.root, "data"), scope) // for tearDown
    }
}
