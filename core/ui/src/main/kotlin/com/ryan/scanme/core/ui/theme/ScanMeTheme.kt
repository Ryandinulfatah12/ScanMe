package com.ryan.scanme.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Brand palette: neutral light surfaces, near-black ink, one indigo accent, violet for the page overlay. */
object ScanMeColors {
    val Background = Color(0xFFF2F2F4)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFE8E8EC)
    val Ink = Color(0xFF111113)
    val Muted = Color(0xFF8E8E93)
    val Accent = Color(0xFF6366F1)
    val AccentSoft = Color(0xFFE6E6FD)
    val Overlay = Color(0xFF7C3AED)
    val OverlayFill = Color(0x59D8C8FA)
    val Danger = Color(0xFFE5484D)
    val WhatsApp = Color(0xFF25D366)
    val CameraBar = Color(0xB3000000)
}

private val colors = lightColorScheme(
    primary = ScanMeColors.Accent,
    onPrimary = Color.White,
    primaryContainer = ScanMeColors.AccentSoft,
    onPrimaryContainer = ScanMeColors.Accent,
    secondary = ScanMeColors.Overlay,
    background = ScanMeColors.Background,
    onBackground = ScanMeColors.Ink,
    surface = ScanMeColors.Surface,
    onSurface = ScanMeColors.Ink,
    surfaceVariant = ScanMeColors.SurfaceMuted,
    onSurfaceVariant = ScanMeColors.Muted,
    outline = Color(0xFFD9D9DE),
    error = ScanMeColors.Danger,
)

private val typography = Typography().run {
    copy(
        // Home greeting: large, tight, heavy, like the reference design.
        displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontSize = 11.sp),
    )
}

/**
 * Fixed light theme. The design relies on its own palette (white cards on light grey, black camera), so
 * dynamic colour and dark mode are intentionally not applied.
 */
@Composable
fun ScanMeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
