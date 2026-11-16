package com.example.kilo.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import kotlin.math.pow

/**
 * Material Expressive-inspired Theme System for Kilo
 *
 * This adds Google's Material 3 Expressive design language concepts as an option
 * alongside Kilo's existing SkinTheme system.
 *
 * Material Expressive features implemented:
 * - Bolder, more expressive color roles
 * - Enhanced elevation and shadow system
 * - Expressive typography scale
 * - Dynamic shape system
 * - Motion and animation enhancements
 */

sealed interface ExpressiveThemeVariant {
    data class Spotlight(
        val primary: Color,
        val secondary: Color,
        val tertiary: Color,
        val surface: Color,
        val background: Color
    ) : ExpressiveThemeVariant

    data class Bold(
        val primary: Color,
        val secondary: Color,
        val tertiary: Color,
        val surface: Color,
        val background: Color
    ) : ExpressiveThemeVariant

    data class Calm(
        val primary: Color,
        val secondary: Color,
        val tertiary: Color,
        val surface: Color,
        val background: Color
    ) : ExpressiveThemeVariant

    data class Vibrant(
        val primary: Color,
        val secondary: Color,
        val tertiary: Color,
        val surface: Color,
        val background: Color
    ) : ExpressiveThemeVariant
}

/**
 * Predefined Expressive theme variants for Kilo
 */
object ExpressiveThemePresets {
    // Spotify-inspired
    val SPOTIFY_EXPRESSIVE = ExpressiveThemeVariant.Spotlight(
        primary = Color(0xFF1DB954),
        secondary = Color(0xFF1ED760),
        tertiary = Color(0xFF1AA34A),
        surface = Color(0xFF181818),
        background = Color(0xFF121212)
    )

    // YouTube Music-inspired
    val YT_MUSIC_EXPRESSIVE = ExpressiveThemeVariant.Vibrant(
        primary = Color(0xFFFF0000),
        secondary = Color(0xFFFF3355),
        tertiary = Color(0xFFCC0000),
        surface = Color(0xFF1E1E1E),
        background = Color(0xFF121212)
    )

    // Kilo Sunset Glass (existing)
    val SUNSET_GLASS_EXPRESSIVE = ExpressiveThemeVariant.Spotlight(
        primary = Color(0xFFB783FF),
        secondary = Color(0xFFDA70D6),
        tertiary = Color(0xFF8A2BE2),
        surface = Color(0xFF1B1326),
        background = Color(0xFF0A0C12)
    )

    // Ocean/Teal
    val OCEAN_EXPRESSIVE = ExpressiveThemeVariant.Calm(
        primary = Color(0xFF00B894),
        secondary = Color(0xFF00E5FF),
        tertiary = Color(0xFF009688),
        surface = Color(0xFF0A1A1A),
        background = Color(0xFF051010)
    )

    // Rose/Pink
    val ROSE_EXPRESSIVE = ExpressiveThemeVariant.Vibrant(
        primary = Color(0xFFFF5F9E),
        secondary = Color(0xFFFF7AC8),
        tertiary = Color(0xFFE91E63),
        surface = Color(0xFF2A111C),
        background = Color(0xFF171018)
    )

    // Gold/Amber
    val GOLD_EXPRESSIVE = ExpressiveThemeVariant.Bold(
        primary = Color(0xFFFFC857),
        secondary = Color(0xFFFFD54F),
        tertiary = Color(0xFFF9A825),
        surface = Color(0xFF2A2410),
        background = Color(0xFF1A160A)
    )

    // Indigo/Deep Blue
    val INDIGO_EXPRESSIVE = ExpressiveThemeVariant.Spotlight(
        primary = Color(0xFF7185FF),
        secondary = Color(0xFF90A4FF),
        tertiary = Color(0xFF5C6BC0),
        surface = Color(0xFF1A1A2E),
        background = Color(0xFF0F0F1A)
    )

    // Coral/Orange
    val CORAL_EXPRESSIVE = ExpressiveThemeVariant.Vibrant(
        primary = Color(0xFFFF6B6B),
        secondary = Color(0xFFFF8E8E),
        tertiary = Color(0xFFE57373),
        surface = Color(0xFF2A1515),
        background = Color(0xFF1A0D0D)
    )

    // All presets
    val ALL = listOf(
        "Spotify Expressive" to SPOTIFY_EXPRESSIVE,
        "YouTube Music Expressive" to YT_MUSIC_EXPRESSIVE,
        "Sunset Glass Expressive" to SUNSET_GLASS_EXPRESSIVE,
        "Ocean Expressive" to OCEAN_EXPRESSIVE,
        "Rose Expressive" to ROSE_EXPRESSIVE,
        "Gold Expressive" to GOLD_EXPRESSIVE,
        "Indigo Expressive" to INDIGO_EXPRESSIVE,
        "Coral Expressive" to CORAL_EXPRESSIVE
    )
}

