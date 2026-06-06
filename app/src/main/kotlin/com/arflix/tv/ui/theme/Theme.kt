package com.arflix.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.arflix.tv.ui.skin.LocalFocusBorderColorOverride
import com.arflix.tv.ui.skin.ProvideArvioSkin
import com.arflix.tv.ui.skin.focusBorderColorFromName

/**
 * ARVIO Color scheme holder - Arctic Fuse 2 inspired
 * Minimal dark theme with light gray (#EDEDED) on pure black (#000000)
 */
data class ArvioColors(
    // Arctic Fuse 2 Main Colors
    val arcticWhite: androidx.compose.ui.graphics.Color = ArcticWhite,
    val arcticWhite90: androidx.compose.ui.graphics.Color = ArcticWhite90,
    val arcticWhite70: androidx.compose.ui.graphics.Color = ArcticWhite70,
    val arcticWhite50: androidx.compose.ui.graphics.Color = ArcticWhite50,
    val arcticBlack: androidx.compose.ui.graphics.Color = ArcticBlack,
    val arcticGray: androidx.compose.ui.graphics.Color = ArcticGray,
    
    // Legacy gradient colors (mapped to Arctic style)
    val cyan: androidx.compose.ui.graphics.Color = ArcticWhite,
    val cyanDark: androidx.compose.ui.graphics.Color = ArcticGray,
    val cyanGlow: androidx.compose.ui.graphics.Color = FocusGlow,
    val purple: androidx.compose.ui.graphics.Color = ArcticWhite,
    val purpleDark: androidx.compose.ui.graphics.Color = ArcticGray,
    val purpleGlow: androidx.compose.ui.graphics.Color = FocusGlow,
    val pink: androidx.compose.ui.graphics.Color = AccentWhite,
    val pinkDark: androidx.compose.ui.graphics.Color = ArcticGray,
    val pinkGlow: androidx.compose.ui.graphics.Color = FocusGlow,

    // Background colors
    val backgroundDark: androidx.compose.ui.graphics.Color = BackgroundDark,
    val backgroundCard: androidx.compose.ui.graphics.Color = BackgroundCard,
    val backgroundElevated: androidx.compose.ui.graphics.Color = BackgroundElevated,
    val backgroundGlass: androidx.compose.ui.graphics.Color = BackgroundGlass,

    // Text colors
    val textPrimary: androidx.compose.ui.graphics.Color = TextPrimary,
    val textSecondary: androidx.compose.ui.graphics.Color = TextSecondary,
    val textTertiary: androidx.compose.ui.graphics.Color = TextTertiary,

    // Border colors
    val borderLight: androidx.compose.ui.graphics.Color = BorderLight,
    val borderGradient: androidx.compose.ui.graphics.Color = BorderGradient,

    // Status colors
    val success: androidx.compose.ui.graphics.Color = SuccessGreen,
    val error: androidx.compose.ui.graphics.Color = ErrorRed,
    val warning: androidx.compose.ui.graphics.Color = WarningOrange,
    val info: androidx.compose.ui.graphics.Color = InfoBlue,

    // Special colors
    val imdbYellow: androidx.compose.ui.graphics.Color = ImdbYellow,
    val accentRed: androidx.compose.ui.graphics.Color = AccentRed,

    // Focus states (White for Arctic Fuse 2)
    val focusRing: androidx.compose.ui.graphics.Color = FocusRing,
    val focusGlow: androidx.compose.ui.graphics.Color = FocusGlow,

    // Particle colors (subtle white)
    val particleCyan: androidx.compose.ui.graphics.Color = ParticleCyan,
    val particlePurple: androidx.compose.ui.graphics.Color = ParticlePurple,
    val particlePink: androidx.compose.ui.graphics.Color = ParticlePink
)

val LocalArvioColors = staticCompositionLocalOf { ArvioColors() }
val LocalOledBlackBackground = staticCompositionLocalOf { false }

@Composable
fun appBackgroundDark(): Color = LocalArvioColors.current.backgroundDark

@Composable
fun appCardBackground(): Color = LocalArvioColors.current.backgroundCard

@Composable
fun appElevatedBackground(): Color = LocalArvioColors.current.backgroundElevated

@Composable
fun appTextPrimary(): Color = LocalArvioColors.current.textPrimary

@Composable
fun appTextSecondary(): Color = LocalArvioColors.current.textSecondary

// Keep legacy aliases for compatibility
val LocalArflixColors = LocalArvioColors

private data class ThemePalette(
    val background: Color,
    val card: Color,
    val elevated: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val borderLight: Color,
)

