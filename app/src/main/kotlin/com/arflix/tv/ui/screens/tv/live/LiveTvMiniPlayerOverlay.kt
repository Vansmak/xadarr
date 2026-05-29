@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv.live

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.ui.theme.Pink

/**
 * Floating picture-in-picture tile shown on non-TV screens when a live stream is active.
 * Rendered in a Box overlay in ArflixApp, above the NavHost content.
 *
 * Dismiss: press Back (handled by HomeScreen's onInterceptBack) or tap the ✕ on touch devices.
 * Select/OK on the tile navigates back to the full TV guide.
 *
 * Surface handoff: the update lambda force-detaches then re-attaches the player surface as a
 * fallback for mid-transition timing.
 *
 * Focus affordance (TV remote):
 *   - Pulsing pink glow border when focused so it's visible from the couch
 *   - Expand icon overlay (↗) appears on focus
 *   - Channel name and program text brighten on focus
 *   - Tile scales up slightly on focus
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvMiniPlayerOverlay(
    player: ExoPlayer,
    channelName: String,
    programTitle: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "mini-scale",
    )

    // Pulsing glow border on focus — couch-visible from ~3m
    val pulseTransition = rememberInfiniteTransition(label = "mini-pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "mini-pulse-alpha",
    )
    val borderColor = if (isFocused) Pink.copy(alpha = pulseAlpha) else Color.Transparent
    val borderWidth = if (isFocused) 2.5.dp else 0.dp

    val textAlpha = if (isFocused) 1f else 0.75f

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (isFocused) 24.dp else 16.dp,
                shape = RoundedCornerShape(10.dp),
                ambientColor = if (isFocused) Pink.copy(alpha = 0.4f) else Color.Black,
                spotColor = if (isFocused) Pink.copy(alpha = 0.5f) else Color.Black,
            )
            .width(240.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .background(Color.Black)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .focusable()
            .clickable(onClick = onClick),
    ) {
        // Video area — 16:9 at 240dp wide = 135dp tall
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(135.dp),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { view ->
                    // Fallback surface handoff: force detach then reattach to ensure
                    // the surface is current when this view enters composition mid-transition.
                    if (view.player !== player) {
                        view.player = null
                        view.player = player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            LiveBug(modifier = Modifier.align(Alignment.TopStart).padding(6.dp))

            // Expand icon — visible on focus to hint that selecting navigates to TV
            if (isFocused) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Pink.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Expand to full TV guide",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        // Info bar — channel name, program, and close button
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    if (isFocused) Color(0xE6000000) else Color.Black.copy(alpha = 0.85f)
                )
                .padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (channelName.isNotBlank()) {
                    Text(
                        text = channelName,
                        style = LiveType.Badge.copy(
                            color = Color.White.copy(alpha = textAlpha),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (programTitle.isNotBlank()) {
                    Text(
                        text = programTitle,
                        style = LiveType.Badge.copy(
                            color = Color.White.copy(alpha = textAlpha * 0.6f),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            // Close button — touch devices tap this; TV users press Back to dismiss
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (isFocused) 0.2f else 0.12f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss — or press Back",
                    tint = Color.White.copy(alpha = if (isFocused) 1f else 0.7f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