/**
 * Compute relative luminance of a color (0.0 to 1.0)
 */
fun Color.luminance(): Float {
    val r = red / 255f
    val g = green / 255f
    val b = blue / 255f
    val rs = if (r <= 0.03928f) r / 12.92f else ((r + 0.055f) / 1.055f).pow(2.4f)
    val gs = if (g <= 0.03928f) g / 12.92f else ((g + 0.055f) / 1.055f).pow(2.4f)
    val bs = if (b <= 0.03928f) b / 12.92f else ((b + 0.055f) / 1.055f).pow(2.4f)
    return (0.2126f * rs + 0.7152f * gs + 0.0722f * bs)
}

/**
 * Creates an Expressive color scheme from a variant
 */
fun expressiveColorScheme(
    variant: ExpressiveThemeVariant,
    isDark: Boolean = true
) = when (variant) {
    is ExpressiveThemeVariant.Spotlight -> darkColorScheme(
        primary = variant.primary,
        secondary = variant.secondary,
        tertiary = variant.tertiary,
        surface = variant.surface,
        background = variant.background,
        onPrimary = if (variant.primary.luminance() > 0.5f) Color.Black else Color.White,
        onSecondary = if (variant.secondary.luminance() > 0.5f) Color.Black else Color.White,
        onTertiary = if (variant.tertiary.luminance() > 0.5f) Color.Black else Color.White,
        onSurface = Color.White,
        onBackground = Color.White,
        surfaceContainerHighest = variant.surface.copy(alpha = 0.9f),
        surfaceContainerHigh = variant.surface.copy(alpha = 0.8f),
        surfaceContainer = variant.surface.copy(alpha = 0.7f),
        surfaceContainerLow = variant.surface.copy(alpha = 0.5f),
        surfaceContainerLowest = variant.surface.copy(alpha = 0.3f)
    )
    is ExpressiveThemeVariant.Bold -> darkColorScheme(
        primary = variant.primary,
        secondary = variant.secondary,
        tertiary = variant.tertiary,
        surface = variant.surface,
        background = variant.background,
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onTertiary = Color.Black,
        onSurface = Color.White,
        onBackground = Color.White,
        surfaceContainerHighest = variant.surface.copy(alpha = 0.95f),
        surfaceContainerHigh = variant.surface.copy(alpha = 0.9f),
        surfaceContainer = variant.surface.copy(alpha = 0.8f),
        surfaceContainerLow = variant.surface.copy(alpha = 0.6f),
        surfaceContainerLowest = variant.surface.copy(alpha = 0.4f),
        outline = variant.primary.copy(alpha = 0.5f),
        outlineVariant = variant.primary.copy(alpha = 0.3f)
    )
    is ExpressiveThemeVariant.Calm -> darkColorScheme(
        primary = variant.primary,
        secondary = variant.secondary,
        tertiary = variant.tertiary,
        surface = variant.surface,
        background = variant.background,
        onPrimary = Color.White,
        onSecondary = Color.White,
        onTertiary = Color.White,
        onSurface = Color.White.copy(alpha = 0.9f),
        onBackground = Color.White.copy(alpha = 0.9f),
        surfaceContainerHighest = variant.surface.copy(alpha = 0.8f),
        surfaceContainerHigh = variant.surface.copy(alpha = 0.7f),
        surfaceContainer = variant.surface.copy(alpha = 0.6f),
        surfaceContainerLow = variant.surface.copy(alpha = 0.5f),
        surfaceContainerLowest = variant.surface.copy(alpha = 0.4f),
        outline = Color.White.copy(alpha = 0.2f),
        outlineVariant = Color.White.copy(alpha = 0.1f)
    )
    is ExpressiveThemeVariant.Vibrant -> darkColorScheme(
        primary = variant.primary,
        secondary = variant.secondary,
        tertiary = variant.tertiary,
        surface = variant.surface,
        background = variant.background,
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onTertiary = Color.Black,
        onSurface = Color.White,
        onBackground = Color.White,
        surfaceContainerHighest = variant.surface.copy(alpha = 0.9f),
        surfaceContainerHigh = variant.surface.copy(alpha = 0.8f),
        surfaceContainer = variant.surface.copy(alpha = 0.7f),
        surfaceContainerLow = variant.surface.copy(alpha = 0.5f),
        surfaceContainerLowest = variant.surface.copy(alpha = 0.3f),
        inverseSurface = variant.background,
        inverseOnSurface = Color.White,
        inversePrimary = variant.primary
    )
}

/**
 * Expressive shape system
 */
