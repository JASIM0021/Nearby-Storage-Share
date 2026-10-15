package com.nearbyshare.core.network

import com.nearbyshare.core.network.lan.LanApprovals
import com.nearbyshare.core.network.lan.LanHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Hosting over both connection methods at once: Google Nearby (phone to phone) and the local network (phones and PCs on the
 * same Wi-Fi or hotspot). The rest of the app sees one host: one list of guests, one list of pairing prompts, one switch.
 */
class HostCoordinator(val nearby: NearbyHost, val lan: LanHost, scope: CoroutineScope) {
    val guests: StateFlow<List<GuestSession>> =
        combine(nearby.guests, lan.guests) { a, b -> a + b }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pending: StateFlow<List<PendingApproval>> =
        combine(nearby.pending, lan.pending) { a, b -> a + b }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** True while this phone is discoverable by either method. */
    val advertising: StateFlow<Boolean> =
        combine(nearby.advertising, lan.running) { a, b -> a || b }.stateIn(scope, SharingStarted.Eagerly, false)

    val error: StateFlow<String?> =
        combine(nearby.error, lan.error, lan.running) { n, l, lanUp -> if (lanUp) null else n ?: l }.stateIn(scope, SharingStarted.Eagerly, null)

    /** "ip:port" addresses on the local network, for connecting by hand. */
    val addresses: StateFlow<List<String>> = lan.addresses

    /** Starts the network method first (it needs no permissions), then Nearby. Sharing works if either one starts. */
    suspend fun start() {
        lan.start()
        try {
            nearby.start()
        } catch (e: Exception) {
            if (!lan.running.value) throw e // neither method could start
        }
    }

    fun stop() {
        nearby.stop()
        lan.stop()
    }

    fun resolvePairing(id: String, approve: Boolean) {
        if (id.startsWith(LanApprovals.KEY_PREFIX)) lan.resolvePairing(id, approve) else nearby.resolvePairing(id, approve)
    }

    fun disconnectDevice(deviceId: String) {
        nearby.disconnectDevice(deviceId)
        lan.disconnectDevice(deviceId)
    }
}
