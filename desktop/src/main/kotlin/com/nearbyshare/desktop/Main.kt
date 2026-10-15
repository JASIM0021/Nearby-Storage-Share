package com.nearbyshare.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main(args: Array<String>) = application {
    val app = remember { DesktopApp(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    // "--tray" = started by Windows at sign-in: stay out of the way until the user opens it
    var visible by remember { mutableStateOf("--tray" !in args) }
    val trayState = rememberTrayState()
    val pending by app.host.engine.pending.collectAsState()

    // a request to connect must not go unnoticed while the window is hidden in the tray
    LaunchedEffect(pending.firstOrNull()?.key) {
        pending.firstOrNull()?.let {
            trayState.sendNotification(Notification("Nearby Storage Share", "${it.name} wants to connect. Open the app to allow or deny.", Notification.Type.Info))
        }
    }

    fun quit() {
        app.shutdown()
        exitApplication()
    }

    Tray(
        icon = painterResource("icon.png"),
        state = trayState,
        tooltip = if (app.host.running) "Nearby Storage Share: sharing is on" else "Nearby Storage Share",
        onAction = { visible = true },
    ) {
        Item("Open") { visible = true }
        if (app.host.running) Item("Stop sharing") { app.host.stop() } else Item("Start sharing") { app.host.start() }
        Item("Quit") { quit() }
    }

    Window(
        visible = visible,
        onCloseRequest = {
            // while sharing, closing the window keeps the host running in the tray
            if (app.host.running) {
                visible = false
                trayState.sendNotification(Notification("Still sharing", "Nearby Storage Share keeps running in the tray. Use the tray icon to open or quit.", Notification.Type.Info))
            } else {
                quit()
            }
        },
        title = "Nearby Storage Share",
        icon = painterResource("icon.png"),
        state = rememberWindowState(width = 1080.dp, height = 760.dp),
    ) {
        NearbyTheme { AppRoot(app) }
    }
}
