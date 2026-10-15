package com.nearbyshare.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val ElectricBlue = Color(0xFF4D9BFF)
val Mint = Color(0xFF3DDC84)
val Amber = Color(0xFFFFC266)

/** Screen width buckets, mirroring Material window size classes (compact < 600dp, medium < 840dp, expanded >= 840dp). */
enum class WidthClass { Compact, Medium, Expanded }

val LocalWidthClass = compositionLocalOf { WidthClass.Compact }

private val Dark = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = Color(0xFF00172F),
    primaryContainer = Color(0xFF16356B),
    onPrimaryContainer = Color(0xFFE3EEFF),
    secondary = Mint,
    onSecondary = Color(0xFF03210F),
    secondaryContainer = Color(0xFF0F3D33),
    onSecondaryContainer = Color(0xFFC9F8DD),
    background = Color(0xFF050B18),
    onBackground = Color(0xFFF2F6FF),
    surface = Color(0xFF09122A),
    onSurface = Color(0xFFF2F6FF),
    surfaceVariant = Color(0xFF0E1C3A),
    onSurfaceVariant = Color(0xFFBBC9E4), // high-contrast secondary text (>= 8:1 on the card colour)
    outline = Color(0xFF2E5399),
    error = Color(0xFFFF8A8A),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1558D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF06214F),
    secondary = Color(0xFF0B7F45),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0F3E0),
    onSecondaryContainer = Color(0xFF033A1D),
    background = Color(0xFFF3F6FC),
    onBackground = Color(0xFF0A1424),
    surface = Color.White,
    onSurface = Color(0xFF0A1424),
    surfaceVariant = Color(0xFFE6ECF8),
    onSurfaceVariant = Color(0xFF3A4963),
    outline = Color(0xFF8D9BB5),
    error = Color(0xFFB3261E),
)

private val Sans = FontFamily.SansSerif

/** Larger than the Material defaults: body text starts at 16sp, captions at 13sp, so nothing is hard to read. */
private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun NearbyStorageTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, shapes = AppShapes, content = content)
}
