package com.arflix.tv.ui.glass

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay

// "Xadarr, glass edition" (Joe, 2026-10-25): the look of his home launcher (free-my-tv,
// HomeView/Ui/Shaders) -- frosted white glass by day, smoked glass from sunset to sunrise.
// This package depends on Compose only, so Xadarr Music (:musicapp) compiles it as-is.

/** Design tokens, day or night. Values are the launcher's (see the mockup's Notes table). */
@Immutable
data class GlassPalette(
    val isNight: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val bgBase: Color,
    val ink: Color,
    val dim: Color,
    val faint: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassRim: Color,
    val glassEdge: Color,
    val chip: Color,
    val cardEmpty: Color,
    val shadow: Color,
    val shadowFocus: Color,
    /** Focus is a white pill / disc with dark ink in both modes. */
    val focusBg: Color,
    val focusInk: Color,
    val focusInkDim: Color,
    val accent: Color,
    val live: Color,
    val up: Color,
    val down: Color,
    val track: Color,
    /** 0 = day, 1 = night; in between through twilight (backgrounds and glass follow it). */
    val night: Float = if (isNight) 1f else 0f,
    /**
     * [night] squeezed into its middle 20% (0.4-0.6): text, and the opaque surfaces text sits on
     * (sheets, panels over video), switch quickly in the middle of twilight instead of passing
     * through mid-grey on mid-grey while the background takes the whole hour.
     */
    val textNight: Float = night,
    /** The sky behind the glass (glassSky): top, middle (55%) and horizon, plus star strength. */
    val skyTop: Color = bgTop,
    val skyMid: Color = bgBase,
    val skyHorizon: Color = bgBottom,
    val stars: Float = 0f,
)

val GlassDay = GlassPalette(
    isNight = false,
    bgTop = Color(0xFFF5F5F7),
    bgBottom = Color(0xFFE2E2E7),
    bgBase = Color(0xFFEBEBF0),
    ink = Color(0xFF1D1D1F),
    dim = Color(0xFF6E6E73),
    faint = Color(0xFF8E8E93),
    glass = Color.White.copy(alpha = 0.55f),
    glassStrong = Color.White.copy(alpha = 0.80f),
    glassRim = Color.White.copy(alpha = 0.95f),
    glassEdge = Color.Black.copy(alpha = 0.04f),
    chip = Color.Black.copy(alpha = 0.06f),
    cardEmpty = Color(0xFFDADADF),
    shadow = Color.Black.copy(alpha = 0.12f),
    shadowFocus = Color.Black.copy(alpha = 0.34f),
    focusBg = Color.White,
    focusInk = Color(0xFF1D1D1F),
    focusInkDim = Color(0xFF6E6E73),
    accent = Color(0xFF007AFF),
    live = Color(0xFFFF3B30),
    up = Color(0xFF248A3D),
    down = Color(0xFFD70015),
    track = Color(0xFF1D1D1F).copy(alpha = 0.14f),
)

val GlassNight = GlassPalette(
    isNight = true,
    bgTop = Color(0xFF1C1C1E),
    bgBottom = Color(0xFF0B0B0D),
    bgBase = Color(0xFF141416),
    ink = Color(0xFFF5F5F7),
    dim = Color(0xFF98989D),
    faint = Color.White.copy(alpha = 0.60f),
    glass = Color.White.copy(alpha = 0.07f),
    glassStrong = Color.White.copy(alpha = 0.09f),
    glassRim = Color.White.copy(alpha = 0.14f),
    glassEdge = Color.Black.copy(alpha = 0.30f),
    chip = Color.White.copy(alpha = 0.14f),
    cardEmpty = Color(0xFF2C2C2E),
    shadow = Color.Black.copy(alpha = 0.32f),
    shadowFocus = Color.Black.copy(alpha = 0.60f),
    focusBg = Color.White,
    focusInk = Color(0xFF1D1D1F),
    focusInkDim = Color(0xFF6E6E73),
    accent = Color(0xFF0A84FF),
    live = Color(0xFFFF453A),
    up = Color(0xFF30D158),
    down = Color(0xFFFF453A),
    track = Color.White.copy(alpha = 0.18f),
)

