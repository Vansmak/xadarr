package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.ui.glass.GlassPalette
import com.arflix.tv.ui.glass.LocalGlass
import com.arflix.tv.ui.glass.overVideo
import com.arflix.tv.ui.glass.overVideoDim
import com.arflix.tv.ui.glass.sheetRaised
import com.arflix.tv.ui.skin.LocalFocusBorderColorOverride
import com.arflix.tv.ui.theme.InterFontFamily

// Xadarr Live TV — design tokens. OKLCH reference kept in spec.md §2.
// Mapped from handoff/tokens.kt. `InterFontFamily` ships; JetBrains Mono
// falls back to system Monospace (Inter's tabular figures are acceptable
// for the numeric/badge slots; can swap for bundled JBMono later).

/**
 * Guide colours. The default is the original near-black guide; the Glass theme swaps in
 * [livePaletteForGlass] (day or night) through [LocalLivePalette], so every `LiveColors.X`
 * read inside the guide follows it. Outside a provider (Cameras, Plex library, Home cards)
 * nothing changes.
 */
@Immutable
data class LivePalette(
    val bg: Color,
    val panel: Color,
    val panelDeep: Color,
    val panelRaised: Color,
    val rowStripe: Color,
    val divider: Color,
    val dividerStrong: Color,
    val fg: Color,
    val fgDim: Color,
    val fgMute: Color,
    val accent: Color,
    val accentDim: Color,
    val focusBg: Color,
    val focusRing: Color,
    val liveRed: Color,
    val online: Color,
    // Glass only: focus is a white pill with dark ink instead of a ring.
    val isGlass: Boolean = false,
    val focusFill: Color = panelRaised,
    val focusInk: Color = fg,
    val focusInkDim: Color = fgDim,
    val shadow: Color = Color.Transparent,
    val shadowFocus: Color = Color.Transparent,
    val bgTop: Color = bg,
    val bgBottom: Color = bg,
    // Over live video: an opaque panel and full-contrast text (glass is far more opaque here).
    val videoPanel: Color = panelDeep.copy(alpha = 0.85f),
    val videoInk: Color = fg,
    val videoDim: Color = fgDim,
)

// Unified near-black palette so the AppTopBar gradient blends cleanly
// into the TV page. Each step lifts just a few lumens above the last —
// enough for panels/cells to pop without creating a visible seam under
// the top bar.
val LiveDarkPalette = LivePalette(
    bg = Color(0xFF070709),
    panel = Color(0xFF121319),
    panelDeep = Color(0xFF0B0B0F),
    panelRaised = Color(0xFF1B1D25),
    rowStripe = Color(0xFF0D0D11),
    divider = Color(0x992B2D36),
    dividerStrong = Color(0xE6333542),
    fg = Color(0xFFF5F5F8),
    fgDim = Color(0xFFB5B6BE),
    fgMute = Color(0xFF7D7E86),
    // Modern dark-blue accent. Less saturated than the prior cyan — reads as
    // a sophisticated "black-blue" for the TV grid. NOW pill, progress bars
    // and active indicators use this. Pure white drives focus rings.
    accent = Color(0xFF4F7FB0),
    accentDim = Color(0xFF355578),
    focusBg = Color(0x264F7FB0), // 15% alpha for softer row tint
    // Focus ring color — always pure white on TV for maximum clarity.
    focusRing = Color(0xFFFFFFFF),
    liveRed = Color(0xFFFF3B30),
    online = Color(0xFF4ADE80),
)

/** The guide in the launcher's glass look: frosted trays, white-pill focus, red "now". */
fun livePaletteForGlass(g: GlassPalette): LivePalette {
    val line = if (g.isNight) Color.White else Color.Black
    return LivePalette(
        bg = g.bgBase,
        panel = g.glass,
        panelDeep = g.glassStrong,
        panelRaised = if (g.isNight) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f),
        rowStripe = line.copy(alpha = 0.025f),
        divider = line.copy(alpha = 0.08f),
        dividerStrong = line.copy(alpha = 0.14f),
        fg = g.ink,
        fgDim = g.dim,
        fgMute = g.faint,
        accent = g.accent,
        accentDim = g.accent.copy(alpha = 0.6f),
        focusBg = g.chip,
        focusRing = g.focusBg,
        liveRed = g.live,
        online = g.up,
        isGlass = true,
        focusFill = g.focusBg,
        focusInk = g.focusInk,
        focusInkDim = g.focusInkDim,
        shadow = g.shadow,
        shadowFocus = g.shadowFocus,
        bgTop = g.bgTop,
        bgBottom = g.bgBottom,
        videoPanel = g.overVideo,
        videoInk = g.ink,
        videoDim = g.overVideoDim,
    )
}

