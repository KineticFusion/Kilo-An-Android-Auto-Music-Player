package com.example.kilo.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class KiloDesignTokens(
    val accent: Color = Color(0xFFB783FF),
    val background: Color = Color(0xFF090A0F),
    val surface: Color = Color(0xFF171821),
    val foreground: Color = Color(0xFFF7F7FB),
    val muted: Color = Color(0xFFB5B7C2)
)

data class KiloThemeSpec(
    val accent: Color = KiloDesignTokens().accent,
    val background: Color = KiloDesignTokens().background,
    val surface: Color = KiloDesignTokens().surface,
    val foreground: Color = KiloDesignTokens().foreground,
    val muted: Color = KiloDesignTokens().muted
)

val LocalKiloDesign = staticCompositionLocalOf { KiloDesignTokens() }

object KiloSpacing {
    val compact = 8.dp
    val standard = 16.dp
    val spacious = 24.dp
    val section = 32.dp
}

object KiloCorners {
    val small = 12.dp
    val card = 22.dp
    val hero = 30.dp
}

object KiloTypeScale {
    val headingXL: TextUnit = 30.sp
    val headingLarge: TextUnit = 24.sp
    val headingMedium: TextUnit = 18.sp
    val body: TextUnit = 14.sp
    val caption: TextUnit = 12.sp

    fun heading(size: TextUnit = headingLarge) = TextStyle(
        fontSize = size,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun KiloTheme(
    spec: KiloThemeSpec = KiloThemeSpec(),
    content: @Composable () -> Unit
) {
    val tokens = KiloDesignTokens(
        accent = spec.accent,
        background = spec.background,
        surface = spec.surface,
        foreground = spec.foreground,
        muted = spec.muted
    )
    val colorScheme = darkColorScheme(
        primary = tokens.accent,
        secondary = tokens.accent,
        background = tokens.background,
        surface = tokens.surface,
        onBackground = tokens.foreground,
        onSurface = tokens.foreground
    )
    CompositionLocalProvider(LocalKiloDesign provides tokens) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