/** Twilight level → text level: 0 below 0.4, 1 above 0.6, smooth in between. */
fun glassTextLevel(t: Float): Float {
    val x = ((t - 0.4f) / 0.2f).coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

/**
 * Day → night blend, t in 0..1 (twilight). Backgrounds and glass blend linearly; text colours use
 * [glassTextLevel] so they never sit mid-grey. Sky colours are set by [withSky].
 */
fun lerpGlass(a: GlassPalette, b: GlassPalette, t: Float): GlassPalette = if (t <= 0f) a else if (t >= 1f) b else GlassPalette(
    isNight = t >= 0.5f,
    bgTop = lerp(a.bgTop, b.bgTop, t),
    bgBottom = lerp(a.bgBottom, b.bgBottom, t),
    bgBase = lerp(a.bgBase, b.bgBase, t),
    ink = lerp(a.ink, b.ink, glassTextLevel(t)),
    dim = lerp(a.dim, b.dim, glassTextLevel(t)),
    faint = lerp(a.faint, b.faint, glassTextLevel(t)),
    glass = lerp(a.glass, b.glass, t),
    glassStrong = lerp(a.glassStrong, b.glassStrong, t),
    glassRim = lerp(a.glassRim, b.glassRim, t),
    glassEdge = lerp(a.glassEdge, b.glassEdge, t),
    chip = lerp(a.chip, b.chip, t),
    cardEmpty = lerp(a.cardEmpty, b.cardEmpty, t),
    shadow = lerp(a.shadow, b.shadow, t),
    shadowFocus = lerp(a.shadowFocus, b.shadowFocus, t),
    focusBg = lerp(a.focusBg, b.focusBg, t),
    focusInk = lerp(a.focusInk, b.focusInk, t),
    focusInkDim = lerp(a.focusInkDim, b.focusInkDim, t),
    accent = lerp(a.accent, b.accent, t),
    live = lerp(a.live, b.live, t),
    up = lerp(a.up, b.up, t),
    down = lerp(a.down, b.down, t),
    track = lerp(a.track, b.track, t),
    night = a.night + (b.night - a.night) * t,
    textNight = a.textNight + (b.textNight - a.textNight) * glassTextLevel(t),
)

/**
 * The launcher's sky (free-my-tv HomeView.skyColors) for this palette's night level and the sun:
 * pale blue by day (deeper with the sun high), warm at the horizon near sunrise and sunset,
 * indigo with stars at night.
 */
fun GlassPalette.withSky(sun: SunClock.Sun): GlassPalette {
    val n = night
    val dTop = lerp(Color(0xFFCEDEF6), Color(0xFFBED6F8), sun.elev)
    val dMid = Color(0xFFE8EDF7)
    val dHor = lerp(Color(0xFFF5F0F2), Color(0xFFFFD2B0), sun.glow * 0.9f)
    val nTop = Color(0xFF090C24)
    val nMid = Color(0xFF1A205C)
    val nHor = lerp(Color(0xFF482E8C), Color(0xFFD6785C), sun.glow * 0.7f)
    return copy(
        skyTop = lerp(dTop, nTop, n),
        skyMid = lerp(dMid, nMid, n),
        skyHorizon = lerp(dHor, nHor, n),
        stars = n * (if (sun.up) maxOf(0f, 0.25f - sun.elev) * 4f else 1f),
    )
}

/**
 * The glass look in effect right now (day or night), or null when another theme is selected.
 * The theme's colour scheme follows it too; screens with fixed dark colours swap them through
 * glassOr (GlassUi.kt). Dynamic (not static) so the crossfade only recomposes its readers.
 */
val LocalGlass = compositionLocalOf<GlassPalette?> { null }

/** Glass Day/Night setting values. */
object GlassMode {
    const val AUTO = "auto"
    const val DAY = "day"
    const val NIGHT = "night"

    fun label(mode: String): String = when (mode) {
        DAY -> "Day"
        NIGHT -> "Night"
        else -> "Auto"
    }

    fun next(mode: String): String = when (mode) {
        AUTO -> DAY
        DAY -> NIGHT
        else -> AUTO
    }
}

/**
 * The glass look for [mode] right now. "auto" follows the sun continuously like the launcher
 * (Joe, 2026-11-01): an hour of twilight around sunset and sunrise instead of a switch, re-checked
 * every minute and eased over a moment when it moves. Day and Night are fixed levels; the sky
 * still follows the real sun.
 */
@Composable
fun rememberGlassPalette(mode: String): GlassPalette {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mode) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val level = remember(mode, now) { SunClock.nightLevel(mode, now) }
    val sun = remember(now) { SunClock.sun(now) }
    val t by animateFloatAsState(
        targetValue = level,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "glass-twilight",
    )
    return remember(t, sun) { lerpGlass(GlassDay, GlassNight, t).withSky(sun) }
}
