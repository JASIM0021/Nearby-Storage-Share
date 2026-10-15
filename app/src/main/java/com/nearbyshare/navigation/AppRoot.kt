package com.nearbyshare.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import com.nearbyshare.ui.theme.ElectricBlue
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nearbyshare.core.network.GuestState
import com.nearbyshare.feature.GuestViewModel
import com.nearbyshare.feature.HostViewModel
import com.nearbyshare.feature.access_control.AccessScreen
import com.nearbyshare.feature.access_control.DeviceDetailScreen
import com.nearbyshare.ui.theme.LocalWidthClass
import com.nearbyshare.ui.theme.WidthClass
import androidx.compose.runtime.CompositionLocalProvider
import com.nearbyshare.feature.home.HomeScreen
import com.nearbyshare.feature.nearby.NearbyScreen
import com.nearbyshare.feature.remote_files.FilesScreen
import com.nearbyshare.feature.settings.SettingsScreen
import com.nearbyshare.feature.transfers.TransfersScreen
import com.nearbyshare.ui.components.CodeBox
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Rounded.Home),
    Nearby("nearby", "Nearby", Icons.Rounded.WifiTethering),
    Files("files", "Files", Icons.Rounded.FolderOpen),
    Transfers("transfers", "Transfers", Icons.Rounded.SwapVert),
    Settings("settings", "Settings", Icons.Rounded.Settings),
}

private val TabGlassBlue = Color(0xFF2B6BFF)
private val TabSelectedInk = Color(0xFFD6E6FF)

/** Frosted bottom bar; the selected tab is a glowing blue rounded square (see the design reference). */
@Composable
private fun GlassTabBar(isSelected: (Tab) -> Boolean, onSelect: (Tab) -> Unit) {
    // A very large system font would overflow five tabs on any phone, so the bar caps its own font scale.
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = d.fontScale.coerceAtMost(1.1f))) {
        GlassTabBarContent(isSelected, onSelect)
    }
}

@Composable
private fun GlassTabBarContent(isSelected: (Tab) -> Boolean, onSelect: (Tab) -> Unit) {
    val c = MaterialTheme.colorScheme
    // Narrow phones get smaller outer margins so the five tabs have room.
    val margin = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 380) 8.dp else 14.dp
    val shape = RoundedCornerShape(30.dp)
    Row(
        // floats above the content with a margin on every side, like the reference
        Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = margin, vertical = 8.dp)
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = ElectricBlue.copy(alpha = 0.4f), spotColor = ElectricBlue.copy(alpha = 0.4f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.surfaceVariant.copy(alpha = 0.96f), c.surface.copy(alpha = 0.98f))))
            .border(1.dp, c.outline.copy(alpha = 0.7f), shape)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            val selected = isSelected(tab)
            // The selected tab is a little wider, which gives the longest labels ("Transfers") room inside the glow.
            val weight by animateFloatAsState(if (selected) 1.3f else 1f, tween(260), label = "tabWeight")
            GlassTabItem(tab, selected, { onSelect(tab) }, Modifier.weight(weight))
        }
    }
}

