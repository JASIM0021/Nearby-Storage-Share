package com.nearbyshare.feature.access_control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearbyshare.core.common.GIB
import com.nearbyshare.core.common.formatBytes
import com.nearbyshare.core.database.DeviceAccess
import com.nearbyshare.core.database.TransferDirection
import com.nearbyshare.core.database.TransferState
import com.nearbyshare.core.database.TrustState
import com.nearbyshare.feature.HostViewModel
import com.nearbyshare.ui.components.AdaptiveColumns
import com.nearbyshare.ui.components.Avatar
import com.nearbyshare.ui.components.EmptyState
import com.nearbyshare.ui.components.LabeledRow
import com.nearbyshare.ui.components.SectionCard
import com.nearbyshare.ui.components.StatTile
import com.nearbyshare.ui.theme.Amber
import com.nearbyshare.ui.theme.Mint
import java.text.DateFormat
import java.util.Date

private data class Activity(val time: Long, val icon: ImageVector, val title: String, val detail: String, val problem: Boolean)

/** What the host sees for one guest: how much it uses, what it did, and the controls to limit it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceDetailScreen(deviceId: String, host: HostViewModel) {
    val devices by host.devices.collectAsStateWithLifecycle()
    val guests by host.guests.collectAsStateWithLifecycle()
    val usage by host.usage.collectAsStateWithLifecycle()
    val cfg by host.config.collectAsStateWithLifecycle()
    val transfers by remember(deviceId) { host.transfersFor(deviceId) }.collectAsStateWithLifecycle(emptyList())
    val audit by remember(deviceId) { host.auditFor(deviceId) }.collectAsStateWithLifecycle(emptyList())
    val fileCount by remember(deviceId) { host.fileCount(deviceId) }.collectAsStateWithLifecycle(0)

    val row = devices.firstOrNull { it.device.deviceId == deviceId }
    if (row == null) {
        EmptyState(Icons.Rounded.Shield, "Device not found", "It may have been removed.")
        return
    }
    val d = row.device
    val a = row.access
    val online = guests.any { it.deviceId == deviceId && it.authenticated }
    val hostQuota = cfg?.quotaBytes ?: 0
    val quota = minOf(a?.quotaOverrideBytes ?: hostQuota, hostQuota)
    val used = usage[deviceId] ?: 0

    val uploads = transfers.filter { it.direction == TransferDirection.UPLOAD && it.state == TransferState.COMPLETED }
    val downloads = transfers.filter { it.direction == TransferDirection.DOWNLOAD && it.state == TransferState.COMPLETED }
    val failed = transfers.count { it.state == TransferState.FAILED || it.state == TransferState.CANCELLED }

    // One timeline: transfers plus security events (uploads/downloads are already covered by the transfer rows).
    val timeline = remember(transfers, audit) {
        val t = transfers.map {
            val up = it.direction == TransferDirection.UPLOAD
            Activity(
                it.createdAt, if (up) Icons.Rounded.Upload else Icons.Rounded.Download,
                "${if (up) "Upload" else "Download"} ${it.name}",
                "${formatBytes(it.totalBytes)} - ${it.state.name.lowercase()}" + (it.error?.let { e -> " ($e)" } ?: ""),
                it.state == TransferState.FAILED,
            )
        }
        val e = audit.filterNot { it.action.startsWith("UPLOAD ") || it.action.startsWith("DOWNLOAD ") }.map {
            Activity(it.createdAt, Icons.Rounded.Shield, it.action, it.outcome, it.outcome.startsWith("DENIED") || it.outcome.startsWith("FAILED") || it.outcome.startsWith("REJECTED"))
        }
        (t + e).sortedByDescending { it.time }.take(40)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AdaptiveColumns(
            left = {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Avatar(d.displayName, size = 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(d.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                            Text(
                                "First seen " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(d.firstSeenAt)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Last seen " + (d.lastSeenAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "never"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(row, online)
                    }
                }

                SectionCard {
                    Text("Storage usage", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { if (quota <= 0) 0f else (used.toFloat() / quota).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text("${formatBytes(used)} of ${formatBytes(quota)} used", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile("Files stored", fileCount.toString())
                        StatTile("Uploaded", "${uploads.size} - ${formatBytes(uploads.sumOf { it.totalBytes })}")
                        StatTile("Downloaded", "${downloads.size} - ${formatBytes(downloads.sumOf { it.totalBytes })}")
                        StatTile("Failed", failed.toString())
                    }
                }

                if (a != null) {
                    SectionCard {
                        Text("Storage limit for this device", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(a.quotaOverrideBytes == null, { host.setDeviceQuota(deviceId, null) }, { Text("Host limit") })
                            listOf(1L, 2L, 5L, 10L).filter { it * GIB <= hostQuota }.forEach { gb ->
                                FilterChip(a.quotaOverrideBytes == gb * GIB, { host.setDeviceQuota(deviceId, gb * GIB) }, { Text("$gb GB") })
                            }
                        }
                        Text("Access expires", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(a.expiresAt == null, { host.setDeviceExpiry(deviceId, null) }, { Text("Never") })
                            FilterChip(false, { host.setDeviceExpiry(deviceId, 24) }, { Text("In 24 hours") })
                            FilterChip(false, { host.setDeviceExpiry(deviceId, 24 * 7) }, { Text("In 7 days") })
                        }
                        a.expiresAt?.let {
                            Text(
                                "Expires " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)),
                                style = MaterialTheme.typography.bodySmall, color = Amber,
                            )
                        }
                    }
                }
            },
            right = {
                if (a != null) {
                    SectionCard {
                        Text("Permissions", style = MaterialTheme.typography.titleMedium)
                        fun change(t: (DeviceAccess) -> DeviceAccess) = host.updateAccess(deviceId, t)
                        LabeledRow("View & download", { Switch(a.canRead, { v -> change { it.copy(canRead = v) } }) })
                        LabeledRow("Upload", { Switch(a.canWrite, { v -> change { it.copy(canWrite = v) } }) })
                        LabeledRow("Overwrite", { Switch(a.canOverwrite, { v -> change { it.copy(canOverwrite = v) } }) })
                        LabeledRow("Delete", { Switch(a.canDelete, { v -> change { it.copy(canDelete = v) } }) })
                        LabeledRow("Create folders", { Switch(a.canCreateFolders, { v -> change { it.copy(canCreateFolders = v) } }) })
                    }
                }

                SectionCard {
                    Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                    if (timeline.isEmpty()) {
                        Text("Nothing yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    timeline.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                item.icon, null, Modifier.size(22.dp).padding(top = 2.dp),
                                tint = if (item.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                                Text(
                                    item.detail, style = MaterialTheme.typography.bodySmall,
                                    color = if (item.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.time)),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SectionCard {
                    Text("Actions", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (a?.revokedAt != null) OutlinedButton({ host.restore(deviceId) }) { Text("Restore access") }
                        else OutlinedButton({ host.revoke(deviceId) }) { Text("Revoke access") }
                        if (d.trustState == TrustState.BLOCKED) OutlinedButton({ host.unblock(deviceId) }) { Text("Unblock") }
                        else OutlinedButton({ host.block(deviceId) }) { Text("Block") }
                        OutlinedButton({ host.resetPairing(deviceId) }) { Text("Re-pair") }
                    }
                }
            },
        )
    }
}
