package com.nearbyshare.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearbyshare.core.common.formatBytes
import com.nearbyshare.core.database.StorageMode
import com.nearbyshare.core.database.Transfer
import com.nearbyshare.core.database.TransferDirection
import com.nearbyshare.core.database.TransferState
import com.nearbyshare.feature.GuestViewModel
import com.nearbyshare.feature.HostViewModel
import com.nearbyshare.ui.components.AdaptiveColumns
import com.nearbyshare.ui.components.Avatar
import com.nearbyshare.ui.components.GlowIconButton
import com.nearbyshare.ui.components.HeroCard
import com.nearbyshare.ui.components.IconStat
import com.nearbyshare.ui.components.IconTile
import com.nearbyshare.ui.components.Pill
import com.nearbyshare.ui.components.SectionCard
import com.nearbyshare.ui.components.StorageRing
import com.nearbyshare.ui.components.rememberNearbyPermissionGate
import com.nearbyshare.ui.theme.Amber
import com.nearbyshare.ui.theme.ElectricBlue
import com.nearbyshare.ui.theme.Mint

private val Violet = Color(0xFFB388FF)

@Composable
fun HomeScreen(host: HostViewModel, guest: GuestViewModel, navigate: (String) -> Unit, notify: (String) -> Unit) {
    val cfg by host.config.collectAsStateWithLifecycle()
    val used by host.usedBytes.collectAsStateWithLifecycle()
    val storage by host.storage.collectAsStateWithLifecycle()
    val guests by host.guests.collectAsStateWithLifecycle()
    val pending by host.pending.collectAsStateWithLifecycle()
    val advertising by host.advertising.collectAsStateWithLifecycle()
    val hostError by host.hostError.collectAsStateWithLifecycle()
    val addresses by host.addresses.collectAsStateWithLifecycle()
    val folderName by host.visibleFolderName.collectAsStateWithLifecycle()
    val transfers by guest.transfers.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        host.refreshStorage()
        host.refreshAddresses()
    }

    val askPermissions = rememberNearbyPermissionGate(
        onGranted = { host.setSharing(true) },
        onDenied = { notify("Nearby permissions are needed to share storage") },
    )

    // First time sharing: ask once for a normal folder, so guest files can be browsed in the file manager.
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) notify("Choose a folder to share, or pick Hidden storage in Settings")
        else host.useVisibleFolder(uri) { err -> if (err != null) notify(err) else askPermissions() }
    }
    val needsFolder = cfg?.let { it.storageMode == StorageMode.PRIVATE && it.visibleTreeUri == null } == true && used == 0L

    val enabled = cfg?.sharingEnabled == true
    val locationLabel = if (cfg?.storageMode == StorageMode.VISIBLE) folderName ?: "Visible folder" else "Internal storage"

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header: avatar, title, quick buttons
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(host.deviceName, size = 52.dp)
            Column(Modifier.weight(1f)) {
                Text("Nearby Storage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(host.deviceName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            GlowIconButton(Icons.Rounded.Notifications, "Devices and activity", { navigate("access") }, badge = pending.isNotEmpty() || guests.isNotEmpty())
            GlowIconButton(Icons.Rounded.Settings, "Settings", { navigate("settings") })
        }

        AdaptiveColumns(
            left = {
                HeroCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Storage, null, tint = Mint, modifier = Modifier.size(20.dp))
                            Text("SHARED STORAGE", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp)
                        }
                        Pill(locationLabel, ElectricBlue)
                    }
                    // The ring scales with the card so it fits small phones and still looks right on tablets.
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        StorageRing(used, cfg?.quotaBytes ?: 0, size = (maxWidth * 0.68f).coerceIn(150.dp, 240.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconStat(Icons.Rounded.Storage, Mint, formatBytes(((cfg?.quotaBytes ?: 0) - used).coerceAtLeast(0)), "Shared quota left", Modifier.weight(1f))
                        IconStat(Icons.Rounded.PhoneAndroid, ElectricBlue, formatBytes(storage.freeBytes), "Phone free", Modifier.weight(1f))
                        IconStat(Icons.Rounded.Groups, Violet, guests.count { it.authenticated }.toString(), "Connected", Modifier.weight(1f))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(Icons.Rounded.WifiTethering, Mint, size = 46.dp)
                        Column(Modifier.weight(1f)) {
                            Text("Share my storage", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (enabled) "Nearby phones can request access" else "Off - nobody can see this phone",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { if (!it) host.setSharing(false) else if (needsFolder) pickFolder.launch(android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")) else askPermissions() })
                    }
                    when {
                        enabled && advertising -> Pill("Visible to nearby devices", Mint, live = true)
                        enabled -> Pill("Starting...", Amber)
                    }
                    hostError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (enabled && addresses.isNotEmpty()) {
                        Text("Also reachable on your Wi-Fi at", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        addresses.forEach { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                    }
                }
            },
            right = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionCard("Find hosts", Icons.Rounded.WifiTethering, Mint, Modifier.weight(1f)) { navigate("nearby") }
                    ActionCard("My files", Icons.Rounded.FolderOpen, ElectricBlue, Modifier.weight(1f)) { navigate("files") }
                    ActionCard("Devices", Icons.Rounded.Shield, Violet, Modifier.weight(1f)) { navigate("access") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionCard("Received files", Icons.Rounded.FolderOpen, Amber, Modifier.weight(1f)) { navigate("received") }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(2f))
                }

                SectionCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, tint = Mint, modifier = Modifier.size(22.dp))
                        Text("Recent transfers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        TextButton({ navigate("transfers") }) {
                            Text("View all")
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
                        }
                    }
                    if (transfers.isEmpty()) {
                        Text("Nothing yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        transfers.take(4).forEach { TransferRow(it) }
                    }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** Action card: tinted icon on top, label and chevron below, whole card tappable. */
@Composable
private fun ActionCard(label: String, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    // Compact padding so labels such as "Find hosts" fit beside the chevron on narrow phones.
    SectionCard(modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick), contentPadding = 14.dp) {
        IconTile(icon, tint, size = 44.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    val image = t.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
    val inFlight = t.state in listOf(TransferState.SENDING, TransferState.RECEIVING, TransferState.VERIFYING, TransferState.RESERVED)
    val (statusText, statusIcon, statusTint) = when {
        t.state == TransferState.COMPLETED -> Triple("Completed", Icons.Rounded.CheckCircle, Mint)
        inFlight -> Triple("In progress", Icons.Rounded.ArrowCircleUp, ElectricBlue)
        else -> Triple(if (t.state == TransferState.CANCELLED) "Cancelled" else "Failed", Icons.Rounded.Error, MaterialTheme.colorScheme.error)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(if (image) Icons.Rounded.Image else Icons.Rounded.InsertDriveFile, if (image) Mint else ElectricBlue, size = 46.dp)
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${if (t.direction == TransferDirection.UPLOAD) "Upload" else "Download"} - ${formatBytes(t.totalBytes)} - $statusText",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        Icon(statusIcon, statusText, Modifier.size(26.dp), tint = statusTint)
    }
}
