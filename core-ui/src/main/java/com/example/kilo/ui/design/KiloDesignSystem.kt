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
    // Shared spacing scale used by both the existing UI and responsive layouts.
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    // Keep the original names available to existing screens and components.
    val compact = xs
    val standard = md
    val spacious = lg
    val section = xl
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
