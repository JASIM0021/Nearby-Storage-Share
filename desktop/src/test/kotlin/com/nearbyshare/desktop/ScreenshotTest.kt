package com.nearbyshare.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.nearbyshare.lan.FakeBackend
import com.nearbyshare.lan.JvmIdentity
import com.nearbyshare.lan.LanHostServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.foundation.layout.fillMaxSize
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Renders the real screens off-screen (no window, nothing on the user's desktop) to PNG files in build/screenshots, so the
 * look of each screen can be checked. The test only asserts that something non-blank was drawn.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenshotTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(25)
        }
    }

    private fun render(name: String, app: DesktopApp, width: Int = 1100, height: Int = 780) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) { NearbyTheme { AppRoot(app) } }
        try {
            scene.render(0L)
            scene.render(500_000_000L) // second frame so animations and layout settle
            val image = scene.render(1_000_000_000L)
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(png)
            assertTrue("screen $name drew something", png.size > 5_000)
        } finally {
            scene.close()
        }
    }

    @Test
    fun rendersTheMainScreens() {
        val backend = FakeBackend()
        val server = LanHostServer(lazyOf(JvmIdentity.generateInMemory("shot-host")), backend, UUID.randomUUID().toString(), { "Ravi's Phone" }, scope)
        val port = server.start(0)
        val dataDir = Files.createTempDirectory("nss-shot").toFile()
        val work = Files.createTempDirectory("nss-shot-work").toFile()
        try {
            val app = DesktopApp(scope, dataDir)
            render("1-hosts-screen", app)

            app.connectByAddress("127.0.0.1:$port")
            await("pairing prompt") { app.conn is Conn.Pairing }
            render("2-pairing-code", app)

            app.confirmPairing(true)
            await("connected") { app.conn is Conn.Connected }
            await("info") { app.info != null }

            app.createFolder("Holiday photos")
            File(work, "Quarterly report.pdf").writeBytes(ByteArray(1_800_000) { 7 })
            File(work, "family.jpg").writeBytes(ByteArray(640_000) { 3 })
            File(work, "notes.txt").writeText("remember the milk")
            app.upload(work.listFiles()!!.sortedBy { it.name }.toList())
            await("uploads") { app.transfers.size == 3 && app.transfers.all { it.finished } }
            await("listing") { app.items.size == 4 }
            render("3-file-browser", app)
            app.shutdown()
        } finally {
            server.stop()
            scope.cancel()
            dataDir.deleteRecursively()
            work.deleteRecursively()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
class HostScreenshotTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun rendersTheHostScreen() {
        val host = DesktopApp(scope, Files.createTempDirectory("nss-shot-h").toFile())
        val guest = DesktopApp(scope, Files.createTempDirectory("nss-shot-g").toFile())
        try {
            host.host.engine.updateConfig { it.copy(accessMode = com.nearbyshare.host.AccessMode.OPEN, safetyReserveBytes = 0) }
            host.host.start()
            guest.connectByAddress("127.0.0.1:${host.host.port}")
            val end = System.currentTimeMillis() + 20_000
            while (guest.conn !is Conn.Pairing && System.currentTimeMillis() < end) Thread.sleep(25)
            guest.confirmPairing(true)
            while (guest.conn !is Conn.Connected && System.currentTimeMillis() < end) Thread.sleep(25)
            val f = File.createTempFile("share", ".bin").apply { writeBytes(ByteArray(3_000_000) { 1 }); deleteOnExit() }
            guest.upload(listOf(f))
            while (host.host.engine.usedBytes.value == 0L && System.currentTimeMillis() < end) Thread.sleep(25)

            val scene = ImageComposeScene(width = 1100, height = 1900, density = Density(1f)) {
                NearbyTheme {
                    androidx.compose.material3.Surface(
                        androidx.compose.ui.Modifier.fillMaxSizeCompat(),
                        color = androidx.compose.ui.graphics.Color(0xFF07112A), contentColor = androidx.compose.ui.graphics.Color.White,
                    ) { HostScreen(host) }
                }
            }
            try {
                scene.render(0L); scene.render(500_000_000L)
                val png = scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File("build/screenshots").apply { mkdirs() }.resolve("4-host-screen.png").writeBytes(png)
                assertTrue(png.size > 5_000)
            } finally {
                scene.close()
            }
        } finally {
            guest.shutdown(); host.shutdown(); scope.cancel()
        }
    }
}

private fun androidx.compose.ui.Modifier.fillMaxSizeCompat(): androidx.compose.ui.Modifier = this.then(androidx.compose.ui.Modifier.fillMaxSize())
