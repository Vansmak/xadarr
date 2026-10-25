package com.arflix.tv.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/** A Material colour scheme for the glass look, so colorScheme-driven screens (Music) follow day/night. */
@OptIn(ExperimentalTvMaterial3Api::class)
fun glassColorScheme(g: GlassPalette): ColorScheme = if (g.isNight) {
    darkColorScheme(
        primary = g.accent, onPrimary = Color.White, primaryContainer = g.chip, onPrimaryContainer = g.ink,
        secondary = g.dim, onSecondary = g.bgBase, secondaryContainer = g.chip, onSecondaryContainer = g.ink,
        tertiary = g.accent, onTertiary = Color.White,
        background = g.bgBase, onBackground = g.ink, surface = g.bgBase, onSurface = g.ink,
        surfaceVariant = g.cardEmpty, onSurfaceVariant = g.dim,
        error = g.down, onError = Color.White, border = g.track, borderVariant = g.chip,
    )
} else {
    lightColorScheme(
        primary = g.accent, onPrimary = Color.White, primaryContainer = g.chip, onPrimaryContainer = g.ink,
        secondary = g.dim, onSecondary = g.bgBase, secondaryContainer = g.chip, onSecondaryContainer = g.ink,
        tertiary = g.accent, onTertiary = Color.White,
        background = g.bgBase, onBackground = g.ink, surface = g.bgBase, onSurface = g.ink,
        surfaceVariant = g.cardEmpty, onSurfaceVariant = g.dim,
        error = g.down, onError = Color.White, border = g.track, borderVariant = g.chip,
    )
}

/** Runs [content] with the glass day/night colour scheme when the glass look is on; untouched otherwise. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GlassMaterialTheme(content: @Composable () -> Unit) {
    val g = LocalGlass.current
    if (g == null) content() else MaterialTheme(colorScheme = glassColorScheme(g), content = content)
}

/** The launcher's frosted background: a soft vertical gradient. */
fun Modifier.glassBackground(g: GlassPalette): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(g.bgTop, g.bgBottom)))
}