/** Same look for wide screens: a vertical frosted rail down the left edge. */
@Composable
private fun GlassTabRail(isSelected: (Tab) -> Boolean, onSelect: (Tab) -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
    Column(
        Modifier.fillMaxHeight().width(92.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(c.surface.copy(alpha = 0.94f), c.background)))
            .border(1.dp, c.outline.copy(alpha = 0.5f), shape)
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Tab.entries.forEach { tab -> GlassTabItem(tab, isSelected(tab), { onSelect(tab) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun GlassTabItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    val c = MaterialTheme.colorScheme
    val glow by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "tabGlow")
    val ink = if (selected) TabSelectedInk else c.onSurfaceVariant
    Box(
        modifier.padding(horizontal = 3.dp).height(62.dp)
            .then(if (selected) Modifier.shadow(18.dp, shape, ambientColor = ElectricBlue, spotColor = ElectricBlue) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(TabGlassBlue.copy(alpha = 0.55f * glow), Color(0xFF0B2A6B).copy(alpha = 0.6f * glow))))
            .border(1.5.dp, Brush.verticalGradient(listOf(Color(0xFF8FC0FF).copy(alpha = glow), ElectricBlue.copy(alpha = glow))), shape)
            .selectable(selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Shrink-to-fit: the label never touches the border, whatever the screen width or label length.
        var labelSize by remember(tab) { mutableStateOf(13f) }
        Column(
            Modifier.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(tab.icon, null, tint = ink)
            Text(
                tab.label, fontSize = labelSize.sp, softWrap = false, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                onTextLayout = { if (it.hasVisualOverflow && labelSize > 8.5f) labelSize -= 0.5f },
                color = if (selected) Color.White else ink, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

/** Below this width the app uses a bottom bar; at or above it (tablets, foldables, landscape) a side rail. */
private val RAIL_BREAKPOINT = 600.dp
private val CONTENT_MAX_WIDTH = 760.dp

@Composable
fun AppRoot(host: HostViewModel = viewModel(), guest: GuestViewModel = viewModel()) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    LaunchedEffect(Unit) { guest.events.collect { notify(it) } }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val navigate: (String) -> Unit = { route ->
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun selected(tab: Tab) = current == tab.route || (tab == Tab.Settings && (current == "access" || current?.startsWith("device/") == true))

    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.background, scheme.primaryContainer.copy(alpha = 0.22f))))
            // soft blue glow behind the header, as in the design
            .background(Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.16f), Color.Transparent), center = Offset(540f, 120f), radius = 900f)),
    ) {
        val wide = maxWidth >= RAIL_BREAKPOINT
        val widthClass = when {
            maxWidth >= 840.dp -> WidthClass.Expanded
            wide -> WidthClass.Medium
            else -> WidthClass.Compact
        }
        CompositionLocalProvider(LocalWidthClass provides widthClass) {
        Scaffold(
            containerColor = Color.Transparent,
            // A transparent container has no matching "on" colour, so state it: otherwise text defaults to black.
            contentColor = scheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide) GlassTabBar(::selected) { navigate(it.route) }
            },
        ) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (wide) GlassTabRail(::selected) { navigate(it.route) }
                // Centre the content and cap its width so lists and cards stay readable on large screens.
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    val maxContent = if (widthClass == WidthClass.Expanded) 1180.dp else CONTENT_MAX_WIDTH
                    NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.widthIn(max = maxContent).fillMaxHeight()) {
                        composable("home") { HomeScreen(host, guest, navigate, notify) }
                        composable("nearby") { Readable { NearbyScreen(guest, navigate, notify) } }
                        composable("files") { Readable { FilesScreen(guest, navigate) } }
                        composable("transfers") { Readable { TransfersScreen(guest) } }
                        composable("settings") { SettingsScreen(host, navigate, notify) }
                        composable("received") { com.nearbyshare.feature.received.ReceivedFilesScreen(host, notify) }
                        composable("access") { AccessScreen(host, navigate = { nav.navigate(it) }) }
                        composable("device/{id}") { entry ->
                            DeviceDetailScreen(entry.arguments?.getString("id").orEmpty(), host)
                        }
                    }
                }
            }
        }
        }
    }

    PairingDialogs(host, guest)
}

/** Single-list screens stay at a comfortable reading width even when the window is very wide. */
@Composable
private fun Readable(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxHeight()) { content() }
    }
}

/** Pairing prompts are global: they must appear whichever tab is open. */
@Composable
private fun PairingDialogs(host: HostViewModel, guest: GuestViewModel) {
    val pending by host.pending.collectAsStateWithLifecycle()
    val guestState by guest.state.collectAsStateWithLifecycle()

    pending.firstOrNull()?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (p.rePair) "Re-pair ${p.name}?" else "Allow ${p.name}?") },
            text = {
                Column {
                    Text(
                        if (p.rePair) "This phone was approved before but lost its key. Confirming gives it a new key and keeps its files and permissions. Check that this code is the same on the other phone:"
                        else "This phone wants to store files here. Check that this code is the same on the other phone:",
                    )
                    CodeBox(p.digits)
                    Text(
                        "The other phone must also tap \"Yes, codes match\". New devices get your default permissions, which you can change in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton({ host.resolvePairing(p.endpointId, true) }) { Text("Allow") } },
            dismissButton = { TextButton({ host.resolvePairing(p.endpointId, false) }) { Text("Deny") } },
        )
    }

    (guestState as? GuestState.ConfirmPairing)?.let { s ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Pair with ${s.hostName}") },
            text = {
                Column {
                    Text("Check that this code is the same on the host's screen:")
                    CodeBox(s.digits)
                    if (s.accepted) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 3.dp)
                            Text("Waiting for ${s.hostName} to tap Allow...", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text("Both phones must confirm. Tap the button below, then tap Allow on the host.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (!s.accepted) TextButton({ guest.confirmPairing(true) }) { Text("Yes, codes match") }
            },
            dismissButton = { TextButton({ if (s.accepted) guest.disconnect() else guest.confirmPairing(false) }) { Text("Cancel") } },
        )
    }
}
