package com.nearbyshare.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nearbyshare.core.network.protocol.RemoteFile
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal val Blue = Color(0xFF4D9BFF)
internal val Mint = Color(0xFF3DDC84)
internal val Card = Color(0xFF0E1C3A)
internal val Edge = Color(0xFF2E5399)
internal val Muted = Color(0xFFBBC9E4)

@Composable
fun NearbyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blue, onPrimary = Color(0xFF00172F), secondary = Mint,
            background = Color(0xFF050B18), onBackground = Color(0xFFF2F6FF),
            surface = Color(0xFF09122A), onSurface = Color(0xFFF2F6FF),
            surfaceVariant = Card, onSurfaceVariant = Muted, outline = Edge, error = Color(0xFFFF8A8A),
        ),
        content = content,
    )
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(22.dp)).background(Brush.verticalGradient(listOf(Card, Color(0xFF09122A))))
            .border(1.dp, Edge.copy(alpha = 0.7f), RoundedCornerShape(22.dp)),
    ) { content() }
}

@Composable
internal fun Chip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Tile(icon: ImageVector, tint: Color, size: Int = 44) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.16f)).border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size((size / 2).dp), tint = tint)
    }
}

@Composable
fun AppRoot(app: DesktopApp) {
    DisposableEffect(Unit) {
        app.startScanning()
        onDispose { app.stopScanning() }
    }
    // Surface sets the default text colour; without it text would be black on the dark background.
    Surface(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF050B18), Color(0xFF0A1A3C)))),
        color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        var mode by remember { mutableStateOf(0) }
        when {
            app.conn is Conn.Connected -> BrowserScreen(app)
            else -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 28.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeTab("Use nearby storage", mode == 0) { mode = 0 }
                    ModeTab(if (app.host.running) "Share my storage · on" else "Share my storage", mode == 1) { mode = 1 }
                }
                Box(Modifier.weight(1f)) { if (mode == 0) HostsScreen(app) else HostScreen(app) }
            }
        }
        HostApprovalHost(app)
        (app.conn as? Conn.Pairing)?.let { PairingDialog(app, it) }
        app.message?.let { msg ->
            AlertDialog(
                onDismissRequest = { app.message = null },
                confirmButton = { TextButton({ app.message = null }) { Text("OK") } },
                title = { Text("Something went wrong") },
                text = { Text(msg) },
            )
        }
    }
}

// ---- finding and connecting to a host --------------------------------------------------------------------------------------

