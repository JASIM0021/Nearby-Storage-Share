package com.nearbyshare.feature.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearbyshare.core.common.formatBytes
import com.nearbyshare.core.database.Transfer
import com.nearbyshare.core.database.TransferDirection
import com.nearbyshare.core.database.TransferRole
import com.nearbyshare.core.database.TransferState
import com.nearbyshare.feature.GuestViewModel
import com.nearbyshare.ui.components.EmptyState
import com.nearbyshare.ui.components.IconTile
import com.nearbyshare.ui.components.Pill
import com.nearbyshare.ui.components.ScreenTitle
import com.nearbyshare.ui.components.SectionCard
import com.nearbyshare.ui.theme.Amber
import com.nearbyshare.ui.theme.ElectricBlue
import com.nearbyshare.ui.theme.Mint

private val IN_FLIGHT = listOf(TransferState.SENDING, TransferState.RECEIVING, TransferState.VERIFYING, TransferState.RESERVED)

@Composable
fun TransfersScreen(vm: GuestViewModel) {
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) } // 0 all, 1 active, 2 completed

    val active = transfers.filter { it.state in IN_FLIGHT }
    val completed = transfers.filter { it.state == TransferState.COMPLETED }
    val shown = when (tab) {
        1 -> active
        2 -> completed
        else -> transfers
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenTitle("Transfers", "Uploads and downloads on this phone") }
            TextButton({ vm.clearFinished() }) { Text("Clear finished") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, { Text("All (${transfers.size})") })
            FilterChip(tab == 1, { tab = 1 }, { Text("Active (${active.size})") })
            FilterChip(tab == 2, { tab = 2 }, { Text("Completed (${completed.size})") })
        }

        SectionCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconTile(Icons.Rounded.SwapVert, ElectricBlue, size = 64.dp)
                Column(Modifier.weight(1f)) {
                    Text(completed.size.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Completed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                VerticalDivider(Modifier.height(44.dp).width(1.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(active.size.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Active", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (shown.isEmpty()) {
            EmptyState(
                Icons.Rounded.SwapVert,
                if (transfers.isEmpty()) "No transfers" else "Nothing here",
                if (transfers.isEmpty()) "Files you upload, download or host for others appear here." else "No transfers match this filter.",
            )
        } else {
            shown.forEach { t -> TransferCard(t, progress[t.transferId], onCancel = { vm.cancelTransfer(t) }) }
        }
    }
}

@Composable
private fun TransferCard(t: Transfer, liveBytes: Long?, onCancel: () -> Unit) {
    val inFlight = t.state in IN_FLIGHT
    val bytes = liveBytes ?: t.transferredBytes
    val image = t.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
    val up = t.direction == TransferDirection.UPLOAD
    // On the host the peer is the sender of an upload; on the guest it is the receiver.
    val peerLine = when {
        t.role == TransferRole.HOST && up -> "From ${t.peerName}"
        t.role == TransferRole.HOST -> "To ${t.peerName}"
        up -> "To ${t.peerName}"
        else -> "From ${t.peerName}"
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(if (image) Icons.Rounded.Image else Icons.Rounded.InsertDriveFile, if (image) Mint else ElectricBlue, size = 56.dp)
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${if (up) "Upload" else "Download"} - ${formatBytes(t.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(peerLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            when (t.state) {
                TransferState.COMPLETED -> Pill("Completed", Mint)
                TransferState.FAILED -> Pill("Failed", MaterialTheme.colorScheme.error)
                TransferState.CANCELLED -> Pill("Cancelled", Amber)
                TransferState.VERIFYING -> Pill("Verifying", Amber)
                else -> if (t.role == TransferRole.GUEST) IconButton(onCancel) { Icon(Icons.Rounded.Close, "Cancel") } else Pill("Active", ElectricBlue)
            }
        }
        if (inFlight) {
            LinearProgressIndicator(
                progress = { if (t.totalBytes <= 0) 0f else (bytes.toFloat() / t.totalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${formatBytes(bytes)} of ${formatBytes(t.totalBytes)}", style = MaterialTheme.typography.bodySmall)
        }
        if (t.state == TransferState.FAILED && !t.error.isNullOrBlank()) {
            Text(t.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (t.state == TransferState.COMPLETED && t.sha256 != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(20.dp), tint = Mint)
                Text("SHA-256 verified", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
