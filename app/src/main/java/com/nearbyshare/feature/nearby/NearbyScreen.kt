package com.nearbyshare.feature.nearby

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearbyshare.core.network.GuestState
import com.nearbyshare.feature.GuestViewModel
import com.nearbyshare.ui.components.Avatar
import com.nearbyshare.ui.components.IconTile
import com.nearbyshare.ui.components.Pill
import com.nearbyshare.ui.components.PrimaryButton
import com.nearbyshare.ui.components.ScreenTitle
import com.nearbyshare.ui.components.SectionCard
import com.nearbyshare.ui.components.rememberNearbyPermissionGate
import com.nearbyshare.ui.theme.ElectricBlue
import com.nearbyshare.ui.theme.Mint
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NearbyScreen(guest: GuestViewModel, navigate: (String) -> Unit, notify: (String) -> Unit) {
    val state by guest.state.collectAsStateWithLifecycle()
    val hosts by guest.hosts.collectAsStateWithLifecycle()
    val scanning by guest.discovering.collectAsStateWithLifecycle()

    var showAddress by remember { mutableStateOf(false) }

    val startScan = rememberNearbyPermissionGate(
        onGranted = { guest.startScan() },
        onDenied = {
            guest.startNetworkScan()
            notify("Bluetooth/location permission was refused, so only devices on the same Wi-Fi are shown")
        },
    )

    LaunchedEffect(state) { if (state is GuestState.Connected) guest.stopScan() }
    DisposableEffect(Unit) { onDispose { guest.stopScan() } }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Nearby devices", "Find a phone that is sharing storage")

        when (val s = state) {
            is GuestState.Connected -> SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(s.hostName, size = 52.dp)
                    Column(Modifier.weight(1f)) {
                        Text(s.hostName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Pill("Connected", Mint, live = true)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Browse files", { navigate("files") }, Modifier.weight(1f))
                    OutlinedButton({ guest.disconnect() }, Modifier.weight(1f).height(52.dp)) { Text("Disconnect") }
                }
            }
            is GuestState.Connecting, is GuestState.Authenticating, is GuestState.ConfirmPairing -> SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(
                        when (s) {
                            is GuestState.Authenticating -> "Verifying ${s.hostName}..."
                            is GuestState.Connecting -> "Connecting to ${s.hostName}..."
                            else -> "Waiting for pairing confirmation"
                        },
                        Modifier.weight(1f),
                    )
                    OutlinedButton({ guest.disconnect() }) { Text("Cancel") }
                }
            }
            else -> {
                if (s is GuestState.Failed) {
                    SectionCard { Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
                }

                // Scan control
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(Icons.Rounded.Radar, ElectricBlue, size = 48.dp)
                        Column(Modifier.weight(1f)) {
                            Text(if (scanning) "Scanning..." else "Not scanning", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (scanning) "Looking for phones that are sharing" else "Tap the button to search for nearby devices",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (scanning) {
                        OutlinedButton({ guest.stopScan() }, Modifier.fillMaxWidth().height(52.dp)) { Text("Stop scanning") }
                    } else {
                        Button({ startScan() }, Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Rounded.Radar, null, Modifier.padding(end = 8.dp))
                            Text("Scan for devices", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                OutlinedButton({ showAddress = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Connect by address (PC or other device)", textAlign = androidx.compose.ui.text.style.TextAlign.Center) }

                // Radar with discovered hosts as blips
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RadarView(scanning, hosts.size, Modifier.size(minOf(maxWidth, 320.dp)))
                }

                if (hosts.isEmpty()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (scanning) "Looking for hosts" else "No devices found yet",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        )
                        Text(
                            "Scan nearby to discover phones sharing storage. Keep Bluetooth and Wi-Fi enabled on both devices.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    hosts.forEach { h ->
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Avatar(h.name, size = 48.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(h.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        if (h.isLan) "Wi-Fi network - ${h.address}" else "Nearby (Bluetooth / Wi-Fi Direct)",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button({ guest.connect(h) }) { Text("Connect") }
                            }
                        }
                    }
                }

                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(Icons.Rounded.Lock, Mint, size = 46.dp)
                        Column {
                            Text("Secure by design", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "You approve access before connecting.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showAddress) {
        ConnectByAddressDialog({ showAddress = false }) { guest.connectByAddress(it) }
    }
}

/** For networks that hide devices from each other, or a PC: type the address the host shows. */
@Composable
private fun ConnectByAddressDialog(onDismiss: () -> Unit, onConnect: (String) -> String?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect by address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Type the address shown on the host (for example on its Home screen): 192.168.1.20:45820",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    text, { text = it; error = null }, singleLine = true, label = { Text("Address") },
                    isError = error != null, supportingText = { error?.let { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton({
                val problem = onConnect(text)
                if (problem == null) onDismiss() else error = problem
            }, enabled = text.isNotBlank()) { Text("Connect") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Concentric rings, a rotating sweep while scanning, a phone in the middle and one blip per discovered host. */
@Composable
private fun RadarView(scanning: Boolean, hostCount: Int, modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition(label = "radar")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val pulse by spin.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "blip")
    val ring = ElectricBlue.copy(alpha = 0.35f)
    val glow = ElectricBlue

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.16f), androidx.compose.ui.graphics.Color.Transparent), c, r), r, c)
            listOf(1f, 0.75f, 0.5f, 0.25f).forEach { f -> drawCircle(ring, r * f - 1.dp.toPx(), c, style = Stroke(1.2.dp.toPx())) }
            drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
            drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
            if (scanning) {
                rotate(angle, c) {
                    drawArc(
                        Brush.sweepGradient(
                            0f to androidx.compose.ui.graphics.Color.Transparent, 0.82f to androidx.compose.ui.graphics.Color.Transparent,
                            1f to glow.copy(alpha = 0.55f), center = c,
                        ),
                        0f, 360f, true, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    )
                }
            }
            // one blip per discovered host, spread around the rings
            repeat(hostCount.coerceAtMost(8)) { i ->
                val a = Math.toRadians(40.0 + i * 137.0)
                val d = r * (0.35f + 0.12f * (i % 4))
                val p = Offset(c.x + (d * cos(a)).toFloat(), c.y + (d * sin(a)).toFloat())
                drawCircle(Mint.copy(alpha = 0.3f * pulse), 11.dp.toPx(), p)
                drawCircle(Mint, 5.dp.toPx(), p)
            }
        }
        Box(
            Modifier.size(64.dp).clip(CircleShape).then(Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.matchParentSize().clip(CircleShape).padding(0.dp)) {
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(androidx.compose.ui.graphics.Color(0xFF0B1B3F))
                    drawCircle(glow.copy(alpha = 0.8f), size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                }
            }
            Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(30.dp), tint = ElectricBlue)
        }
    }
}
