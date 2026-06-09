package com.arflix.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import androidx.compose.ui.platform.LocalContext
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.ui.skin.XadarrFocusableSurface
import com.arflix.tv.ui.skin.XadarrSkin
import com.arflix.tv.ui.skin.rememberXadarrCardShape

/**
 * Continue Watching card with progress bar.
 *
 * Notes:
 * - Focus visuals are handled by `XadarrFocusableSurface` (no layout scaling).
 * - The `isFocused` param is preserved for compatibility with any external focus tracking.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingCard(
    item: MediaItem,
    progress: Float = 0f, // 0.0 to 1.0
    episodeInfo: String? = null,
    timeRemaining: String? = null,
    width: Dp = 340.dp,
    isFocused: Boolean = false,
    onClick: () -> Unit = {},
) {
    val shape = rememberXadarrCardShape(XadarrSkin.radius.md)

    Column(modifier = Modifier.width(width)) {
        XadarrFocusableSurface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = shape,
            backgroundColor = XadarrSkin.colors.surface,
            onClick = onClick,
        ) { surfaceFocused ->
            val focused = isFocused || surfaceFocused

            Box(modifier = Modifier.fillMaxSize()) {
                // Use a sized ImageRequest so Coil decodes at the card's actual
                // pixel dimensions instead of the source image's full resolution.
                // Without this, "original"-sized TMDB backdrops (2-10 MB) were
                // being decoded at full size, causing slow loads and memory waste.
                val imageUrl = (item.backdrop ?: item.image).takeIf { !it.isNullOrBlank() }
                if (imageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUrl)
                            .size(640, 360)
                            .precision(Precision.INEXACT)
                            .allowHardware(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    XadarrSkin.colors.background.copy(alpha = 0.85f),
                                ),
                                startY = 120f,
                            )
                        )
                )

                if (focused) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(XadarrSkin.colors.accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = XadarrSkin.colors.textPrimary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = XadarrSkin.spacing.x2, vertical = XadarrSkin.spacing.x2),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(XadarrSkin.colors.focusOutline.copy(alpha = 0.20f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(XadarrSkin.colors.accent),
                    )
                }

                if (timeRemaining != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(XadarrSkin.spacing.x2)
                            .background(
                                color = XadarrSkin.colors.surfaceRaised.copy(alpha = 0.85f),
                                shape = rememberXadarrCardShape(XadarrSkin.radius.sm),
                            )
                            .padding(horizontal = XadarrSkin.spacing.x2, vertical = XadarrSkin.spacing.x1),
                    ) {
                        Text(
                            text = timeRemaining,
                            style = XadarrSkin.typography.badge,
                            color = XadarrSkin.colors.textPrimary,
                        )
                    }
                }

                val typeLabel = if (item.mediaType == MediaType.TV) "TV" else "MOVIE"
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(XadarrSkin.spacing.x2)
                        .background(
                            color = XadarrSkin.colors.surfaceRaised.copy(alpha = 0.85f),
                            shape = rememberXadarrCardShape(XadarrSkin.radius.sm),
                        )
                        .padding(horizontal = XadarrSkin.spacing.x2, vertical = XadarrSkin.spacing.x1),
                ) {
                    Text(
                        text = typeLabel,
                        style = XadarrSkin.typography.badge,
                        color = XadarrSkin.colors.textPrimary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(XadarrSkin.spacing.x2))

        Text(
            text = item.title,
            style = XadarrSkin.typography.cardTitle,
            color = if (isFocused) XadarrSkin.colors.textPrimary else XadarrSkin.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val meta = episodeInfo ?: item.year.takeIf { it.isNotBlank() }
        if (meta != null) {
            Text(
                text = meta,
                style = XadarrSkin.typography.caption,
                color = XadarrSkin.colors.textMuted.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Compact Continue Watching card.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingCardCompact(
    item: MediaItem,
    progress: Float = 0f,
    episodeInfo: String? = null,
    isFocused: Boolean = false,
    onClick: () -> Unit = {},
) {
    val shape = rememberXadarrCardShape(XadarrSkin.radius.md)

    XadarrFocusableSurface(
        modifier = Modifier.width(380.dp),
        shape = shape,
        backgroundColor = XadarrSkin.colors.surface,
        onClick = onClick,
    ) { surfaceFocused ->
        val focused = isFocused || surfaceFocused

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(XadarrSkin.spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .aspectRatio(16f / 9f)
                    .background(XadarrSkin.colors.surfaceRaised, rememberXadarrCardShape(XadarrSkin.radius.sm)),
            ) {
                val compactUrl = (item.backdrop ?: item.image).takeIf { !it.isNullOrBlank() }
                if (compactUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(compactUrl)
                            .size(200, 112)
                            .precision(Precision.INEXACT)
                            .allowHardware(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(XadarrSkin.colors.focusOutline.copy(alpha = 0.20f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(XadarrSkin.colors.accent),
                    )
                }
            }

            Spacer(modifier = Modifier.width(XadarrSkin.spacing.x3))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = XadarrSkin.typography.cardTitle,
                    color = XadarrSkin.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episodeInfo != null) {
                    Text(
                        text = episodeInfo,
                        style = XadarrSkin.typography.caption,
                        color = XadarrSkin.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (focused) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(XadarrSkin.colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = XadarrSkin.colors.textPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

