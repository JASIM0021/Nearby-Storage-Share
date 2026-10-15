package com.nearbyshare.desktop

import com.nearbyshare.host.AccessMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** One PC hosts (the new Windows host) and another PC uses it (the Windows guest), both the real app logic over real TLS. */
class PcToPcTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var hostApp: DesktopApp
    private lateinit var guestApp: DesktopApp
    private lateinit var work: File

    @Before
    fun setUp() {
        hostApp = DesktopApp(scope, Files.createTempDirectory("nss-host").toFile())
        guestApp = DesktopApp(scope, Files.createTempDirectory("nss-guest").toFile())
        work = Files.createTempDirectory("nss-work").toFile()
    }

    @After
    fun tearDown() {
        guestApp.disconnect()
        hostApp.shutdown()
        scope.cancel()
    }

    private fun await(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(25)
        }
    }

    @Test
    fun guestPairsWithApprovalUploadsAndHostSeesTheDevice() {
        val engine = hostApp.host.engine
        engine.updateConfig { it.copy(accessMode = AccessMode.APPROVAL, safetyReserveBytes = 0) }
        hostApp.host.start()
        assertTrue(hostApp.host.running)
        assertEquals(null, guestApp.connectByAddress("127.0.0.1:${hostApp.host.port}"))

        await("guest shows a code") { guestApp.conn is Conn.Pairing }
        await("host shows the same code") { engine.pending.value.isNotEmpty() }
        assertEquals(engine.pending.value.first().code, (guestApp.conn as Conn.Pairing).code)
        guestApp.confirmPairing(true)
        engine.resolvePairing(engine.pending.value.first().key, true)
        await("connected") { guestApp.conn is Conn.Connected }
        await("storage info") { guestApp.info != null }

        val f = File(work, "hello.txt").apply { writeText("from another PC") }
        guestApp.upload(listOf(f))
        await("upload") { guestApp.transfers.isNotEmpty() && guestApp.transfers.all { it.finished } }
        assertEquals("Done", guestApp.transfers[0].status)
        await("listed") { guestApp.items.map { it.name } == listOf("hello.txt") }
        assertEquals(1, engine.devices.value.size)
        assertEquals(15L, engine.usedBytes.value)

        // revoke on the host: the guest is dropped
        engine.revoke(engine.devices.value.first().device.deviceId)
        await("guest dropped") { guestApp.conn is Conn.Failed || guestApp.conn is Conn.Idle }

        // search and rename still compile into the guest API
        hostApp.host.stop()
        assertTrue(!hostApp.host.running)
    }
}
