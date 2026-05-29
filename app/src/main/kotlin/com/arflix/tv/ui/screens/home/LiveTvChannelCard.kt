@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.home

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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.ui.screens.tv.live.LiveColors
import com.arflix.tv.ui.screens.tv.live.LiveType
import com.arflix.tv.ui.screens.tv.live.progressOf
import com.arflix.tv.ui.screens.tv.live.remainingLabel
import com.arflix.tv.ui.theme.Pink

/** Card width used in the On Now home row — matches standard landscape home cards. */
val LiveTvCardWidth: Dp = 210.dp

/**
 * Home-row card for a favorited live TV channel.
 * Shows channel logo, current program title, progress bar, time remaining, and LIVE badge.
 * Designed for the "On Now" row — tapping starts the channel in the mini-player.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvChannelCard(
    channelName: String,
    logoUrl: String?,
    nowNext: IptvNowNext?,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "card-scale",
    )

    // Pulsing glow border when focused
    val pulseTransition = rememberInfiniteTransition(label = "card-pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "card-pulse-alpha",
    )
    val borderColor = if (isFocused) Pink.copy(alpha = pulseAlpha) else Color.Transparent
    val borderWidth = if (isFocused) 2.dp else 0.dp

    val now = nowNext?.now
    val progress = progressOf(now)
    val remaining = remainingLabel(now)
    val programTitle = now?.title.orEmpty()
    val textBrightness = if (isFocused) 1f else 0.75f

    Box(
        modifier = modifier
            .width(LiveTvCardWidth)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .background(Color(0xFF0E0E14))
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
    ) {
        // ── Logo / image area ───────────────────────────────────────────────
        val hasLogo = !logoUrl.isNullOrBlank()
        // Deterministic color from channel name so each channel gets a consistent hue
        val fallbackColor = remember(channelName) {
            val palette = listOf(
                Color(0xFF1565C0), Color(0xFF6A1B9A), Color(0xFF2E7D32),
                Color(0xFFC62828), Color(0xFF00695C), Color(0xFF4527A0),
                Color(0xFF283593), Color(0xFF558B2F), Color(0xFF4E342E),
            )
            palette[Math.abs(channelName.hashCode()) % palette.size]
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp)
                .background(
                    if (hasLogo) Brush.radialGradient(listOf(Color(0xFF1A1A28), Color(0xFF090910)))
                    else Brush.radialGradient(listOf(fallbackColor.copy(alpha = 0.9f), fallbackColor.copy(alpha = 0.5f)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (hasLogo) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = channelName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            } else {
                // Channel initial on colored background
                val initial = channelName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "TV"
                Text(
                    text = initial,
                    style = LiveType.ChannelName.copy(
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    ),
                )
            }

            // LIVE badge — top left
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(LiveColors.LiveRed)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val dotPulse by pulseTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "live-dot",
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color.White.copy(alpha = dotPulse), CircleShape)
                )
                Text(
                    text = "LIVE",
                    style = LiveType.Badge.copy(color = Color.White, fontSize = 9.sp),
                )
            }

            // Progress bar — bottom of image area
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = if (isFocused) Pink else LiveColors.Accent,
                    trackColor = Color.White.copy(alpha = 0.12f),
                )
            }
        }

        // ── Info strip ──────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xFF0E0E14))
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = channelName,
                    style = LiveType.ChannelName.copy(
                        color = Color.White.copy(alpha = textBrightness),
                        fontSize = 12.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (remaining.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = remaining,
                        style = LiveType.Badge.copy(
                            color = LiveColors.Accent.copy(alpha = textBrightness),
                            fontSize = 9.sp,
                        ),
                    )
                }
            }
            if (programTitle.isNotBlank()) {
                Text(
                    text = programTitle,
                    style = LiveType.ProgramTitle.copy(
                        color = Color.White.copy(alpha = textBrightness * 0.65f),
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
