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
    /**
     * 0 = day, 1 = night for every surface and text colour. During twilight it switches as the
     * sun's level crosses 0.5 (eased over a moment), so a panel and the text on it always flip
     * together -- a linear glass blend under switched text was unreadable at dusk (Joe,
     * 2026-11-01). Only the sky ([skyNight]) takes the whole hour.
     */
    val night: Float = if (isNight) 1f else 0f,
    /** The sun's twilight level, 0..1 over the hour around sunset/sunrise: the sky and stars only. */
    val skyNight: Float = night,
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

/**
 * Night side just after the switch, while the sky ([t] = its level) is still light lavender:
 * plain night glass (white 7%) and grey secondary text (#98989D) vanish on it -- Joe's "unreadable"
 * photos at dusk, 2026-11-01. So until the sky is nearly dark (0.75) the night glass carries a
 * smoke tint and dim/faint sit near white; both settle to normal night by 1.0. Measured worst-case
 * unfocused-text contrast over the dusk sky: 3.6 (full night: 4.0), 6+ for the first 20 minutes.
 */
private fun duskNight(n: GlassPalette, t: Float): GlassPalette {
    val x = ((t - 0.75f) / 0.25f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    if (x >= 1f) return n
    val near = Color(0xFFE5E5EA)
    val smoke = Color(0xFF1C1C1E).copy(alpha = 0.45f)
    return n.copy(
        dim = lerp(near, n.dim, x),
        faint = lerp(near, n.faint, x),
        glass = lerp(smoke, n.glass, x),
        glassStrong = lerp(smoke, n.glassStrong, x),
    )
}

/**
 * Day side just before the switch, while the sky ([t]) is already darkening toward lavender: grey
 * secondary text (#6E6E73 / #8E8E93) sitting straight on the sky loses contrast -- at level 0.3
 * the sky is already ~#98A5BC, where #8E8E93 is ~1.4:1 (2.45 check on the Office TV). So from
 * level 0.05 it deepens to #3A3A3C (~4.5:1 there) by 0.3 -- the mirror of [duskNight].
 */
private fun duskDay(d: GlassPalette, t: Float): GlassPalette {
    val x = ((t - 0.05f) / 0.25f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    if (x <= 0f) return d
    val deep = Color(0xFF3A3A3C)
    return d.copy(dim = lerp(d.dim, deep, x), faint = lerp(d.faint, deep, x))
}

/**
 * The palette for sky level [t] (0..1, the hour of twilight) and surface level [u] (0 = day,
 * 1 = night). Every surface and text colour blends by [u], which steps at t = 0.5 and only eases
 * for a moment, so a panel and the text on it always flip together; the sky ([skyNight]) follows
 * [t]. A level inside a narrow window (0.49-0.51) could hold half-switched for a minute between
 * clock updates, hence a step.
 */
fun lerpGlass(a: GlassPalette, b: GlassPalette, t: Float, u: Float): GlassPalette {
    val nb = duskNight(b, t)
    val da = duskDay(a, t)
    val base = if (u <= 0f) da else if (u >= 1f) nb else GlassPalette(
        isNight = u >= 0.5f,
        bgTop = lerp(da.bgTop, nb.bgTop, u),
        bgBottom = lerp(da.bgBottom, nb.bgBottom, u),
        bgBase = lerp(da.bgBase, nb.bgBase, u),
        ink = lerp(da.ink, nb.ink, u),
        dim = lerp(da.dim, nb.dim, u),
        faint = lerp(da.faint, nb.faint, u),
        glass = lerp(da.glass, nb.glass, u),
        glassStrong = lerp(da.glassStrong, nb.glassStrong, u),
        glassRim = lerp(da.glassRim, nb.glassRim, u),
        glassEdge = lerp(da.glassEdge, nb.glassEdge, u),
        chip = lerp(da.chip, nb.chip, u),
        cardEmpty = lerp(da.cardEmpty, nb.cardEmpty, u),
        shadow = lerp(da.shadow, nb.shadow, u),
        shadowFocus = lerp(da.shadowFocus, nb.shadowFocus, u),
        focusBg = lerp(da.focusBg, nb.focusBg, u),
        focusInk = lerp(da.focusInk, nb.focusInk, u),
        focusInkDim = lerp(da.focusInkDim, nb.focusInkDim, u),
        accent = lerp(da.accent, nb.accent, u),
        live = lerp(da.live, nb.live, u),
        up = lerp(da.up, nb.up, u),
        down = lerp(da.down, nb.down, u),
        track = lerp(da.track, nb.track, u),
        night = u,
    )
    return base.copy(skyNight = t.coerceIn(0f, 1f))
}

/**
 * The launcher's sky (free-my-tv HomeView.skyColors) for this palette's night level and the sun:
 * pale blue by day (deeper with the sun high), warm at the horizon near sunrise and sunset,
 * indigo with stars at night.
 */
fun GlassPalette.withSky(sun: SunClock.Sun): GlassPalette {
    val n = skyNight
    val dTop = lerp(Color(0xFFCEDEF6), Color(0xFFBED6F8), sun.elev)
    val dMid = Color(0xFFE8EDF7)
    val dHor = lerp(Color(0xFFF5F0F2), Color(0xFFFFD2B0), sun.glow * 0.9f)
    // Midnight blue most of the night; the violet/orange horizon only near sunset and sunrise
    // (launcher, 2026-11-01: Joe found the night "too purple").
    val nTop = Color(0xFF070B1E)
    val nMid = lerp(Color(0xFF0F1C45), Color(0xFF1A205C), sun.glow)
    val nHor = lerp(Color(0xFF1D3466), lerp(Color(0xFF482E8C), Color(0xFFD6785C), sun.glow * 0.7f), sun.glow)
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
    val level = remember(mode, now) { debugGlassLevel() ?: SunClock.nightLevel(mode, now) }
    val sun = remember(now) { SunClock.sun(now) }
    val t by animateFloatAsState(
        targetValue = level,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "glass-twilight",
    )
    val u by animateFloatAsState(
        targetValue = if (level >= 0.5f) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "glass-day-night",
    )
    return remember(t, u, sun) { lerpGlass(GlassDay, GlassNight, t, u).withSky(sun) }
}

/**
 * `adb shell setprop debug.xadarr.glass_level 0.5` forces the twilight level (picked up within a
 * minute) to check readability without waiting for dusk; `setprop debug.xadarr.glass_level ""`
 * goes back to the sun. Only adb can set debug.* properties, so nothing else reaches it.
 */
private fun debugGlassLevel(): Float? = runCatching {
    val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, "debug.xadarr.glass_level") as String
    v.toFloatOrNull()?.coerceIn(0f, 1f)
}.getOrNull()
