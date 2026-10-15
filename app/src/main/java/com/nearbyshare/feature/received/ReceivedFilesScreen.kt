package com.nearbyshare.feature.received

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nearbyshare.core.common.formatBytes
import com.nearbyshare.feature.HostViewModel
import com.nearbyshare.ui.components.CardHeader
import com.nearbyshare.ui.components.ScreenTitle
import com.nearbyshare.ui.components.SectionCard
import com.nearbyshare.ui.theme.ElectricBlue
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** What the host has received from guests: open each file, or open the folder it sits in (visible-folder storage). */
@Composable
fun ReceivedFilesScreen(host: HostViewModel, notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices by host.devices.collectAsState()
    val used by host.usedBytes.collectAsState()
    var files by remember { mutableStateOf<List<HostViewModel.ReceivedFile>?>(null) }
    LaunchedEffect(used, devices.size) { files = host.receivedFiles() }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Received files", "Everything other devices have stored on this phone")
        val list = files
        SectionCard {
            CardHeader(Icons.Rounded.FolderOpen, ElectricBlue, "Files", if (list == null) "Loading..." else "${list.size} file(s)")
            if (list != null && list.isEmpty()) Text("Files that other devices upload will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            list?.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.Icon(Icons.Rounded.InsertDriveFile, null, tint = ElectricBlue)
                        Column(Modifier.weight(1f)) {
                            Text(r.file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            val where = if (r.folder.isEmpty()) "" else " · in ${r.folder}"
                            Text(
                                "${r.deviceName} · ${formatBytes(r.file.sizeBytes)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.file.updatedAt))}$where",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                            )
                            if (!r.exists) Text("Missing on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({
                            scope.launch {
                                val intent = host.openIntent(r.file)
                                if (intent == null) notify("That file is missing on this phone")
                                else try {
                                    context.startActivity(android.content.Intent.createChooser(intent, r.file.displayName))
                                } catch (e: ActivityNotFoundException) {
                                    notify("No app on this phone can open ${r.file.displayName}")
                                }
                            }
                        }, enabled = r.exists) { Text("Open") }
                        if (r.inVisibleFolder) {
                            TextButton({
                                val intent = host.folderIntent(r.file)
                                try {
                                    if (intent == null) throw ActivityNotFoundException()
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val place = listOf(r.deviceName, r.folder).filter { it.isNotEmpty() }.joinToString(" / ")
                                    notify("Open your file manager and look in the shared folder under: $place")
                                }
                            }, enabled = r.exists) { Text("Open location") }
                        }
                    }
                }
            }
            if (list?.any { !it.inVisibleFolder } == true) {
                Text(
                    "Files in hidden storage have no location a file manager can open. Use Open, or switch Storage location to a visible folder in Settings.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