@Composable
private fun ModeTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) Blue else Muted
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Blue.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, c.copy(alpha = if (selected) 0.9f else 0.4f), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
    ) { Text(text, color = c, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun HostsScreen(app: DesktopApp) {
    val hosts by app.discovered.collectAsState()
    var address by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }
    val connecting = app.conn as? Conn.Connecting
    val failed = app.conn as? Conn.Failed
    val saved = remember(app.conn, hosts) { app.pins.all() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(Icons.Rounded.WifiTethering, Blue, 56)
                Column {
                    Text("Nearby Storage Share", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("This PC: ${app.device.name}", color = Muted)
                }
            }

            if (connecting != null) {
                Panel(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Text("Connecting to ${connecting.label}...", Modifier.weight(1f))
                        OutlinedButton({ app.disconnect() }) { Text("Cancel") }
                    }
                }
            }
            if (failed != null) {
                Panel(Modifier.fillMaxWidth()) { Text(failed.message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
            }

            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Hosts on your network", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (hosts.isEmpty()) {
                        Text(
                            "Looking for phones and PCs that are sharing storage. Turn on \"Share my storage\" on the other device, and make sure both are on the same Wi-Fi or hotspot.",
                            color = Muted,
                        )
                    }
                    hosts.forEach { h ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Tile(Icons.Rounded.WifiTethering, Mint)
                            Column(Modifier.weight(1f)) {
                                Text(h.name, fontWeight = FontWeight.SemiBold)
                                Text("${h.address}:${h.port}", color = Muted, fontSize = 13.sp)
                            }
                            Button({ app.connect(h) }, enabled = connecting == null) { Text("Connect") }
                        }
                    }
                }
            }

            if (saved.isNotEmpty()) {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Paired hosts", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        saved.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Tile(Icons.Rounded.Home, Blue)
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, fontWeight = FontWeight.SemiBold)
                                    Text(if (s.address.isNotEmpty()) "Last seen at ${s.address}:${s.port}" else "No address yet", color = Muted, fontSize = 13.sp)
                                }
                                OutlinedButton({ app.pins.forget(s.id); app.message = null }) { Text("Forget") }
                                Button({ app.connectSaved(s) }, enabled = connecting == null && s.address.isNotEmpty()) { Text("Connect") }
                            }
                        }
                    }
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connect by address", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Use this if the host does not appear above. The host shows its address on its Home screen.", color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        OutlinedTextField(
                            address, { address = it; addressError = null }, singleLine = true, modifier = Modifier.weight(1f),
                            label = { Text("192.168.1.20:45820") }, isError = addressError != null, supportingText = { addressError?.let { Text(it) } },
                        )
                        Button({ addressError = app.connectByAddress(address) }, Modifier.height(56.dp), enabled = address.isNotBlank() && connecting == null) { Text("Connect") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingDialog(app: DesktopApp, p: Conn.Pairing) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Pair with ${p.hostName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Check that this code is the same on the other device's screen:")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF16356B)).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text(p.code, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, textAlign = TextAlign.Center)
                }
                if (p.accepted) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                        Text("Waiting for ${p.hostName} to tap Allow...")
                    }
                } else {
                    Text("Both devices must confirm. Tap the button, then tap Allow on the host.", color = Muted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { if (!p.accepted) Button({ app.confirmPairing(true) }) { Text("Yes, the codes match") } },
        dismissButton = { TextButton({ if (p.accepted) app.disconnect() else app.confirmPairing(false) }) { Text("Cancel") } },
    )
}

// ---- browsing files ----------------------------------------------------------------------------------------------------------

