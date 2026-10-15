package com.nearbyshare.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nearbyshare.core.common.formatBytes
import com.nearbyshare.ui.theme.ElectricBlue
import com.nearbyshare.ui.theme.LocalWidthClass
import com.nearbyshare.ui.theme.Mint
import com.nearbyshare.ui.theme.WidthClass
import kotlin.math.cos
import kotlin.math.sin

/** Shared "glass" look: a deep-navy panel with a soft blue edge, used by every card in the app. */
@Composable
private fun glassBrush(): Brush {
    val c = MaterialTheme.colorScheme
    return Brush.verticalGradient(listOf(c.surfaceVariant, c.surface.copy(alpha = 0.92f)))
}

@Composable
private fun glassBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))

@Composable
fun SectionCard(modifier: Modifier = Modifier, contentPadding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(glassBrush()).border(glassBorder(), MaterialTheme.shapes.large),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
        }
    }
}

/** Glass panel without inner padding, for lists that manage their own row padding and dividers. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(MaterialTheme.shapes.large).background(glassBrush()).border(glassBorder(), MaterialTheme.shapes.large)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

/** Prominent card with a brand glow; used for the main storage block. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(c.primaryContainer.copy(alpha = 0.85f), c.surfaceVariant, c.secondaryContainer.copy(alpha = 0.55f))))
            .border(1.dp, ElectricBlue.copy(alpha = 0.45f), MaterialTheme.shapes.large),
    ) {
        CompositionLocalProvider(LocalContentColor provides c.onSurface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
    }
}

/**
 * Phones and medium screens: [left] then [right] stacked. Expanded screens (tablets, foldables, landscape):
 * two side-by-side columns, so wide displays are used instead of stretching one narrow column.
 */
@Composable
fun AdaptiveColumns(
    modifier: Modifier = Modifier,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    if (LocalWidthClass.current == WidthClass.Expanded) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { left() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { right() }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            left()
            right()
        }
    }
}

/** Round avatar showing the first letter of a device name, tinted deterministically from the name, with a soft glow ring. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val palette = listOf(ElectricBlue, Mint, Color(0xFFB388FF), Color(0xFFFFB84D), Color(0xFFFF7B9C))
    val tint = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(
        modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.2f)).border(1.5.dp, tint.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.firstOrNull()?.uppercase() ?: "?", color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

/** Rounded-square icon on a tinted background (stat tiles, action cards, list rows). */
@Composable
fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.16f)).border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = tint)
    }
}

/** Round outlined icon button for the screen header (notifications, settings). */
@Composable
fun GlowIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Boolean = false) {
    Box(
        modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface)
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(9.dp).clip(CircleShape).background(Mint))
    }
}

/** The pairing code, large and spaced so two people can read it out and compare. */
@Composable
fun CodeBox(code: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().padding(vertical = 10.dp).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            code, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, letterSpacing = 8.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Storage ring with a glowing marker at the end of the arc (at 12 o'clock when empty) and the value in the middle. */
@Composable
fun StorageRing(used: Long, quota: Long, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val fraction = if (quota <= 0) 0f else (used.toFloat() / quota).coerceIn(0f, 1f)
    val animated by animateFloatAsState(fraction, tween(900), label = "ring")
    val pulse by rememberInfiniteTransition(label = "glow").animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "pulse",
    )
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.075f
            val inset = stroke / 2 + 8.dp.toPx()
            val arc = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            val center = Offset(this.size.width / 2, this.size.height / 2)
            // faint outer halo ring
            drawCircle(ElectricBlue.copy(alpha = 0.22f), this.size.minDimension / 2 - 1.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (animated > 0f) {
                drawArc(
                    Brush.linearGradient(listOf(ElectricBlue, Mint), Offset(0f, 0f), Offset(this.size.width, this.size.height)),
                    -90f, 360f * animated, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            val angle = Math.toRadians((-90f + 360f * animated).toDouble())
            val r = arc.width / 2
            val dot = Offset(center.x + (r * cos(angle)).toFloat(), center.y + (r * sin(angle)).toFloat())
            drawCircle(Mint.copy(alpha = 0.28f * pulse), stroke * 1.15f, dot)
            drawCircle(Mint, stroke * 0.42f, dot)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(Icons.Rounded.Storage, null, Modifier.size(size * 0.13f), tint = Mint)
            Text(formatBytes(used), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("of ${formatBytes(quota)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, live: Boolean = false) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (live) {
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
            )
            Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = pulse)))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        IconTile(icon, MaterialTheme.colorScheme.primary, size = 84.dp)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            action()
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Stat with a tinted icon tile on its left, as in the Home mockup. */
@Composable
fun IconStat(icon: ImageVector, tint: Color, value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(icon, tint, size = 34.dp)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

/** Card heading: tinted icon tile, title and optional one-line description. */
@Composable
fun CardHeader(icon: ImageVector, tint: Color, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, tint, size = 42.dp)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Horizontal bar split into proportional coloured parts (value, colour). Zero-size parts are skipped. */
@Composable
fun StackedBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    val total = parts.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.0001f)
    Row(
        modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.forEach { (v, c) -> if (v > 0f) Box(Modifier.weight(v / total).fillMaxHeight().background(c)) }
    }
}

/** Legend entry for a [StackedBar]: coloured dot, value and label. */
@Composable
fun LegendItem(color: Color, value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.height(52.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun LabeledRow(label: String, trailing: @Composable () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}
