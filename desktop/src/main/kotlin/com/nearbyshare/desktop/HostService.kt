package com.nearbyshare.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nearbyshare.host.HostEngine
import com.nearbyshare.lan.BeaconBroadcaster
import com.nearbyshare.lan.LanIdentity
import com.nearbyshare.lan.LanConstants
import com.nearbyshare.lan.LanGuestInfo
import com.nearbyshare.lan.LanHostServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Runs this PC as a storage host: the [HostEngine] (rules and storage), the network server, the discovery beacon, and the
 * Windows extras (keep the PC awake while sharing, start with Windows).
 */
class HostService(private val scope: CoroutineScope, dataDir: File, private val device: Device, identity: LanIdentity) {
    val engine = HostEngine(File(dataDir, "host"), scope)
    private val server = LanHostServer(lazyOf(identity), engine, device.id, { device.name }, scope)
    private val beacon = BeaconBroadcaster(scope, device.id, { device.name }, { server.port })
    private var awake: Process? = null

    val guests: StateFlow<List<LanGuestInfo>> = server.guests

    var running by mutableStateOf(false)
        private set
    var port by mutableStateOf(0)
        private set
    var addresses by mutableStateOf(emptyList<String>())
        private set
    var error by mutableStateOf<String?>(null)

    init {
        engine.onDeviceDropped = { server.disconnectDevice(it) }
        // a previous run may have ended with sharing "on": come back switched off until the user starts it again
        engine.setSharing(false)
        // A first run shares a normal folder you can open in File Explorer. Hidden storage is an advanced option.
        if (engine.config.value.visibleDir == null && engine.devices.value.isEmpty()) {
            runCatching {
                val dir = File(System.getProperty("user.home"), "Documents/Nearby Storage Share").apply { mkdirs() }
                engine.useVisibleFolder(dir.absolutePath)
            }
        }
    }

    fun start() {
        if (running) return
        try {
            port = server.start(LanConstants.DEFAULT_PORT)
        } catch (e: Throwable) {
            error = "Could not start sharing: ${e.message}"
            return
        }
        engine.setSharing(true)
        beacon.start()
        addresses = localAddresses().map { "$it:$port" }
        keepAwake(true)
        running = true
    }

    fun stop() {
        if (!running) return
        engine.setSharing(false)
        beacon.stop()
        server.stop()
        keepAwake(false)
        running = false
        addresses = emptyList()
    }

    /** Network changes while sharing (Wi-Fi switch, hotspot on): show the current addresses. */
    fun refreshAddresses() {
        if (running) addresses = localAddresses().map { "$it:$port" }
    }

    fun shutdown() {
        stop()
        engine.close()
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLinkLocalAddress }
            .map { it.hostAddress }
    }.getOrDefault(emptyList())

    /** While sharing, a sleeping PC would drop every guest. A tiny helper process holds the "stay awake" request and ends with us. */
    private fun keepAwake(on: Boolean) {
        awake?.destroy()
        awake = null
        if (!on || !System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val script = "Add-Type -Name P -Namespace W -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);';" +
            "while (\$true) { [W.P]::SetThreadExecutionState(0x80000001) | Out-Null; Start-Sleep -Seconds 30 }"
        awake = runCatching {
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script).redirectErrorStream(true).start()
        }.getOrNull()
    }

    companion object {
        private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
        private const val RUN_NAME = "NearbyStorageShare"

        fun autostartEnabled(): Boolean = runCatching {
            ProcessBuilder("reg", "query", RUN_KEY, "/v", RUN_NAME).redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

        /** Starts the app (minimised to the tray) when the user signs in to Windows. */
        fun setAutostart(on: Boolean) {
            runCatching {
                if (on) {
                    val exe = ProcessHandle.current().info().command().orElse(null) ?: return
                    ProcessBuilder("reg", "add", RUN_KEY, "/v", RUN_NAME, "/t", "REG_SZ", "/d", "\"$exe\" --tray", "/f").start().waitFor()
                } else {
                    ProcessBuilder("reg", "delete", RUN_KEY, "/v", RUN_NAME, "/f").start().waitFor()
                }
            }
        }
    }
}