@Composable
private fun BrowserScreen(app: DesktopApp) {
    val conn = app.conn as Conn.Connected
    val info = app.info
    val perms = info?.permissions
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteFile?>(null) }
    var deleting by remember { mutableStateOf<RemoteFile?>(null) }
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton({ if (!app.up()) app.disconnect() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(conn.hostName, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Mint))
                    Text("Connected", color = Muted, fontSize = 13.sp)
                }
            }
            OutlinedButton({ app.disconnect() }) { Text("Disconnect") }
        }

        if (info != null) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${formatBytes(info.usedBytes)} of ${formatBytes(info.quotaBytes)} used")
                        Text("${formatBytes(info.remainingBytes)} free", color = Muted)
                    }
                    LinearProgressIndicator(
                        progress = { if (info.quotaBytes <= 0) 0f else (info.usedBytes.toFloat() / info.quotaBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Read", if (info.permissions.read) Mint else Muted)
                        Chip("Upload", if (info.permissions.write) Mint else Muted)
                        Chip("Overwrite", if (info.permissions.overwrite) Mint else Muted)
                        Chip("Delete", if (info.permissions.delete) Mint else Muted)
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ app.goTo(0) }) { Icon(Icons.Rounded.Home, null, Modifier.size(18.dp), tint = Blue); Text("  Home") }
                app.path.forEachIndexed { i, f -> Text("›", color = Muted); TextButton({ app.goTo(i + 1) }) { Text(f.name, maxLines = 1) } }
            }
            if (perms?.folders == true) OutlinedButton({ newFolder = true }) { Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)); Text("  New folder") }
            if (perms?.write == true) Button({
                val dlg = FileDialog(null as Frame?, "Upload files", FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
                dlg.files?.toList()?.filter { it.isFile }?.takeIf { it.isNotEmpty() }?.let { app.upload(it) }
            }) { Icon(Icons.Rounded.CloudUpload, null, Modifier.size(18.dp)); Text("  Upload") }
            IconButton({ app.refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh") }
        }

        if (perms?.read == true) OutlinedTextField(
            query, { query = it; app.search(it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search my files on this host") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
        )

        Panel(Modifier.fillMaxWidth().weight(1f)) {
            when {
                perms != null && !perms.read -> Text("The host has not allowed you to view files. You can still upload if upload is permitted.", Modifier.padding(24.dp), color = Muted)
                app.items.isEmpty() && !app.loading -> Text("Nothing here yet. Upload a file or create a folder to get started.", Modifier.padding(24.dp), color = Muted)
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    items(app.items, key = { it.fileId }) { f ->
                        FileRow(
                            f, perms?.read == true, perms?.delete == true, perms?.write == true,
                            onOpenFolder = { app.openFolder(f) },
                            onOpen = { app.openInCache(f) { file -> runCatching { Desktop.getDesktop().open(file) }.onFailure { app.message = "No app on this PC can open ${f.name}. Use Save instead." } } },
                            onSave = {
                                val dlg = FileDialog(null as Frame?, "Save ${f.name}", FileDialog.SAVE).apply { file = f.name; isVisible = true }
                                if (dlg.file != null) app.download(f, File(dlg.directory, dlg.file))
                            },
                            onRename = { renaming = f },
                            onDelete = { deleting = f },
                        )
                    }
                }
            }
        }

        if (app.transfers.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Transfers", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton({ app.clearFinishedTransfers() }) { Text("Clear finished") }
                    }
                    app.transfers.take(5).forEach { TransferRow(it) }
                }
            }
        }
    }

    if (newFolder) NameDialog("New folder", "", "Create", onDismiss = { newFolder = false }) { app.createFolder(it); newFolder = false }
    renaming?.let { f -> NameDialog("Rename", f.name, "Rename", onDismiss = { renaming = null }) { app.rename(f, it); renaming = null } }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text("This permanently removes it from the host's storage.") },
            confirmButton = { TextButton({ app.delete(f); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileRow(
    f: RemoteFile, canRead: Boolean, canDelete: Boolean, canWrite: Boolean,
    onOpenFolder: () -> Unit, onOpen: () -> Unit, onSave: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = f.isFolder || canRead) { if (f.isFolder) onOpenFolder() else onOpen() }.padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Tile(if (f.isFolder) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile, Blue)
        Column(Modifier.weight(1f)) {
            Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (f.isFolder) "Folder" else "${formatBytes(f.sizeBytes)}  -  ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.updatedAt))}", color = Muted, fontSize = 13.sp)
        }
        if (!f.isFolder && canRead) {
            IconButton(onOpen) { Icon(Icons.Rounded.OpenInNew, "Open") }
            IconButton(onSave) { Icon(Icons.Rounded.Download, "Save to PC") }
        }
        if (canWrite) IconButton(onRename) { Icon(Icons.Rounded.DriveFileRenameOutline, "Rename") }
        if (canDelete) IconButton(onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
    }
}

@Composable
private fun TransferRow(t: TransferItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (t.upload) Icons.Rounded.CloudUpload else Icons.Rounded.Download, null, tint = Blue, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.status == "Active" && t.total > 0) {
                LinearProgressIndicator(progress = { (t.bytes.toFloat() / t.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        when {
            t.status == "Done" -> Icon(Icons.Rounded.CheckCircle, "Done", tint = Mint)
            t.status.startsWith("Failed") -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text(t.status.removePrefix("Failed: "), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 1, modifier = Modifier.widthIn(max = 260.dp))
            }
            t.status == "Active" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (t.total > 0) (t.bytes * 100 / t.total) else 0}%", color = Muted)
                TextButton({ t.cancel() }) { Text("Cancel") }
            }
            else -> Text(t.status, color = Muted)
        }
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ onOk(text) }, enabled = text.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
