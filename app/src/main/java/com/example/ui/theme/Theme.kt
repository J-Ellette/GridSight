package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class FieldVisualMode(val label: String, val shortCode: String) {
    CTOS_DARK("ctOS Tactical", "CTOS"),
    NIGHT_OPS("Low-Light Night Ops", "NVG"),
    RUGGED_DAYLIGHT("Rugged High-Contrast", "HI-VIS")
}

@Immutable
data class TacticalPalette(
    val mode: FieldVisualMode,
    val radarBeam: Color,
    val gridLine: Color,
    val accentCyan: Color,
    val secureGreen: Color,
    val cautionAmber: Color,
    val alertCrimson: Color,
    val hudBorder: Color,
    val hudPanelAlpha: Float,
    val borderWidth: Dp
)

val LocalTacticalPalette = staticCompositionLocalOf {
    TacticalPalette(
        mode = FieldVisualMode.CTOS_DARK,
        radarBeam = CtosPrimary,
        gridLine = CtosBorder,
        accentCyan = CtosPrimary,
        secureGreen = CtosTertiary,
        cautionAmber = CtosWarning,
        alertCrimson = CtosError,
        hudBorder = CtosBorder,
        hudPanelAlpha = 0.90f,
        borderWidth = 1.dp
    )
}

private val CtosColorScheme = darkColorScheme(
    primary = CtosPrimary,
    onPrimary = CtosOnPrimary,
    primaryContainer = CtosSurfaceVariant,
    onPrimaryContainer = CtosTextPrimary,
    secondary = CtosSecondary,
    onSecondary = CtosOnPrimary,
    tertiary = CtosTertiary,
    onTertiary = CtosOnPrimary,
    background = CtosBg,
    onBackground = CtosTextPrimary,
    surface = CtosSurface,
    onSurface = CtosTextPrimary,
    surfaceVariant = CtosSurfaceVariant,
    onSurfaceVariant = CtosTextMuted,
    error = CtosError,
    onError = Color.White,
    outline = CtosBorder
)

private val NightOpsColorScheme = darkColorScheme(
    primary = NightPrimary,
    onPrimary = NightOnPrimary,
    primaryContainer = NightSurfaceVariant,
    onPrimaryContainer = NightTextPrimary,
    secondary = NightSecondary,
    onSecondary = NightOnPrimary,
    tertiary = NightTertiary,
    onTertiary = NightOnPrimary,
    background = NightBg,
    onBackground = NightTextPrimary,
    surface = NightSurface,
    onSurface = NightTextPrimary,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightTextMuted,
    error = NightError,
    onError = Color.White,
    outline = NightBorder
)

private val RuggedDaylightColorScheme = lightColorScheme(
    primary = RuggedPrimary,
    onPrimary = RuggedOnPrimary,
    primaryContainer = RuggedSurfaceVariant,
    onPrimaryContainer = RuggedTextPrimary,
    secondary = RuggedSecondary,
    onSecondary = RuggedOnPrimary,
    tertiary = RuggedTertiary,
    onTertiary = RuggedOnPrimary,
    background = RuggedBg,
    onBackground = RuggedTextPrimary,
    surface = RuggedSurface,
    onSurface = RuggedTextPrimary,
    surfaceVariant = RuggedSurfaceVariant,
    onSurfaceVariant = RuggedTextMuted,
    error = RuggedError,
    onError = Color.White,
    outline = RuggedBorder
)

private val TacticalShapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(10.dp),
    large = CutCornerShape(14.dp),
    extraLarge = CutCornerShape(18.dp)
)

@Composable
fun GridSightTheme(
    visualMode: FieldVisualMode = FieldVisualMode.CTOS_DARK,
    content: @Composable () -> Unit
) {
    val colorScheme = when (visualMode) {
        FieldVisualMode.CTOS_DARK -> CtosColorScheme
        FieldVisualMode.NIGHT_OPS -> NightOpsColorScheme
        FieldVisualMode.RUGGED_DAYLIGHT -> RuggedDaylightColorScheme
    }

    val tacticalPalette = when (visualMode) {
        FieldVisualMode.CTOS_DARK -> TacticalPalette(
            mode = visualMode,
            radarBeam = CtosPrimary,
            gridLine = CtosBorder,
            accentCyan = CtosPrimary,
            secureGreen = CtosTertiary,
            cautionAmber = CtosWarning,
            alertCrimson = CtosError,
            hudBorder = CtosPrimary.copy(alpha = 0.55f),
            hudPanelAlpha = 0.90f,
            borderWidth = 1.dp
        )
        FieldVisualMode.NIGHT_OPS -> TacticalPalette(
            mode = visualMode,
            radarBeam = NightPrimary,
            gridLine = NightBorder,
            accentCyan = NightPrimary,
            secureGreen = NightTertiary,
            cautionAmber = NightSecondary,
            alertCrimson = NightError,
            hudBorder = NightPrimary.copy(alpha = 0.65f),
            hudPanelAlpha = 0.94f,
            borderWidth = 1.25.dp
        )
        FieldVisualMode.RUGGED_DAYLIGHT -> TacticalPalette(
            mode = visualMode,
            radarBeam = RuggedPrimary,
            gridLine = Color(0xFFB0BEC5),
            accentCyan = RuggedPrimary,
            secureGreen = RuggedTertiary,
            cautionAmber = RuggedWarning,
            alertCrimson = RuggedError,
            hudBorder = RuggedBorder,
            hudPanelAlpha = 0.98f,
            borderWidth = 2.dp
        )
    }

    CompositionLocalProvider(LocalTacticalPalette provides tacticalPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = TacticalShapes,
            content = content
        )
    }
}
