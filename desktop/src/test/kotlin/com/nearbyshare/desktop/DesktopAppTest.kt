package com.nearbyshare.desktop

import com.nearbyshare.lan.FakeBackend
import com.nearbyshare.lan.JvmIdentity
import com.nearbyshare.lan.LanHostServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random

/** Drives the Windows app's logic like a user would, against a real host over real TLS on this machine. */
class DesktopAppTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backend = FakeBackend()
    private val hostId = UUID.randomUUID().toString()
    private lateinit var server: LanHostServer
    private var port = 0
    private lateinit var dataDir: File
    private lateinit var work: File

    @Before
    fun start() {
        server = LanHostServer(lazyOf(JvmIdentity.generateInMemory("test-host")), backend, hostId, { "Living Room Phone" }, scope)
        port = server.start(0)
        dataDir = Files.createTempDirectory("nss-desktop-test").toFile()
        work = Files.createTempDirectory("nss-desktop-work").toFile()
    }

    @After
    fun stop() {
        server.stop()
        scope.cancel()
        dataDir.deleteRecursively()
        work.deleteRecursively()
    }

    private fun await(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(25)
        }
    }

    private fun pairAndConnect(app: DesktopApp) {
        assertEquals(null, app.connectByAddress("127.0.0.1:$port"))
        await("pairing prompt") { app.conn is Conn.Pairing }
        val p = app.conn as Conn.Pairing
        await("host to receive the request") { backend.lastSas != null }
        assertEquals("both screens must show the same code", backend.lastSas, p.code)
        assertEquals("Living Room Phone", p.hostName)
        app.confirmPairing(true)
        await("connected") { app.conn is Conn.Connected }
    }

    @Test
    fun fullSessionPairUploadOpenDownloadRenameDeleteAndReconnect() {
        val app = DesktopApp(scope, dataDir)
        pairAndConnect(app)
        assertEquals("Living Room Phone", (app.conn as Conn.Connected).hostName)
        await("storage info") { app.info != null }

        // upload two files, one of them large
        val big = File(work, "movie.bin").apply { writeBytes(Random(3).nextBytes(2_500_000)) }
        val small = File(work, "notes.txt").apply { writeText("hello from windows") }
        app.upload(listOf(big, small))
        await("uploads finished") { app.transfers.size == 2 && app.transfers.all { it.finished } }
        assertTrue("both uploads must succeed: ${app.transfers.map { it.status }}", app.transfers.all { it.status == "Done" })
        await("listing shows both files") { app.items.map { it.name }.toSet() == setOf("movie.bin", "notes.txt") }

        // save a copy
        val remoteBig = app.items.first { it.name == "movie.bin" }
        val saved = File(work, "saved.bin")
        app.download(remoteBig, saved)
        await("download finished") { app.transfers.first { it.name == "movie.bin" && !it.upload }.finished }
        assertTrue("saved file must equal the original", big.readBytes().contentEquals(saved.readBytes()))

        // open (cache) a file
        var opened: File? = null
        app.openInCache(app.items.first { it.name == "notes.txt" }) { opened = it }
        await("opened copy") { opened != null }
        assertEquals("hello from windows", opened!!.readText())

        // folders, rename, delete
        app.createFolder("Photos")
        await("folder appears") { app.items.any { it.name == "Photos" && it.isFolder } }
        app.rename(app.items.first { it.name == "notes.txt" }, "readme.txt")
        await("rename visible") { app.items.any { it.name == "readme.txt" } && app.items.none { it.name == "notes.txt" } }
        app.delete(app.items.first { it.name == "readme.txt" })
        await("delete visible") { app.items.none { it.name == "readme.txt" } }
        assertTrue(backend.files.values.none { it.name == "readme.txt" })

        // disconnect and reconnect: the pinned certificate means no code is asked for
        app.disconnect()
        assertTrue(app.conn is Conn.Idle)
        assertNotNull(app.pins.all().firstOrNull { it.id == hostId })
        assertEquals(null, app.connectByAddress("127.0.0.1:$port"))
        await("reconnected without a code") { app.conn is Conn.Connected }
        assertEquals("no second pairing prompt", 1, backend.pairingRequests)
        app.shutdown()
    }

    @Test
    fun pairedHostsSurviveARestart() {
        val first = DesktopApp(scope, dataDir)
        pairAndConnect(first)
        first.shutdown()

        // a new instance with the same data folder (as after closing and reopening the app) still trusts the host
        val second = DesktopApp(scope, dataDir)
        assertEquals(first.device.id, second.device.id)
        assertEquals(1, second.pins.all().size)
        second.connectSaved(second.pins.all().first())
        await("reconnected after restart") { second.conn is Conn.Connected }
        assertEquals(1, backend.pairingRequests)
        second.shutdown()
    }

    @Test
    fun decliningTheCodeLeavesNothingPaired() {
        val app = DesktopApp(scope, dataDir)
        app.connectByAddress("127.0.0.1:$port")
        await("pairing prompt") { app.conn is Conn.Pairing }
        app.confirmPairing(false)
        await("back to idle") { app.conn is Conn.Idle }
        assertTrue(app.pins.all().isEmpty())
        assertTrue(backend.peers.isEmpty())
    }

    @Test
    fun badAddressesAreRejectedWithoutConnecting() {
        val app = DesktopApp(scope, dataDir)
        assertNotNull(app.connectByAddress("not an address!"))
        assertNotNull(app.connectByAddress("1.2.3.4:99999"))
        assertTrue(app.conn is Conn.Idle)
    }

    @Test
    fun unreachableHostShowsAFriendlyError() {
        val app = DesktopApp(scope, dataDir)
        // nothing listens on this port
        app.connectByAddress("127.0.0.1:1")
        await("failure") { app.conn is Conn.Failed }
        assertTrue((app.conn as Conn.Failed).message.isNotBlank())
    }
}
