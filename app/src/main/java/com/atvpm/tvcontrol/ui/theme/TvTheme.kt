package com.atvpm.tvcontrol.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Google TV / Android 14 Material 3 Dark Tonal Palette.
 */
object TvColors {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF161B22)
    val SurfaceElevated = Color(0xFF1F2631)
    val SurfaceFocused = Color(0xFF2B3545)
    val SurfaceDisabled = Color(0xFF12161C)

    val FocusRing = Color(0xFFA8C7FA)
    val FocusGlow = Color(0xFF669DF6)
    val AccentGold = Color(0xFFFFB951)

    val TextPrimary = Color(0xFFE2E2E6)
    val TextSecondary = Color(0xFF9AA0A6)
    val TextMuted = Color(0xFF6E7681)

    val Success = Color(0xFF6DD58C)
    val SuccessBg = Color(0xFF0F2E1D)
    val Warning = Color(0xFFFFB951)
    val WarningBg = Color(0xFF332005)
    val Danger = Color(0xFFF2B8B5)
    val DangerBg = Color(0xFF5C1916)
    val Info = Color(0xFFA8C7FA)
    val InfoBg = Color(0xFF112642)

    val Divider = Color(0xFF28303D)
}

private val TvDarkColorScheme = darkColorScheme(
    primary = TvColors.FocusRing,
    onPrimary = Color(0xFF062E6F),
    secondary = TvColors.AccentGold,
    onSecondary = Color(0xFF261900),
    background = TvColors.Background,
    onBackground = TvColors.TextPrimary,
    surface = TvColors.Surface,
    onSurface = TvColors.TextPrimary,
    surfaceVariant = TvColors.SurfaceElevated,
    onSurfaceVariant = TvColors.TextSecondary,
    error = TvColors.Danger,
    onError = Color(0xFF601410),
)

@Composable
fun TvControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TvDarkColorScheme,
        content = content,
    )
}