private fun paletteForTheme(name: String, oledBlack: Boolean): ThemePalette = when (name) {
    "Owl" -> ThemePalette(
        background = if (oledBlack) Color.Black else Color(0xFF1A1206),
        card = Color(0xFF221809),
        elevated = Color(0xFF2D2010),
        textPrimary = Color(0xFFF5ECD8),
        textSecondary = Color(0xFFF5ECD8).copy(alpha = 0.7f),
        textTertiary = Color(0xFFF5ECD8).copy(alpha = 0.5f),
        accent = Color(0xFFF0A500),
        borderLight = Color(0xFFF0A500).copy(alpha = 0.18f),
    )
    "Black & Gold" -> ThemePalette(
        background = Color.Black,
        card = Color(0xFF0A0A0A),
        elevated = Color(0xFF141414),
        textPrimary = Color(0xFFF5F5F5),
        textSecondary = Color(0xFFF5F5F5).copy(alpha = 0.7f),
        textTertiary = Color(0xFFF5F5F5).copy(alpha = 0.5f),
        accent = Color(0xFFD4AF37),
        borderLight = Color(0xFFD4AF37).copy(alpha = 0.20f),
    )
    "Neon" -> ThemePalette(
        background = if (oledBlack) Color.Black else Color(0xFF050505),
        card = Color(0xFF0A0A10),
        elevated = Color(0xFF12121A),
        textPrimary = Color(0xFFE8E8F0),
        textSecondary = Color(0xFFE8E8F0).copy(alpha = 0.7f),
        textTertiary = Color(0xFFE8E8F0).copy(alpha = 0.5f),
        accent = Color(0xFF00FF88),
        borderLight = Color(0xFF00FF88).copy(alpha = 0.15f),
    )
    else -> ThemePalette( // "Midnight" — default
        background = if (oledBlack) Color.Black else Color(0xFF0D1B2A),
        card = Color(0xFF151E2B),
        elevated = Color(0xFF1E2D3E),
        textPrimary = Color(0xFFE8EEF5),
        textSecondary = Color(0xFFE8EEF5).copy(alpha = 0.7f),
        textTertiary = Color(0xFFE8EEF5).copy(alpha = 0.5f),
        accent = Color(0xFF4A9EFF),
        borderLight = Color(0xFF4A9EFF).copy(alpha = 0.18f),
    )
}

private fun arvioColorsFromPalette(p: ThemePalette): ArvioColors = ArvioColors(
    arcticWhite = p.textPrimary,
    arcticWhite90 = p.textPrimary.copy(alpha = 0.9f),
    arcticWhite70 = p.textSecondary,
    arcticWhite50 = p.textTertiary,
    arcticBlack = p.background,
    arcticGray = p.elevated,
    cyan = p.accent,
    cyanDark = p.elevated,
    cyanGlow = p.accent.copy(alpha = 0.2f),
    purple = p.accent,
    purpleDark = p.elevated,
    purpleGlow = p.accent.copy(alpha = 0.2f),
    pink = p.accent,
    pinkDark = p.elevated,
    pinkGlow = p.accent.copy(alpha = 0.2f),
    backgroundDark = p.background,
    backgroundCard = p.card,
    backgroundElevated = p.elevated,
    backgroundGlass = p.background.copy(alpha = 0.6f),
    textPrimary = p.textPrimary,
    textSecondary = p.textSecondary,
    textTertiary = p.textTertiary,
    borderLight = p.borderLight,
    borderGradient = p.accent.copy(alpha = 0.5f),
    focusRing = p.accent,
    focusGlow = p.accent.copy(alpha = 0.2f),
    particleCyan = p.accent.copy(alpha = 0.3f),
    particlePurple = p.accent.copy(alpha = 0.12f),
    particlePink = p.accent.copy(alpha = 0.3f),
)

/**
 * Main ARVIO TV theme - Arctic Fuse 2 inspired
 * Pure black background, light gray text, white focus states
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ArvioTvTheme(
    oledBlackBackground: Boolean = false,
    focusBorderColorName: String? = null,
    themeName: String = "Midnight",
    content: @Composable () -> Unit
) {
    val palette = paletteForTheme(themeName, oledBlackBackground)
    val focusBorderColor = focusBorderColorName?.let { focusBorderColorFromName(it) } ?: palette.accent
    val colorScheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.background,
        primaryContainer = palette.elevated,
        onPrimaryContainer = palette.textPrimary,
        secondary = palette.textSecondary,
        onSecondary = palette.background,
        secondaryContainer = palette.elevated,
        onSecondaryContainer = palette.textPrimary,
        tertiary = palette.accent.copy(alpha = 0.7f),
        onTertiary = palette.background,
        tertiaryContainer = palette.elevated,
        onTertiaryContainer = palette.textPrimary,
        background = palette.background,
        onBackground = palette.textPrimary,
        surface = palette.card,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.elevated,
        onSurfaceVariant = palette.textSecondary,
        error = ErrorRed,
        onError = palette.textPrimary,
        border = palette.borderLight
    )

    val arvioColors = arvioColorsFromPalette(palette)

    CompositionLocalProvider(
        LocalArvioColors provides arvioColors,
        LocalOledBlackBackground provides oledBlackBackground,
        LocalFocusBorderColorOverride provides focusBorderColor
    ) {
        ProvideArvioSkin {
            MaterialTheme(
                colorScheme = colorScheme,
                content = content
            )
        }
    }
}

// Legacy alias for compatibility
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ArflixTvTheme(
    oledBlackBackground: Boolean = false,
    focusBorderColorName: String? = null,
    themeName: String = "Midnight",
    content: @Composable () -> Unit
) = ArvioTvTheme(
    oledBlackBackground = oledBlackBackground,
    focusBorderColorName = focusBorderColorName,
    themeName = themeName,
    content = content
)

/**
 * Access custom ARVIO colors
 */
object ArvioTheme {
    val colors: ArvioColors
        @Composable
        get() = LocalArvioColors.current
}

// Legacy alias for compatibility
object ArflixTheme {
    val colors: ArvioColors
        @Composable
        get() = LocalArvioColors.current
}

// Type alias for backward compatibility
typealias ArflixColors = ArvioColors