data class ExpressiveShapes(
    val extraSmall: androidx.compose.ui.graphics.Shape = RoundedCornerShape(4.dp),
    val small: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    val medium: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    val large: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    val extraLarge: androidx.compose.ui.graphics.Shape = RoundedCornerShape(32.dp),
    val full: androidx.compose.ui.graphics.Shape = CircleShape
) {
    companion object {
        val DEFAULT = ExpressiveShapes()
        val ROUNDED = ExpressiveShapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(32.dp),
            extraLarge = RoundedCornerShape(40.dp)
        )
        val PILLED = ExpressiveShapes(
            extraSmall = RoundedCornerShape(100.dp),
            small = RoundedCornerShape(100.dp),
            medium = RoundedCornerShape(100.dp),
            large = RoundedCornerShape(100.dp),
            extraLarge = RoundedCornerShape(100.dp)
        )
        val SHARP = ExpressiveShapes(
            extraSmall = RoundedCornerShape(0.dp),
            small = RoundedCornerShape(0.dp),
            medium = RoundedCornerShape(4.dp),
            large = RoundedCornerShape(8.dp),
            extraLarge = RoundedCornerShape(12.dp)
        )
    }
}

/**
 * Expressive typography scale
 */
data class ExpressiveTypography(
    val displayLarge: TextStyle = TextStyle(
        fontSize = 57.sp,
        fontWeight = FontWeight.W400,
        lineHeight = 64.sp,
        letterSpacing = -0.25.sp
    ),
    val displayMedium: TextStyle = TextStyle(
        fontSize = 45.sp,
        fontWeight = FontWeight.W400,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    val displaySmall: TextStyle = TextStyle(
        fontSize = 36.sp,
        fontWeight = FontWeight.W400,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    val headlineLarge: TextStyle = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    val headlineMedium: TextStyle = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    val headlineSmall: TextStyle = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    val titleLarge: TextStyle = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    val titleMedium: TextStyle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    val titleSmall: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    val labelLarge: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    val labelMedium: TextStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    val labelSmall: TextStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    val bodyLarge: TextStyle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.W400,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    val bodyMedium: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W400,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    val bodySmall: TextStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.W400,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
) {
    companion object {
        val DEFAULT = ExpressiveTypography()
        val COMPACT = ExpressiveTypography(
            bodyLarge = DEFAULT.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
            bodyMedium = DEFAULT.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            bodySmall = DEFAULT.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
        )
        val COMFORTABLE = ExpressiveTypography(
            bodyLarge = DEFAULT.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp),
            bodyMedium = DEFAULT.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodySmall = DEFAULT.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp)
        )
    }
}

/**
 * Main Expressive Theme Composable
 */
@Composable
fun ExpressiveTheme(
    variant: ExpressiveThemeVariant = ExpressiveThemePresets.SPOTIFY_EXPRESSIVE,
    shapes: ExpressiveShapes = ExpressiveShapes.DEFAULT,
    typography: ExpressiveTypography = ExpressiveTypography.DEFAULT,
    isDark: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = expressiveColorScheme(variant, isDark)
    val materialTypography = remember { typography.toMaterialTypography() }

    // Use default Material3 shapes, custom shapes can be applied individually via ExpressiveShapes
    MaterialTheme(
        colorScheme = colorScheme,
        typography = materialTypography,
        content = content
    )
}

private fun ExpressiveTypography.toMaterialTypography(): Typography {
    return Typography(
        displayLarge = this.displayLarge,
        displayMedium = this.displayMedium,
        displaySmall = this.displaySmall,
        headlineLarge = this.headlineLarge,
        headlineMedium = this.headlineMedium,
        headlineSmall = this.headlineSmall,
        titleLarge = this.titleLarge,
        titleMedium = this.titleMedium,
        titleSmall = this.titleSmall,
        labelLarge = this.labelLarge,
        labelMedium = this.labelMedium,
        labelSmall = this.labelSmall,
        bodyLarge = this.bodyLarge,
        bodyMedium = this.bodyMedium,
        bodySmall = this.bodySmall
    )
}

/**
 * Expressive elevation system
 */
object ExpressiveElevation {
    val LEVEL_0 = 0.dp
    val LEVEL_1 = 1.dp   // Surface
    val LEVEL_2 = 3.dp   // Card
    val LEVEL_3 = 6.dp   // Elevated card
    val LEVEL_4 = 12.dp  // Modal bottom sheet
    val LEVEL_5 = 24.dp  // Dialog, navigation drawer

    // Expressive shadows (more pronounced)
    val EXPRESSIVE_1 = 4.dp
    val EXPRESSIVE_2 = 8.dp
    val EXPRESSIVE_3 = 16.dp
    val EXPRESSIVE_4 = 24.dp
    val EXPRESSIVE_5 = 32.dp
}

/**
 * Expressive motion tokens
 */
object ExpressiveMotion {
    val DURATION_SHORT = 150
    val DURATION_MEDIUM = 300
    val DURATION_LONG = 500
}