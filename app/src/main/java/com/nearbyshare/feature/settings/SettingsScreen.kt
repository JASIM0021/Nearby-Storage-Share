package com.nearbyshare.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearbyshare.core.common.GIB
import com.nearbyshare.core.common.formatBytes
import com.nearbyshare.core.database.AccessMode
import com.nearbyshare.core.database.StorageMode
import com.nearbyshare.feature.HostViewModel
import com.nearbyshare.ui.components.AdaptiveColumns
import com.nearbyshare.ui.components.CardHeader
import com.nearbyshare.ui.components.LabeledRow
import com.nearbyshare.ui.components.LegendItem
import com.nearbyshare.ui.components.ScreenTitle
import com.nearbyshare.ui.components.SectionCard
import com.nearbyshare.ui.components.StackedBar
import com.nearbyshare.ui.theme.Amber
import com.nearbyshare.ui.theme.ElectricBlue
import com.nearbyshare.ui.theme.Mint

private val PRESETS = listOf(5L, 10L, 20L)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(host: HostViewModel, navigate: (String) -> Unit, notify: (String) -> Unit) {
    val cfg by host.config.collectAsStateWithLifecycle()
    val storage by host.storage.collectAsStateWithLifecycle()
    val used by host.usedBytes.collectAsStateWithLifecycle()
    val folderName by host.visibleFolderName.collectAsStateWithLifecycle()
    val c = cfg ?: return
    LaunchedEffect(Unit) { host.refreshStorage() }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) host.useVisibleFolder(uri) { it?.let(notify) }
    }
    var showCustom by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(host.deviceName) }
    val isPreset = c.quotaBytes in PRESETS.map { it * GIB }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Settings", "Manage your device and sharing preferences")

        AdaptiveColumns(
            left = {
                SectionCard {
                    CardHeader(Icons.Rounded.PhoneAndroid, ElectricBlue, "This device", "Name shown to nearby phones")
                    OutlinedTextField(
                        name, { name = it.take(40); host.deviceName = name }, singleLine = true,
                        trailingIcon = { Icon(Icons.Rounded.Edit, "Edit name", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SectionCard {
                    CardHeader(Icons.Rounded.Storage, Mint, "Shared storage limit")
                    Text(
                        "Phone: ${formatBytes(storage.totalBytes)} total, ${formatBytes(storage.freeBytes)} free. " +
                            "${formatBytes(c.safetyReserveBytes)} always stays free for you. Guests use ${formatBytes(used)}.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StackedBar(listOf(c.quotaBytes.toFloat() to ElectricBlue, storage.freeBytes.toFloat() to Mint, c.safetyReserveBytes.toFloat() to Amber))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LegendItem(ElectricBlue, formatBytes(c.quotaBytes), "Shared for guests", Modifier.weight(1f))
                        LegendItem(Mint, formatBytes(storage.freeBytes), "Phone free", Modifier.weight(1f))
                        LegendItem(Amber, formatBytes(c.safetyReserveBytes), "Reserved for you", Modifier.weight(1f))
                    }
                    Text("Select shared storage limit", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PRESETS.forEach { gb ->
                            FilterChip(c.quotaBytes == gb * GIB, { host.setQuota(gb * GIB) { it?.let(notify) } }, { Text("$gb GB") })
                        }
                        FilterChip(!isPreset, { showCustom = true }, { Text(if (!isPreset) formatBytes(c.quotaBytes) else "Custom") })
                    }
                }

                SectionCard {
                    CardHeader(Icons.Rounded.FolderOpen, ElectricBlue, "Storage location", "Where guest files are saved")
                    ModeRow(
                        "Private (recommended)",
                        "Hidden app storage. Other apps and file managers cannot see or change guest files.",
                        c.storageMode == StorageMode.PRIVATE,
                    ) { host.usePrivateStorage() }
                    ModeRow(
                        "Visible folder",
                        "Guest files appear in a folder you choose, with their real names, so you can browse them in your file manager.",
                        c.storageMode == StorageMode.VISIBLE,
                    ) {
                        val saved = c.visibleTreeUri
                        if (saved != null) host.useVisibleFolder(Uri.parse(saved)) { it?.let(notify) } else pickFolder.launch(null)
                    }
                    if (c.storageMode == StorageMode.VISIBLE) {
                        Text(
                            "Folder: " + (folderName ?: "not available - choose it again"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (folderName == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        OutlinedButton({ pickFolder.launch(null) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (folderName == null) "Choose folder" else "Change folder")
                        }
                        Text(
                            "Each phone gets its own subfolder. Anyone who can open this folder can edit or delete guest files, and the quota and permissions only apply to changes made through this app. Files already saved stay where they were.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedButton({ pickFolder.launch(null) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose a visible folder...") }
                    }
                }

                SectionCard {
                    CardHeader(Icons.Rounded.Groups, Color3, "Devices at the same time", "How many phones can be connected at once")
                    Text(
                        "More phones share this phone's Wi-Fi/Bluetooth bandwidth, so transfers slow down.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3, 5, 8).forEach { n -> FilterChip(c.maxGuests == n, { host.setMaxGuests(n) }, { Text("$n") }) }
                    }
                }
            },
            right = {
                SectionCard {
                    CardHeader(Icons.Rounded.Shield, Mint, "Who can connect")
                    ModeRow(
                        "Approve each new device", "You'll be asked to approve every device that wants to connect",
                        c.accessMode == AccessMode.APPROVAL,
                    ) { host.setAccessMode(AccessMode.APPROVAL) }
                    ModeRow(
                        "Anyone nearby with restrictions", "Anyone can request access automatically. You can still set storage limits and permissions",
                        c.accessMode == AccessMode.OPEN,
                    ) { host.setAccessMode(AccessMode.OPEN) }
                    ModeRow(
                        "Private", "Only devices you already approved can reconnect",
                        c.accessMode == AccessMode.PRIVATE,
                    ) { host.setAccessMode(AccessMode.PRIVATE) }
                }

                SectionCard {
                    CardHeader(Icons.Rounded.Tune, ElectricBlue, "Default permissions", "Applied to new devices")
                    LabeledRow("View & download", { Switch(c.defaultCanRead, { host.setDefaults(read = it) }) })
                    LabeledRow("Upload", { Switch(c.defaultCanWrite, { host.setDefaults(write = it) }) })
                    LabeledRow("Overwrite existing files", { Switch(c.defaultCanOverwrite, { host.setDefaults(overwrite = it) }) })
                    LabeledRow("Delete", { Switch(c.defaultCanDelete, { host.setDefaults(delete = it) }) })
                    LabeledRow("Create folders", { Switch(c.defaultCanCreateFolders, { host.setDefaults(folders = it) }) })
                }

                OutlinedButton({ navigate("access") }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Manage devices & activity") }
            },
        )
    }

    if (showCustom) {
        var text by remember { mutableStateOf("") }
        val gb = text.toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("Custom limit") },
            text = {
                OutlinedTextField(
                    text, { text = it }, singleLine = true, label = { Text("Gigabytes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                TextButton({
                    host.setQuota((gb!! * GIB).toLong()) { err -> if (err != null) notify(err) }
                    showCustom = false
                }, enabled = gb != null && gb > 0) { Text("Apply") }
            },
            dismissButton = { TextButton({ showCustom = false }) { Text("Cancel") } },
        )
    }
}

private val Color3 = androidx.compose.ui.graphics.Color(0xFFB388FF)

@Composable
private fun ModeRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
