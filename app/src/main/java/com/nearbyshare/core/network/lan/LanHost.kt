package com.nearbyshare.core.network.lan

import android.content.Context
import android.util.Log
import com.nearbyshare.core.common.TransferProgress
import com.nearbyshare.core.database.AppDatabase
import com.nearbyshare.core.network.GuestSession
import com.nearbyshare.core.network.PendingApproval
import com.nearbyshare.core.security.DeviceIdentity
import com.nearbyshare.domain.HostFileService
import com.nearbyshare.lan.BeaconBroadcaster
import com.nearbyshare.lan.LanHostServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import java.net.Inet4Address
import java.net.NetworkInterface

/** Hosting over the local network (Wi-Fi or a hotspot): a TLS server plus a beacon so guests find this phone. */
class LanHost(
    context: Context,
    db: AppDatabase,
    files: HostFileService,
    private val identity: DeviceIdentity,
    progress: TransferProgress,
    private val scope: CoroutineScope,
) {
    private val approvals = LanApprovals()
    private val backend = AndroidHostBackend(context.applicationContext, db, files, progress, approvals)

    // The Keystore key is created the first time hosting starts, not at app start.
    private val server = LanHostServer(lazy { AndroidLanIdentity.get() }, backend, identity.deviceId, { identity.displayName }, scope)
    private val beacons = BeaconBroadcaster(scope, identity.deviceId, { identity.displayName }, { server.port })

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _addresses = MutableStateFlow<List<String>>(emptyList())

    /** "ip:port" this phone can be reached on, for connecting by hand (for example from a PC). */
    val addresses: StateFlow<List<String>> = _addresses.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val pending: StateFlow<List<PendingApproval>> = approvals.pending

    val guests: StateFlow<List<GuestSession>> =
        server.guests.map { list -> list.map { GuestSession(LanApprovals.KEY_PREFIX + it.connId, it.deviceId ?: "", it.name, it.authenticated) } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun start() {
        if (_running.value) return
        try {
            val port = server.start()
            beacons.start()
            _running.value = true
            _error.value = null
            _addresses.value = localAddresses().map { "$it:$port" }
        } catch (e: Exception) {
            Log.w(TAG, "LAN hosting failed to start", e)
            _error.value = "Network sharing unavailable: ${e.message}"
            runCatching { server.stop() }
        }
    }

    fun stop() {
        if (!_running.value) return
        beacons.stop()
        server.stop()
        _running.value = false
        _addresses.value = emptyList()
    }

    fun resolvePairing(key: String, approve: Boolean) = approvals.resolve(key, approve)

    fun disconnectDevice(deviceId: String) {
        if (_running.value) server.disconnectDevice(deviceId)
    }

    /** Recompute after the phone switched networks. */
    fun refreshAddresses() {
        if (_running.value) _addresses.value = localAddresses().map { "$it:${server.port}" }
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "LanHost"
    }
}