/**
 * The guide's pop-ups over live video (HUD, splash, search, programme info, keypad, toasts) in the
 * glass look: same day/night ink, but opaque panels and full-contrast secondary text, since glass
 * over bright video was unreadable (Joe, 2026-10-25). Focus rings are ink so they show on white.
 */
fun livePaletteOverVideo(g: GlassPalette): LivePalette = livePaletteForGlass(g).copy(
    bg = g.overVideo,
    panel = g.sheetRaised,
    panelDeep = g.overVideo,
    panelRaised = g.sheetRaised,
    fgDim = g.overVideoDim,
    fgMute = g.overVideoDim,
    focusRing = g.ink,
)

val LocalLivePalette = staticCompositionLocalOf { LiveDarkPalette }

object LiveColors {
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.bg
    val Panel: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.panel
    val PanelDeep: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.panelDeep
    val PanelRaised: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.panelRaised
    val RowStripe: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.rowStripe

    val Divider: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.divider
    val DividerStrong: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.dividerStrong

    val Fg: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.fg
    val FgDim: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.fgDim
    val FgMute: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.fgMute

    val Accent: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.accent
    val AccentDim: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.accentDim
    val FocusBg: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.focusBg
    val FocusRing: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.focusRing

    val LiveRed: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.liveRed
    val Online: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.online

    val IsGlass: Boolean @Composable @ReadOnlyComposable get() = LocalLivePalette.current.isGlass
    val FocusFill: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.focusFill
    val FocusInk: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.focusInk
    val FocusInkDim: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.focusInkDim
    val ShadowFocus: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.shadowFocus
    val Shadow: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.shadow
    val VideoPanel: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.videoPanel
    val VideoInk: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.videoInk
    val VideoDim: Color @Composable @ReadOnlyComposable get() = LocalLivePalette.current.videoDim

    data class Brand(val bg: Color, val fg: Color)
    val BrandNews    = Brand(Color(0xFF8A2F2F), Color(0xFFFDE7D4))
    val BrandSport   = Brand(Color(0xFF0B6131), Color(0xFFEAFFF1))
    val BrandMovies  = Brand(Color(0xFF1A1A2E), Color(0xFFF5C26B))
    val BrandSeries  = Brand(Color(0xFF3A1552), Color(0xFFE9D2FF))
    val BrandKids    = Brand(Color(0xFFF3B13A), Color(0xFF1A1308))
    val BrandMusic   = Brand(Color(0xFF2A2A6E), Color(0xFFC8D4FF))
    val BrandDocs    = Brand(Color(0xFF1D3F3A), Color(0xFFCFE9E3))
    val BrandGeneral = Brand(Color(0xFF1B2B5A), Color(0xFFE8EFFB))
}

/**
 * Background for a guide surface: the glass look's frosted gradient, else the solid guide black.
 */
@Composable
fun Modifier.liveBackground(): Modifier {
    val p = LocalLivePalette.current
    return if (p.isGlass) background(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom))) else background(p.bg)
}

/** Inside a focused glass pill the text tokens become the pill's dark ink. No-op otherwise. */
@Composable
fun FocusInk(focused: Boolean, content: @Composable () -> Unit) {
    val p = LocalLivePalette.current
    if (!focused || !p.isGlass) {
        content()
    } else {
        CompositionLocalProvider(
            LocalLivePalette provides p.copy(fg = p.focusInk, fgDim = p.focusInkDim, fgMute = p.focusInkDim),
            content = content,
        )
    }
}

/**
 * The guide screen's own backdrop. With glass on it follows day/night even though the screen's
 * overlays (HUD over video, splash, search) run on the night palette.
 */
@Composable
fun Modifier.liveRootBackground(): Modifier {
    val g = LocalGlass.current ?: return background(LocalLivePalette.current.bg)
    return background(Brush.verticalGradient(listOf(g.bgTop, g.bgBottom)))
}

