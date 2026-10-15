package com.nearbyshare.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nearbyshare.host.AccessMode
import com.nearbyshare.host.DeviceRow
import com.nearbyshare.host.GIB
import com.nearbyshare.host.HostEngine
import com.nearbyshare.host.StorageMode
import com.nearbyshare.host.TrustState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import javax.swing.JFileChooser

private val Warn = Color(0xFFFFB454)
private val Bad = Color(0xFFFF8A8A)

@Composable
private fun Pick(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = if (selected) Blue else Muted
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Blue.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, c.copy(alpha = if (selected) 0.9f else 0.4f), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(text, color = c.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun ToggleRow(title: String, sub: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub != null) Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun Heading(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

private fun chooseFolder(): String? {
    val c = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Choose the folder guests' files are saved in" }
    return if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) c.selectedFile.absolutePath else null
}

private fun whenText(ms: Long?): String = if (ms == null) "never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

private fun openReceived(engine: HostEngine, r: HostEngine.Received): String? = runCatching {
    val f = engine.openableCopy(r.file) ?: return "That file is missing on this PC."
    java.awt.Desktop.getDesktop().open(f)
    null
}.getOrElse { "No app on this PC can open ${r.file.displayName}. Use Show in folder." }

/** Opens File Explorer with the file selected. */
private fun showInFolder(f: java.io.File?): String? {
    if (f == null) return "That file is missing on this PC."
    return runCatching { ProcessBuilder("explorer.exe", "/select,${f.absolutePath}").start(); null }.getOrElse { "Could not open File Explorer." }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostScreen(app: DesktopApp) {
    val host = app.host
    val engine = host.engine
    val cfg by engine.config.collectAsState()
    val devices by engine.devices.collectAsState()
    val usage by engine.usage.collectAsState()
    val used by engine.usedBytes.collectAsState()
    val audit by engine.recentAudit.collectAsState()
    val live by host.guests.collectAsState()
    var quotaError by remember { mutableStateOf<String?>(null) }
    var custom by remember { mutableStateOf("") }
    var folderError by remember { mutableStateOf<String?>(null) }
    var openDevice by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var showAllFiles by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }
    val changes by engine.changes.collectAsState()
    val received = remember(changes) { engine.receivedFiles() }
    var autostart by remember { mutableStateOf(HostService.autostartEnabled()) }
    var deviceName by remember { mutableStateOf(app.device.name) }
    val disk = remember(cfg.storageMode, cfg.visibleDir, used) { engine.diskInfo() }

    LaunchedEffect(host.running) { while (host.running) { host.refreshAddresses(); delay(10_000) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // --- status and the main switch
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Tile(Icons.Rounded.Dns, if (host.running) Mint else Blue, 52)
                        Column(Modifier.weight(1f)) {
                            Text("Share this PC's storage", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (host.running) "Sharing is on · ${live.count { it.authenticated }} connected" else "Sharing is off. Nobody can connect.",
                                color = if (host.running) Mint else Muted,
                            )
                        }
                        Switch(host.running, { if (it) host.start() else host.stop() })
                    }
                    host.error?.let { Text(it, color = Bad) }
                    if (host.running) {
                        Text("Other devices find this PC automatically. If it does not show up, connect by address:", color = Muted, fontSize = 13.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            host.addresses.forEach { Chip(it, Blue) }
                            if (host.addresses.isEmpty()) Text("No network connection found.", color = Warn)
                        }
                        Text("The first time, Windows asks to allow the app through the firewall: choose Private networks.", color = Muted, fontSize = 12.sp)
                    }
                    val share = cfg.quotaBytes
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${formatBytes(used)} of ${formatBytes(share)} used")
                        Text("${formatBytes((share - used).coerceAtLeast(0))} free", color = Muted)
                    }
                    LinearProgressIndicator({ if (share <= 0) 0f else (used.toFloat() / share).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    if (disk.first > 0) Text("This PC's disk: ${formatBytes(disk.second)} free of ${formatBytes(disk.first)}", color = Muted, fontSize = 12.sp)
                }
            }

            // --- storage limit
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Heading("Storage to share")
                    Text("Guests together can store at most this much. ${formatBytes(cfg.safetyReserveBytes)} always stays free for this PC.", color = Muted, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5L, 10L, 20L, 50L).forEach { g -> Pick("$g GB", cfg.quotaBytes == g * GIB) { quotaError = engine.setQuota(g * GIB) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        OutlinedTextField(
                            custom, { custom = it.filter { c -> c.isDigit() || c == '.' }.take(7); quotaError = null },
                            singleLine = true, modifier = Modifier.weight(1f), label = { Text("Custom, in GB") },
                            isError = quotaError != null, supportingText = { quotaError?.let { Text(it) } },
                        )
                        Button({
                            val gb = custom.toDoubleOrNull()
                            quotaError = if (gb == null) "Enter a number" else engine.setQuota((gb * GIB).toLong())
                        }, Modifier.padding(top = 6.dp), enabled = custom.isNotBlank()) { Text("Apply") }
                    }
                }
            }

            // --- where files are stored
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Heading("Where files are stored")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pick("Private (hidden)", cfg.storageMode == StorageMode.PRIVATE) { engine.usePrivateStorage(); folderError = null }
                        Pick("A folder I choose", cfg.storageMode == StorageMode.VISIBLE) {
                            val dir = cfg.visibleDir
                            if (dir != null) folderError = engine.useVisibleFolder(dir)
                            else chooseFolder()?.let { folderError = engine.useVisibleFolder(it) }
                        }
                    }
                    if (cfg.storageMode == StorageMode.PRIVATE) {
                        Text("Files are kept in this app's own folder under random names. Only this app can read them.", color = Muted, fontSize = 13.sp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.Folder, null, tint = Blue)
                            Text(cfg.visibleDir ?: "No folder chosen", Modifier.weight(1f), fontSize = 13.sp)
                            OutlinedButton({ chooseFolder()?.let { folderError = engine.useVisibleFolder(it) } }) { Text("Change") }
                        }
                        Text(
                            "Each guest gets its own subfolder with real file names, so you can open them in File Explorer. Anyone who can open this folder can also change those files. Files already saved stay where they are when you switch.",
                            color = Muted, fontSize = 13.sp,
                        )
                    }
                    folderError?.let { Text(it, color = Bad, fontSize = 13.sp) }
                }
            }

            // --- who may connect and what they may do
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Heading("Who can connect")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pick("Private", cfg.accessMode == AccessMode.PRIVATE) { engine.updateConfig { it.copy(accessMode = AccessMode.PRIVATE) } }
                        Pick("Ask me each time", cfg.accessMode == AccessMode.APPROVAL) { engine.updateConfig { it.copy(accessMode = AccessMode.APPROVAL) } }
                        Pick("Open on this network", cfg.accessMode == AccessMode.OPEN) { engine.updateConfig { it.copy(accessMode = AccessMode.OPEN) } }
                    }
                    Text(
                        when (cfg.accessMode) {
                            AccessMode.PRIVATE -> "Only devices you already approved can connect. New devices are refused."
                            AccessMode.APPROVAL -> "A new device must show the same code as this screen, and you press Allow."
                            AccessMode.OPEN -> "Any device on this network can join with the default permissions below, after the code check. Use only on a network you trust."
                        },
                        color = Muted, fontSize = 13.sp,
                    )
                    Text("Devices at once", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3, 5, 8).forEach { n -> Pick("$n", cfg.maxGuests == n) { engine.updateConfig { it.copy(maxGuests = n) } } }
                    }
                    Text("Default permissions for new devices", fontWeight = FontWeight.SemiBold)
                    ToggleRow("View and download", checked = cfg.defaultCanRead) { v -> engine.updateConfig { it.copy(defaultCanRead = v) } }
                    ToggleRow("Upload", checked = cfg.defaultCanWrite) { v -> engine.updateConfig { it.copy(defaultCanWrite = v) } }
                    ToggleRow("Replace existing files", checked = cfg.defaultCanOverwrite) { v -> engine.updateConfig { it.copy(defaultCanOverwrite = v) } }
                    ToggleRow("Delete files", checked = cfg.defaultCanDelete) { v -> engine.updateConfig { it.copy(defaultCanDelete = v) } }
                    ToggleRow("Create folders", checked = cfg.defaultCanCreateFolders) { v -> engine.updateConfig { it.copy(defaultCanCreateFolders = v) } }
                }
            }

            // --- devices
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Heading("Devices")
                    if (devices.isEmpty()) Text("No device has connected yet.", color = Muted)
                    devices.forEach { row ->
                        val d = row.device
                        val acc = row.access
                        val online = live.any { it.authenticated && it.deviceId == d.deviceId }
                        val exp = acc?.expiresAt
                        val state = when {
                            d.trustState == TrustState.BLOCKED -> "Blocked" to Bad
                            acc?.revokedAt != null -> "Revoked" to Bad
                            exp != null && exp < System.currentTimeMillis() -> "Expired" to Warn
                            online -> "Connected" to Mint
                            else -> "Offline" to Muted
                        }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { openDevice = d.deviceId }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Tile(Icons.Rounded.PhoneAndroid, state.second)
                            Column(Modifier.weight(1f)) {
                                Text(d.displayName, fontWeight = FontWeight.SemiBold)
                                Text("${formatBytes(usage[d.deviceId] ?: 0)} stored · tap to manage", color = Muted, fontSize = 13.sp)
                            }
                            Chip(state.first, state.second)
                        }
                    }
                }
            }

            // --- everything guests have sent
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Heading("Received files")
                    val shown = if (showAllFiles) received else received.take(8)
                    if (received.isEmpty()) Text("Files that guests upload will be listed here.", color = Muted)
                    shown.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(r.file.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                val where = if (r.folder.isEmpty()) "" else " · in ${r.folder}"
                                Text("${r.deviceName} · ${formatBytes(r.file.sizeBytes)} · ${whenText(r.file.updatedAt)}$where", color = Muted, fontSize = 12.sp, maxLines = 1)
                                if (r.location == null) Text("Missing on this PC", color = Warn, fontSize = 12.sp)
                            }
                            TextButton({ openError = openReceived(engine, r) }, enabled = r.location != null) { Text("Open") }
                            TextButton({ openError = showInFolder(r.location) }, enabled = r.location != null) { Text("Show in folder") }
                        }
                    }
                    if (received.size > 8) TextButton({ showAllFiles = !showAllFiles }) { Text(if (showAllFiles) "Show fewer" else "Show all ${received.size}") }
                    openError?.let { Text(it, color = Bad, fontSize = 13.sp) }
                    if (cfg.storageMode == StorageMode.PRIVATE && received.isNotEmpty()) {
                        Text("Private storage keeps files under random names. \"Open\" opens a temporary copy with the real name; \"Show in folder\" highlights the stored file.", color = Muted, fontSize = 12.sp)
                    }
                }
            }

            // --- security log
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Heading("Recent activity")
                    if (audit.isEmpty()) Text("Nothing yet.", color = Muted)
                    audit.take(12).forEach { a ->
                        val who = devices.firstOrNull { it.device.deviceId == a.deviceId }?.device?.displayName ?: "-"
                        Text("${whenText(a.createdAt)} · $who · ${a.action} · ${a.outcome}", color = Muted, fontSize = 12.sp)
                    }
                }
            }

            // --- this PC
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Heading("This PC")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Name shown to other devices"); Text(deviceName, color = Muted, fontSize = 13.sp) }
                        OutlinedButton({ renaming = true }) { Text("Rename") }
                    }
                    ToggleRow("Start with Windows", "Opens in the tray when you sign in. Sharing still has to be switched on.", autostart) {
                        HostService.setAutostart(it)
                        autostart = HostService.autostartEnabled()
                    }
                    Text("While sharing, Windows is kept awake so guests are not dropped. Closing the window keeps sharing in the tray.", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }

    if (renaming) NameDialog("Device name", deviceName, "Save", { renaming = false }) {
        app.renameDevice(it)
        deviceName = app.device.name
        renaming = false
    }
    openDevice?.let { id -> devices.firstOrNull { it.device.deviceId == id }?.let { DeviceDialog(engine, it, usage[id] ?: 0) { openDevice = null } } }
}

