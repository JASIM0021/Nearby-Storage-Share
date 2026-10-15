package com.nearbyshare.core.network

import android.net.Uri
import com.nearbyshare.core.network.lan.LanGuest
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.core.network.protocol.StorageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * One guest for the rest of the app: hosts found by Google Nearby and on the local network appear in one list, and a
 * connection made either way is used through the same functions. Only one host is connected at a time.
 */
class GuestFacade(private val nearby: NearbyGuest, private val lan: LanGuest, scope: CoroutineScope) : GuestTransport {

    /** The most "active" of the two states: connected beats pairing beats connecting beats an error beats idle. */
    val state: StateFlow<GuestState> =
        combine(nearby.state, lan.state) { a, b -> if (rank(b) > rank(a)) b else a }.stateIn(scope, SharingStarted.Eagerly, GuestState.Idle)

    val hosts: StateFlow<List<DiscoveredHost>> =
        combine(nearby.hosts, lan.hosts) { a, b -> a + b }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val discovering: StateFlow<Boolean> =
        combine(nearby.discovering, lan.discovering) { a, b -> a || b }.stateIn(scope, SharingStarted.Eagerly, false)

    val connectedHostId: String? get() = (state.value as? GuestState.Connected)?.hostId

    private fun rank(s: GuestState) = when (s) {
        is GuestState.Connected -> 4
        is GuestState.ConfirmPairing, is GuestState.Authenticating -> 3
        is GuestState.Connecting -> 2
        is GuestState.Failed -> 1
        is GuestState.Idle -> 0
    }

    /** Scans both ways. Nearby needs Bluetooth/location permissions; the network method needs none. */
    fun startDiscovery() {
        lan.startDiscovery()
        nearby.startDiscovery()
    }

    /** When Nearby permissions are missing the network scan still works. */
    fun startNetworkDiscovery() = lan.startDiscovery()

    fun stopDiscovery() {
        lan.stopDiscovery()
        nearby.stopDiscovery()
    }

    fun connect(host: DiscoveredHost) = if (host.isLan) lan.connect(host) else nearby.connect(host)

    fun connectByAddress(address: String, port: Int) = lan.connectByAddress(address, port)

    fun confirmPairing(accept: Boolean) {
        if (lan.state.value is GuestState.ConfirmPairing) lan.confirmPairing(accept) else nearby.confirmPairing(accept)
    }

    fun disconnect() {
        lan.disconnect()
        nearby.disconnect()
    }

    private val active: GuestTransport get() = if (lan.state.value is GuestState.Connected) lan else nearby

    override suspend fun storageInfo(): StorageInfo = active.storageInfo()
    override suspend fun list(parentId: String?): List<RemoteFile> = active.list(parentId)
    override suspend fun createFolder(name: String, parentId: String?): RemoteFile = active.createFolder(name, parentId)
    override suspend fun stat(fileId: String): RemoteFile = active.stat(fileId)
    override suspend fun rename(fileId: String, newName: String): RemoteFile = active.rename(fileId, newName)
    override suspend fun search(query: String): List<RemoteFile> = active.search(query)
    override suspend fun delete(fileId: String) = active.delete(fileId)
    override suspend fun cancelTransfer(transferId: String) = active.cancelTransfer(transferId)
    override suspend fun upload(uri: Uri, parentId: String?, name: String?): String? = active.upload(uri, parentId, name)
    override suspend fun download(file: RemoteFile, dest: Uri) = active.download(file, dest)
}
