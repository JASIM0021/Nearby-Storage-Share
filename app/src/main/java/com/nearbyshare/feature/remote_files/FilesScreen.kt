package com.nearbyshare.feature.remote_files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import com.nearbyshare.ui.components.GlassPanel
import com.nearbyshare.ui.components.HeroCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearbyshare.core.common.formatBytes
import com.nearbyshare.core.network.GuestState
import com.nearbyshare.core.network.protocol.RemoteFile
import com.nearbyshare.feature.GuestViewModel
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import com.nearbyshare.ui.components.IconTile
import com.nearbyshare.ui.components.ScreenTitle
import com.nearbyshare.ui.theme.ElectricBlue
import com.nearbyshare.ui.components.EmptyState
import com.nearbyshare.ui.components.Pill
import com.nearbyshare.ui.components.PrimaryButton
import com.nearbyshare.ui.components.SectionCard
import com.nearbyshare.ui.theme.Mint
import java.text.DateFormat
import java.util.Date

@Composable
fun FilesScreen(guest: GuestViewModel, navigate: (String) -> Unit) {
    val state by guest.state.collectAsStateWithLifecycle()
    val ui by guest.files.collectAsStateWithLifecycle()

    val host = state as? GuestState.Connected
    if (host == null) {
        NotConnectedFiles(navigate)
        return
    }

    val perms = ui.info?.permissions
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RemoteFile?>(null) }

    BackHandler(enabled = ui.path.isNotEmpty()) { guest.up() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) guest.upload(uri) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pendingDownload
        pendingDownload = null
        if (uri != null && f != null) guest.download(f, uri)
    }

    var hostMenu by remember { mutableStateOf(false) }
    val opening by guest.opening.collectAsStateWithLifecycle()

    // A fetched file is ready: hand it to the viewer app for its type (PDF reader, gallery, video player...).
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        guest.previewRequests.collect { r ->
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(r.uri, r.mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            view.clipData = android.content.ClipData.newRawUri(r.name, r.uri)
            try {
                context.startActivity(android.content.Intent.createChooser(view, r.name))
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(context, "No app on this phone can open ${r.name}. Use Download to save it.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Header: back, host name with connection status, overflow menu
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton({ if (!guest.up()) navigate("nearby") }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(host.hostName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Mint))
                        Text("Connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    IconButton({ hostMenu = true }) { Icon(Icons.Rounded.MoreVert, "Host options") }
                    DropdownMenu(hostMenu, { hostMenu = false }) {
                        DropdownMenuItem({ Text("Refresh") }, { hostMenu = false; guest.refresh() })
                        DropdownMenuItem({ Text("Disconnect") }, { hostMenu = false; guest.disconnect() })
                    }
                }
            }

            ui.info?.let { info ->
                HeroCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconTile(Icons.Rounded.PhoneAndroid, ElectricBlue, size = 52.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${formatBytes(info.usedBytes)} of ${formatBytes(info.quotaBytes)} used", style = MaterialTheme.typography.bodyMedium)
                                Text("${formatBytes(info.remainingBytes)} free", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            LinearProgressIndicator(
                                progress = { if (info.quotaBytes <= 0) 0f else (info.usedBytes.toFloat() / info.quotaBytes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(), color = ElectricBlue, trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            )
                        }
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PermPill("Read", info.permissions.read)
                        PermPill("Upload", info.permissions.write)
                        PermPill("Overwrite", info.permissions.overwrite)
                        PermPill("Delete", info.permissions.delete)
                    }
                }
            }

            // Breadcrumb: Home > folder > subfolder
            GlassPanel {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ guest.navigateTo(0) }) {
                        Icon(Icons.Rounded.Home, null, Modifier.size(20.dp), tint = ElectricBlue)
                        Text("Home", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurface)
                    }
                    ui.path.forEachIndexed { i, folder ->
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ guest.navigateTo(i + 1) }) { Text(folder.name, maxLines = 1, color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
            }

            opening?.let { name ->
                GlassPanel {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Opening $name...", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }

            when {
                perms != null && !perms.read -> EmptyState(Icons.Rounded.FolderOpen, "Browsing is off", "The host has not allowed you to view files. You can still upload if upload is permitted.")
                ui.items.isEmpty() && !ui.loading -> EmptyState(Icons.Rounded.FolderOpen, "Nothing here yet", "Upload a file or create a folder to get started.")
                else -> GlassPanel(Modifier.weight(1f, fill = false)) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp, horizontal = 14.dp)) {
                        itemsIndexed(ui.items, key = { _, f -> f.fileId }) { index, f ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                            FileRow(
                                f,
                                canDownload = perms?.read == true,
                                canDelete = perms?.delete == true,
                                onOpen = { if (f.isFolder) guest.openFolder(f) else guest.open(f) },
                                onDownload = {
                                    pendingDownload = f
                                    saver.launch(f.name)
                                },
                                onDelete = { deleteTarget = f },
                            )
                        }
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (perms?.folders == true) {
                GlowFab({ showNewFolder = true }, RoundedCornerShape(20.dp), Modifier.size(58.dp)) {
                    Icon(Icons.Rounded.CreateNewFolder, "New folder", tint = Color.White)
                }
            }
            if (perms?.write == true) {
                GlowFab({ picker.launch("*/*") }, RoundedCornerShape(50), Modifier.height(58.dp).padding(horizontal = 4.dp)) {
                    Icon(Icons.Rounded.CloudUpload, null, Modifier.padding(start = 16.dp), tint = Color.White)
                    Text("Upload", Modifier.padding(start = 10.dp, end = 22.dp), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton({
                    guest.createFolder(name.trim())
                    showNewFolder = false
                }, enabled = name.isNotBlank()) { Text("Create") }
            },
            dismissButton = { TextButton({ showNewFolder = false }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text("This permanently removes it from the host's storage.") },
            confirmButton = {
                TextButton({
                    guest.delete(f)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

/** Empty state shown before a host is connected: a connect card, a folder illustration and what the screen offers. */
@Composable
private fun NotConnectedFiles(navigate: (String) -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth()) { ScreenTitle("My files", "Access files stored on nearby devices") }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(Icons.Rounded.FolderOpen, ElectricBlue, size = 56.dp)
                Column {
                    Text("Not connected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Connect to a nearby host to browse and upload your files.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button({ navigate("nearby") }, Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Rounded.WifiTethering, null, Modifier.padding(end = 8.dp))
                Text("Find devices", fontWeight = FontWeight.SemiBold)
            }
        }

        // Illustration: a glowing folder with a phone and signal beside it.
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(ElectricBlue.copy(alpha = 0.28f), Color.Transparent), c, r), r, c)
                listOf(1f, 0.72f, 0.46f).forEach { drawCircle(ElectricBlue.copy(alpha = 0.3f), r * it - 1.dp.toPx(), c, style = Stroke(1.2.dp.toPx())) }
            }
            IconTile(Icons.Rounded.FolderOpen, ElectricBlue, size = 96.dp)
            Icon(Icons.Rounded.PhoneAndroid, null, Modifier.align(Alignment.CenterEnd).size(40.dp), tint = Mint)
            Icon(Icons.Rounded.WifiTethering, null, Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 18.dp).size(26.dp), tint = Mint)
        }

        Text("No files available yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            "Connect to a nearby device to browse files, upload new content and manage your storage.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureChip("Upload files", Icons.Rounded.CloudUpload, Mint, Modifier.weight(1f))
            FeatureChip("Browse folders", Icons.Rounded.Folder, ElectricBlue, Modifier.weight(1f))
            FeatureChip("Secure access", Icons.Rounded.Lock, Color(0xFFB388FF), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FeatureChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier) {
    SectionCard(modifier, contentPadding = 12.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(icon, tint, size = 40.dp)
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Floating action button in the glass style: deep-blue fill, bright blue edge and a soft blue glow. */
@Composable
private fun GlowFab(onClick: () -> Unit, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.shadow(16.dp, shape, ambientColor = ElectricBlue, spotColor = ElectricBlue)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1F4FB8), Color(0xFF0B2A6B))))
            .border(1.5.dp, Brush.verticalGradient(listOf(Color(0xFF8FC0FF), ElectricBlue)), shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun PermPill(label: String, allowed: Boolean) {
    Pill(label, if (allowed) Mint else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FileRow(
    f: RemoteFile,
    canDownload: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val showMenu = (!f.isFolder && canDownload) || canDelete
    val image = !f.isFolder && f.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
    Row(
        Modifier.fillMaxWidth()
            // folders always open; a file opens in its viewer app when the host allows reading
            .let { if (f.isFolder || canDownload) it.clickable(onClick = onOpen) else it }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(
            when {
                f.isFolder -> Icons.Rounded.Folder
                image -> Icons.Rounded.Image
                else -> Icons.Rounded.InsertDriveFile
            },
            if (image) Color(0xFF8AB4FF) else ElectricBlue, size = 48.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (f.isFolder) "Folder" else "${formatBytes(f.sizeBytes)} - ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.updatedAt))}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showMenu) {
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Actions") }
                DropdownMenu(menu, { menu = false }) {
                    if (!f.isFolder && canDownload) DropdownMenuItem({ Text("Open") }, { menu = false; onOpen() })
                    if (!f.isFolder && canDownload) DropdownMenuItem({ Text("Save to phone...") }, { menu = false; onDownload() })
                    if (canDelete) DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete() })
                }
            }
        }
    }
}