@Composable
fun HostApprovalHost(app: DesktopApp) {
    // shown on every screen, so a request is never missed while browsing a remote host
    val pending by app.host.engine.pending.collectAsState()
    pending.firstOrNull()?.let { p -> ApprovalDialog(p.name, p.code, p.rePair) { app.host.engine.resolvePairing(p.key, it) } }
}

@Composable
private fun ApprovalDialog(name: String, code: String, rePair: Boolean, answer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (rePair) "$name wants to pair again" else "$name wants to connect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Allow only if the same code is showing on that device:")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF16356B)).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text(code, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, textAlign = TextAlign.Center)
                }
                if (rePair) Text("It keeps its files and permissions.", color = Muted, fontSize = 13.sp)
            }
        },
        confirmButton = { Button({ answer(true) }) { Text("Allow") } },
        dismissButton = { TextButton({ answer(false) }) { Text("Deny") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceDialog(engine: HostEngine, row: DeviceRow, stored: Long, onClose: () -> Unit) {
    val d = row.device
    val a = row.access ?: return
    val cfg by engine.config.collectAsState()
    val activity = remember(d.deviceId, stored, a) { engine.auditFor(d.deviceId, 8) }
    val files = remember(d.deviceId, stored) { engine.fileCount(d.deviceId) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(d.displayName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatBytes(stored)} stored in $files item(s) · first seen ${whenText(d.firstSeenAt)}", color = Muted, fontSize = 13.sp)
                val cap = minOf(a.quotaOverrideBytes ?: cfg.quotaBytes, cfg.quotaBytes)
                LinearProgressIndicator({ if (cap <= 0) 0f else (stored.toFloat() / cap).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Text("Limit for this device", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pick("Same as host", a.quotaOverrideBytes == null) { engine.setDeviceQuota(d.deviceId, null) }
                    listOf(1L, 2L, 5L).forEach { g -> Pick("$g GB", a.quotaOverrideBytes == g * GIB) { engine.setDeviceQuota(d.deviceId, g * GIB) } }
                }
                Text("Access lasts", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pick("Always", a.expiresAt == null) { engine.setDeviceExpiry(d.deviceId, null) }
                    Pick("1 hour", false) { engine.setDeviceExpiry(d.deviceId, 1) }
                    Pick("24 hours", false) { engine.setDeviceExpiry(d.deviceId, 24) }
                    Pick("7 days", false) { engine.setDeviceExpiry(d.deviceId, 24 * 7) }
                }
                a.expiresAt?.let { Text("Until ${whenText(it)}", color = Muted, fontSize = 12.sp) }
                ToggleRow("View and download", checked = a.canRead) { v -> engine.updateAccess(d.deviceId) { it.copy(canRead = v) } }
                ToggleRow("Upload", checked = a.canWrite) { v -> engine.updateAccess(d.deviceId) { it.copy(canWrite = v) } }
                ToggleRow("Replace existing files", checked = a.canOverwrite) { v -> engine.updateAccess(d.deviceId) { it.copy(canOverwrite = v) } }
                ToggleRow("Delete files", checked = a.canDelete) { v -> engine.updateAccess(d.deviceId) { it.copy(canDelete = v) } }
                ToggleRow("Create folders", checked = a.canCreateFolders) { v -> engine.updateAccess(d.deviceId) { it.copy(canCreateFolders = v) } }
                if (activity.isNotEmpty()) {
                    Text("Recent activity", fontWeight = FontWeight.SemiBold)
                    activity.forEach { Text("${whenText(it.createdAt)} · ${it.action} · ${it.outcome}", color = Muted, fontSize = 12.sp) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (a.revokedAt == null) OutlinedButton({ engine.revoke(d.deviceId) }) { Text("Revoke access") }
                    else OutlinedButton({ engine.restore(d.deviceId) }) { Text("Restore access") }
                    if (d.trustState == TrustState.BLOCKED) OutlinedButton({ engine.unblock(d.deviceId) }) { Text("Unblock") }
                    else OutlinedButton({ engine.block(d.deviceId) }) { Icon(Icons.Rounded.Block, null, Modifier.size(16.dp)); Text("  Block") }
                    OutlinedButton({ engine.resetPairing(d.deviceId) }) { Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)); Text("  Require new pairing") }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Done") } },
    )
}
