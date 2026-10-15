package com.nearbyshare.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() = application {
    val app = remember { DesktopApp(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    Window(
        onCloseRequest = {
            app.shutdown()
            exitApplication()
        },
        title = "Nearby Storage Share",
        icon = painterResource("icon.png"),
        state = rememberWindowState(width = 1080.dp, height = 760.dp),
    ) {
        NearbyTheme { AppRoot(app) }
    }
}