/**
 * Provides the guide palette for the current theme: glass day/night when the Glass theme is on,
 * otherwise the original dark guide. Inside, the custom focus colour becomes the glass "live" red
 * for the guide's now/active accents (focus itself is the white pill) unless [liveFocus] is off.
 */
@Composable
fun ProvideLivePalette(
    glass: GlassPalette? = LocalGlass.current,
    liveFocus: Boolean = true,
    overVideo: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (glass == null) {
        content()
    } else if (overVideo) {
        CompositionLocalProvider(
            LocalLivePalette provides livePaletteOverVideo(glass),
            LocalFocusBorderColorOverride provides glass.live,
            content = content,
        )
    } else if (!liveFocus) {
        // Screens outside the guide (library, cameras) keep the app's focus colour.
        CompositionLocalProvider(LocalLivePalette provides livePaletteForGlass(glass), content = content)
    } else {
        CompositionLocalProvider(
            LocalLivePalette provides livePaletteForGlass(glass),
            LocalFocusBorderColorOverride provides glass.live,
            content = content,
        )
    }
}

val LiveMono: FontFamily = InterFontFamily

object LiveType {
    // TV couch-readable sizes — minimum 14sp for body, larger for titles.
    val ChannelName  = TextStyle(fontFamily = InterFontFamily, fontSize = 14.sp, fontWeight = FontWeight.W600, letterSpacing = 0.sp, lineHeight = 18.sp)
    val ProgramTitle = TextStyle(fontFamily = InterFontFamily, fontSize = 14.sp, fontWeight = FontWeight.W500, letterSpacing = 0.sp, lineHeight = 18.sp)
    val CellTitle    = TextStyle(fontFamily = InterFontFamily, fontSize = 14.sp, fontWeight = FontWeight.W500, letterSpacing = 0.sp, lineHeight = 18.sp)
    val BodySynopsis = TextStyle(fontFamily = InterFontFamily, fontSize = 13.sp, fontWeight = FontWeight.W400, letterSpacing = 0.sp, lineHeight = 17.sp)
    val CatLabel     = TextStyle(fontFamily = InterFontFamily, fontSize = 13.sp, fontWeight = FontWeight.W500, letterSpacing = 0.sp, lineHeight = 17.sp)
    val SectionTag   = TextStyle(fontFamily = InterFontFamily, fontSize = 12.sp, fontWeight = FontWeight.W600, letterSpacing = 0.sp, lineHeight = 16.sp)
    val Badge        = TextStyle(fontFamily = InterFontFamily, fontSize = 11.sp, fontWeight = FontWeight.W600, letterSpacing = 0.sp, lineHeight = 15.sp)
    val TimeMono     = TextStyle(fontFamily = InterFontFamily, fontSize = 13.sp, fontWeight = FontWeight.W500, letterSpacing = 0.sp, lineHeight = 17.sp)
    val NumberMono   = TextStyle(fontFamily = InterFontFamily, fontSize = 13.sp, fontWeight = FontWeight.W500, letterSpacing = 0.sp, lineHeight = 17.sp)
}

object LiveDims {
    // 268 dp so the longest labels ("United Kingdom", "Czech Republic",
    // "South Africa") still render fully without ellipsis at the larger,
    // couch-readable label sizes below.
    val SidebarExpanded  = 268.dp
    val SidebarCollapsed = 52.dp
    val SidebarRowHeight = 34.dp

    val MiniPlayerWidth  = 300.dp
    val MiniPlayerHeight = 168.dp

    // Tightened from 240/62/38 to pack more rows on screen at once, TiviMate-style —
    // still comfortably fits the 36dp ChannelLogo + two-line text in ChannelRow.
    val EpgChannelColWidth = 200.dp
    val EpgRowHeight       = 50.dp
    val EpgHeaderHeight    = 30.dp
    val EpgPxPerMinute     = 5
    val EpgHalfHourWidth   = 120.dp

    val PanelRadius     = 12.dp
    val CardRadius      = 10.dp
    val CellRadius      = 6.dp
    val VideoRadius     = 12.dp
    val FocusBorder     = 2.dp
    val ActiveIndicator = 3.dp
}

val LocalLiveType   = staticCompositionLocalOf { LiveType }
val LocalLiveDims   = staticCompositionLocalOf { LiveDims }
