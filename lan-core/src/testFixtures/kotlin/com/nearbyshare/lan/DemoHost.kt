package com.nearbyshare.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/**
 * A stand-in host for trying a guest app by hand: `gradlew :lan-core:demoHost`. It announces itself on the network, approves every
 * pairing (printing the code so you can compare it with the guest's screen) and keeps files in memory. Not part of any shipped app.
 */
fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val backend = FakeBackend().apply { quota = 5L * 1024 * 1024 * 1024 }
    val hostId = UUID.randomUUID().toString()
    val server = LanHostServer(lazyOf(JvmIdentity.generateInMemory("demo-host")), backend, hostId, { "Demo Phone" }, scope)
    val port = server.start(LanConstants.DEFAULT_PORT)
    BeaconBroadcaster(scope, hostId, { "Demo Phone" }, { port }).start()
    println("DEMO HOST listening on port $port as \"Demo Phone\"")
    System.out.flush()
    while (true) {
        Thread.sleep(500)
        backend.lastSas?.let {
            println("PAIRING CODE: $it")
            System.out.flush()
            backend.lastSas = null
        }
    }
}
