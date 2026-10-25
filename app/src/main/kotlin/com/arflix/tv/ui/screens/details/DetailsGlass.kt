package com.arflix.tv.ui.screens.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.arflix.tv.ui.glass.GlassPalette
import com.arflix.tv.ui.glass.glassBackground
import com.arflix.tv.ui.skin.LocalXadarrSkinTokens

// Details in the glass look (Joe, 2026-10-25 mockup): the launcher's Backdrop treatment -- the
// backdrop sits top-right and is washed into the frosted page from the left and the bottom
// (whitened by day, darkened at night), so the text sits on clean paper instead of a black scrim.

private const val BACKDROP_WIDTH = 0.68f
private const val BACKDROP_HEIGHT = 0.72f

@Composable
internal fun BoxScope.GlassDetailsBackdrop(url: String?, g: GlassPalette) {
    Box(Modifier.matchParentSize().glassBackground(g))
    val area = Modifier.align(Alignment.TopEnd).fillMaxWidth(BACKDROP_WIDTH).fillMaxHeight(BACKDROP_HEIGHT)
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = area,
    )
    // The page colour where the image ends, so the bottom wash meets the gradient seamlessly.
    val edge = lerp(g.bgTop, g.bgBottom, BACKDROP_HEIGHT)
    Box(area.background(g.bgBase.copy(alpha = if (g.isNight) 0.22f else 0.18f)))
    Box(area.background(Brush.horizontalGradient(0f to g.bgBase, 0.45f to g.bgBase.copy(alpha = 0f))))
    Box(area.background(Brush.verticalGradient(0.5f to edge.copy(alpha = 0f), 1f to edge)))
}

/** Skin colours for the glass Details page, so shared cards (MediaCard titles) read on light paper. */
@Composable
internal fun ProvideDetailsGlassSkin(g: GlassPalette?, content: @Composable () -> Unit) {
    if (g == null) {
        content()
        return
    }
    val tokens = LocalXadarrSkinTokens.current
    CompositionLocalProvider(
        LocalXadarrSkinTokens provides tokens.copy(
            colors = tokens.colors.copy(
                textPrimary = g.ink,
                textMuted = g.dim,
                surface = g.cardEmpty,
                surfaceRaised = g.cardEmpty,
            )
        ),
        content = content,
    )
}
