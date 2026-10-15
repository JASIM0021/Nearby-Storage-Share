package com.nearbyshare.feature.access_control

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearbyshare.core.common.formatBytes
import com.nearbyshare.core.database.TrustState
import com.nearbyshare.feature.DeviceRow
import com.nearbyshare.feature.HostViewModel
import com.nearbyshare.ui.components.Avatar
import com.nearbyshare.ui.components.EmptyState
import com.nearbyshare.ui.components.Pill
import com.nearbyshare.ui.components.ScreenTitle
import com.nearbyshare.ui.components.SectionCard
import com.nearbyshare.ui.theme.Amber
import com.nearbyshare.ui.theme.Mint
import java.text.DateFormat
import java.util.Date

@Composable
fun AccessScreen(host: HostViewModel, navigate: (String) -> Unit) {
    val devices by host.devices.collectAsStateWithLifecycle()
    val guests by host.guests.collectAsStateWithLifecycle()
    val audit by host.audit.collectAsStateWithLifecycle()
    val usage by host.usage.collectAsStateWithLifecycle()
    val cfg by host.config.collectAsStateWithLifecycle()
    val online = guests.filter { it.authenticated }.map { it.deviceId }.toSet()
    val fullSpan: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    // Adaptive grid: one column on phones, two or more on tablets and landscape.
    LazyVerticalGrid(
        GridCells.Adaptive(340.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = fullSpan) { ScreenTitle("Devices & access", "Tap a device to see its usage and activity") }
        if (devices.isEmpty()) {
            item(span = fullSpan) {
                EmptyState(Icons.Rounded.Shield, "No devices yet", "Phones you approve appear here, with permissions you can change at any time.")
            }
        }
        items(devices, key = { it.device.deviceId }) { row ->
            DeviceSummaryCard(
                row, row.device.deviceId in online, usage[row.device.deviceId] ?: 0,
                minOf(row.access?.quotaOverrideBytes ?: cfg?.quotaBytes ?: 0, cfg?.quotaBytes ?: 0),
            ) { navigate("device/${row.device.deviceId}") }
        }
        item(span = fullSpan) {
            SectionCard {
                Text("Security log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (audit.isEmpty()) Text("No events yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                audit.take(15).forEach {
                    Text(
                        "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.createdAt))}  ${it.action} - ${it.outcome}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceSummaryCard(row: DeviceRow, online: Boolean, usedBytes: Long, quotaBytes: Long, onClick: () -> Unit) {
    val d = row.device
    val a = row.access
    SectionCard(Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(d.displayName, size = 48.dp)
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(d.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "Last seen " + (d.lastSeenAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "never"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            StatusPill(row, online)
        }
        LinearProgressIndicator(
            progress = { if (quotaBytes <= 0) 0f else (usedBytes.toFloat() / quotaBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("${formatBytes(usedBytes)} of ${formatBytes(quotaBytes)} used", style = MaterialTheme.typography.bodyMedium)
        if (a != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PermPill("Read", a.canRead)
                PermPill("Upload", a.canWrite)
                PermPill("Overwrite", a.canOverwrite)
                PermPill("Delete", a.canDelete)
            }
        }
    }
}

@Composable
internal fun StatusPill(row: DeviceRow, online: Boolean) {
    when {
        row.device.trustState == TrustState.BLOCKED -> Pill("Blocked", MaterialTheme.colorScheme.error)
        row.access?.revokedAt != null -> Pill("Revoked", Amber)
        online -> Pill("Online", Mint, live = true)
        else -> Pill("Offline", MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PermPill(label: String, allowed: Boolean) {
    Pill(label, if (allowed) Mint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
}
