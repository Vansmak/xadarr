package com.arflix.tv.ui.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalXadarrSkinTokens = staticCompositionLocalOf { XadarrSkinTokens.defaults() }

/**
 * Optional override for the focus border colour, driven by the user's
 * "Focus border colour" setting. When non-null every [xadarrFocusable]
 * composable uses this colour instead of [XadarrColorTokens.focusOutline].
 */
val LocalFocusBorderColorOverride = staticCompositionLocalOf<Color?> { null }

/**
 * Resolves the effective focus border colour for a component that draws its
 * own focus border (for example, settings rows and glow chips) instead of
 * using the [xadarrFocusable] modifier. Returns the user's chosen override
 * when set, otherwise the provided fallback color.
 *
 * Call this inside a `@Composable` lambda to read the CompositionLocal.
 */
@Composable
fun resolveFocusBorderColor(fallback: Color): Color {
    return LocalFocusBorderColorOverride.current ?: fallback
}

/**
 * Maps a user-facing colour name to its [Color] value.
 * Used by the focus border colour setting and the colour picker.
 */
fun focusBorderColorFromName(name: String): Color = when (name) {
    "Red" -> Color(0xFFFF4444)
    "Orange" -> Color(0xFFFF8800)
    "Yellow" -> Color(0xFFFFDD44)
    "Green" -> Color(0xFF44CC44)
    "Blue" -> Color(0xFF4488FF)
    "Indigo" -> Color(0xFF6644CC)
    "Violet" -> Color(0xFFBB44CC)
    else -> Color(0xFFFFFFFF) // White (default)
}

@Composable
fun ProvideXadarrSkin(
    tokens: XadarrSkinTokens = XadarrSkinTokens.defaults(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalXadarrSkinTokens provides tokens,
        content = content,
    )
}

object XadarrSkin {
    val tokens: XadarrSkinTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalXadarrSkinTokens.current

    val colors: XadarrColorTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.colors

    val spacing: XadarrSpacingTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.spacing

    val radius: XadarrRadiusTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.radius

    val typography: XadarrTypographyTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.typography

    val motion: XadarrMotionTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.motion

    val focus: XadarrFocusTokens
        @Composable
        @ReadOnlyComposable
        get() = tokens.focus
}

