package com.nearbyshare.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/** A host found on the local network. */
data class LanHostInfo(val id: String, val name: String, val address: String, val port: Int)

object Beacon {
    fun encode(id: String, name: String, port: Int): ByteArray =
        JSONObject().put("app", "nss").put("v", 1).put("id", id).put("name", name).put("port", port).toString().toByteArray(Charsets.UTF_8)

    /** Returns null for anything that is not a well-formed beacon from this app. */
    fun decode(data: ByteArray, length: Int, senderAddress: String): LanHostInfo? = runCatching {
        val o = JSONObject(String(data, 0, length, Charsets.UTF_8))
        if (o.getString("app") != "nss" || o.getInt("v") != 1) return null
        val id = o.getString("id")
        val port = o.getInt("port")
        if (!isUuid(id) || port !in 1..65535) return null
        LanHostInfo(id, sanitizeDeviceName(o.getString("name")), senderAddress, port)
    }.getOrNull()
}

/**
 * Announces this host every couple of seconds on every network interface (a hotspot or a Wi-Fi network), so guests find it
 * without typing an address. Beacons only carry the host's name, random id and port - never any file information.
 */
class BeaconBroadcaster(
    private val scope: CoroutineScope,
    private val id: String,
    private val name: () -> String,
    private val port: () -> Int,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            val socket = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return@launch
            try {
                while (isActive) {
                    val payload = Beacon.encode(id, name(), port())
                    for (target in broadcastTargets()) {
                        runCatching { socket.send(DatagramPacket(payload, payload.size, target, LanConstants.DISCOVERY_PORT)) }
                    }
                    delay(INTERVAL_MS)
                }
            } finally {
                socket.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Each interface's own broadcast address (a plain 255.255.255.255 only leaves through the default route). */
    private fun broadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching {
            for (ifc in NetworkInterface.getNetworkInterfaces()) {
                if (!ifc.isUp || ifc.isLoopback) continue
                ifc.interfaceAddresses.mapNotNull { it.broadcast }.forEach { targets += it }
            }
        }
        runCatching { targets += InetAddress.getByName("255.255.255.255") }
        return targets.toList()
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
    }
}

/** Listens for host beacons and keeps a live list; hosts that stop announcing drop off after a few seconds. */
class BeaconListener(private val scope: CoroutineScope, private val selfId: String? = null) {
    private class Seen(val info: LanHostInfo, val at: Long)

    private val seen = ConcurrentHashMap<String, Seen>()
    private val _hosts = MutableStateFlow<List<LanHostInfo>>(emptyList())
    val hosts: StateFlow<List<LanHostInfo>> = _hosts.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        seen.clear()
        job = scope.launch(Dispatchers.IO) {
            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1_000
                    bind(InetSocketAddress(LanConstants.DISCOVERY_PORT))
                }
            }.getOrNull()
            if (socket == null) return@launch // port in use or blocked: connect-by-address still works
            val buf = ByteArray(2048)
            try {
                while (isActive) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        val info = Beacon.decode(p.data, p.length, p.address.hostAddress ?: continue)
                        if (info != null && info.id != selfId) seen[info.id] = Seen(info, System.currentTimeMillis())
                    } catch (_: java.net.SocketTimeoutException) {
                        // fall through to pruning
                    }
                    val now = System.currentTimeMillis()
                    seen.values.removeAll { now - it.at > EXPIRY_MS }
                    _hosts.value = seen.values.map { it.info }.sortedBy { it.name.lowercase() }
                }
            } finally {
                socket.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        seen.clear()
        _hosts.value = emptyList()
    }

    private companion object {
        const val EXPIRY_MS = 8_000L
    }
}
