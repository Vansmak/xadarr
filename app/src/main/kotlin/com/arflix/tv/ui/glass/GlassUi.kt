package com.arflix.tv.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Shared pieces of the glass look for the restyled screens (phase 2, 2026-10-25).

/**
 * [fallback] when another theme is on, else [pick] from the glass look in effect. Swapping a
 * hardcoded colour for `glassOr(old) { ink }` keeps every other theme exactly as it was.
 */
@Composable
@ReadOnlyComposable
fun glassOr(fallback: Color, pick: GlassPalette.() -> Color): Color = LocalGlass.current?.pick() ?: fallback

/** Opaque surface for dialogs, menus and sheets: near-white by day, the launcher's smoke at night. */
val GlassPalette.sheet: Color get() = lerp(Color(0xFFF7F7F9), Color(0xFF1C1C1E), night)

/** A raised opaque surface on a [sheet] (input fields, rows). */
val GlassPalette.sheetRaised: Color get() = lerp(Color(0xFFFFFFFF), Color(0xFF2C2C2E), night)

/**
 * Panels drawn over live video (HUD, channel badge, clock, player controls): far more opaque than
 * glass so bright video can't bleed through (Joe, 2026-10-25) -- ~92% white by day, ~88% smoke at night.
 */
val GlassPalette.overVideo: Color get() = lerp(Color.White.copy(alpha = 0.92f), Color(0xFF1C1C1E).copy(alpha = 0.88f), night)

/** Secondary text over video: never lighter than #3A3A3C by day / darker than #D1D1D6 at night. */
val GlassPalette.overVideoDim: Color get() = lerp(Color(0xFF3A3A3C), Color(0xFFD1D1D6), night)

/**
 * Focus on an opaque [sheet]: the white pill at night; by day a white pill would vanish on the
 * near-white sheet, so it inverts to an ink pill with white text.
 */
val GlassPalette.sheetFocus: Color get() = lerp(Color(0xFF1D1D1F), Color.White, night)
val GlassPalette.sheetFocusInk: Color get() = lerp(Color.White, Color(0xFF1D1D1F), night)

/** Dim behind a dialog. */
val GlassPalette.scrim: Color get() = Color.Black.copy(alpha = 0.22f + 0.33f * night)

/**
 * The launcher's page: the frosted vertical gradient and, at night, its night sky
 * (Shaders.NIGHT 0x070B1E / 0x16225A / 0x4B2F8F) as soft blobs.
 */
fun Modifier.glassSky(g: GlassPalette): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(g.bgTop, g.bgBottom)))
    val n = g.night
    if (n > 0f) clipRect {
        skyBlob(Color(0xFF4B2F8F).copy(alpha = 0.28f * n), 0.55f, 0.12f, 0.60f, 0.55f)
        skyBlob(Color(0xFF16225A).copy(alpha = 0.45f * n), 0.92f, 0.95f, 0.45f, 0.50f)
        skyBlob(Color(0xFF070B1E).copy(alpha = 0.60f * n), 0.04f, 0.70f, 0.50f, 0.60f)
    }
}

/** A CSS-style `radial-gradient(rx ry at cx cy, color, transparent 70%)`, fractions of the size. */
private fun DrawScope.skyBlob(color: Color, cx: Float, cy: Float, rx: Float, ry: Float) {
    val c = Offset(size.width * cx, size.height * cy)
    val r = size.width * rx
    scale(scaleX = 1f, scaleY = size.height * ry / r, pivot = c) {
        drawCircle(Brush.radialGradient(0f to color, 0.7f to Color.Transparent, center = c, radius = r), radius = r, center = c)
    }
}

/** A frosted glass panel: soft shadow, translucent fill, light rim. */
fun Modifier.glassPanel(g: GlassPalette, shape: Shape, strong: Boolean = false, elevation: Dp = 8.dp): Modifier =
    shadow(elevation, shape, ambientColor = g.shadow, spotColor = g.shadow)
        .clip(shape)
        .background(if (strong) g.glassStrong else g.glass)
        .border(1.dp, g.glassRim, shape)

/** An opaque dialog / menu sheet. */
fun Modifier.glassSheet(g: GlassPalette, shape: Shape): Modifier =
    shadow(24.dp, shape, ambientColor = g.shadowFocus, spotColor = g.shadowFocus)
        .clip(shape)
        .background(g.sheet)
        .border(1.dp, g.glassRim, shape)

/** The launcher's focus: a white pill (or disc) with a lift shadow; transparent when not focused. */
fun Modifier.glassFocusPill(g: GlassPalette, focused: Boolean, shape: Shape, rest: Color = Color.Transparent): Modifier =
    then(if (focused) Modifier.shadow(10.dp, shape, ambientColor = g.shadowFocus, spotColor = g.shadowFocus) else Modifier)
        .clip(shape)
        .background(if (focused) g.focusBg else rest)

/**
 * Inside a focused white pill, the glass ink tokens become the pill's dark ink, so text that
 * reads [GlassPalette.ink]/[GlassPalette.dim] stays legible at night too. No-op otherwise.
 */
@Composable
fun GlassFocusInk(focused: Boolean, content: @Composable () -> Unit) {
    val g = LocalGlass.current
    if (!focused || g == null) content()
    else CompositionLocalProvider(LocalGlass provides g.copy(ink = g.focusInk, dim = g.focusInkDim, faint = g.focusInkDim), content = content)
}
