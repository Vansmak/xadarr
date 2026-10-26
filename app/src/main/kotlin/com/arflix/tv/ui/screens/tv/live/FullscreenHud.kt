@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.IptvNowNext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import kotlinx.coroutines.delay
import com.arflix.tv.ui.skin.LocalFocusBorderColorOverride

/**
 * Fullscreen playback HUD. Auto-hides 5s after the last `pokeSignal`
 * bump; parent bumps the counter on any DPAD key so the HUD re-surfaces.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FullscreenHud(
    channel: EnrichedChannel?,
    nowNext: IptvNowNext?,
    pokeSignal: Int,
    onBackClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(true) }
    var lastPoke by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(pokeSignal) {
        visible = true
        lastPoke = System.currentTimeMillis()
        delay(5_000)
        if (System.currentTimeMillis() - lastPoke >= 5_000) {
            visible = false
        }
    }

    var clockMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockMillis = System.currentTimeMillis()
            delay(30_000)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier.fillMaxSize(),
    ) {
        // Glass: everything here sits on live video, so the panels are near-opaque (not glass),
        // with a soft shadow, full-contrast text and a size up (Joe, 2026-10-25: bright video
        // bled through and small gray text was unreadable). Other themes: unchanged.
        val glass = LiveColors.IsGlass
        val panel = if (glass) LiveColors.VideoPanel else Color(0x66000000)
        val ink = if (glass) LiveColors.VideoInk else LiveColors.Fg
        val dim = if (glass) LiveColors.VideoDim else LiveColors.FgDim
        val mute = if (glass) LiveColors.VideoDim else LiveColors.FgMute
        val up = if (glass) 2 else 0
        val lift = if (glass) Modifier.shadow(12.dp, RoundedCornerShape(12.dp), ambientColor = LiveColors.Shadow, spotColor = LiveColors.Shadow) else Modifier
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color(0xCC000000),
                        )
                    ),
            )

            if (channel != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = if (onBackClick != null) 80.dp else 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .then(lift)
                            .clip(RoundedCornerShape(12.dp))
                            .background(panel)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ChannelLogo(channel = channel, size = 40.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "CH ${channel.numberLabel}",
                                style = LiveType.SectionTag.copy(color = mute),
                            )
                            Text(
                                text = channel.name,
                                style = LiveType.ChannelName.copy(
                                    color = ink,
                                    fontSize = (16 + up).sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                HudBadge(channel.quality.label, ink, LiveColors.Panel)
                                channel.country?.takeIf { it != channel.lang }?.let {
                                    HudBadge(it.uppercase(), dim, LiveColors.Panel)
                                }
                                HudBadge(channel.lang.uppercase(), dim, LiveColors.Panel)
                            }
                        }
                    }
                    // Favorites quick bar removed — duplicated the sidebar's own favorites list.
                    // Left-on-remote favorites cycling (cycleFavorite() in LiveTvScreen) still
                    // works without this visual strip.
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .then(lift)
                    .clip(RoundedCornerShape(12.dp))
                    .background(panel)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = formatClock(clockMillis),
                    style = LiveType.TimeMono.copy(
                        color = ink,
                        fontSize = 18.sp,
                    ),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.6f)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .wrapContentHeight()
                    .then(if (glass) Modifier.shadow(16.dp, RoundedCornerShape(LiveDims.CardRadius), ambientColor = LiveColors.Shadow, spotColor = LiveColors.Shadow) else Modifier)
                    .clip(RoundedCornerShape(LiveDims.CardRadius))
                    .background(if (glass) panel else LiveColors.PanelRaised.copy(alpha = 0.85f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val now = nowNext?.now
                val next = nowNext?.next
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("NOW", style = LiveType.SectionTag.copy(color = (LocalFocusBorderColorOverride.current ?: LiveColors.Accent)))
                        Text(
                            text = formatTimeWindow(now),
                            style = LiveType.TimeMono.copy(color = ink),
                        )
                        Spacer(Modifier.weight(1f))
                        val remaining = remainingLabel(now)
                        if (remaining.isNotBlank()) {
                            Text(
                                text = remaining,
                                style = LiveType.TimeMono.copy(color = (LocalFocusBorderColorOverride.current ?: LiveColors.Accent)),
                            )
                        }
                    }
                    Text(
                        text = now?.title
                            ?: channel?.name
                            ?: "No programme data",
                        style = LiveType.ProgramTitle.copy(
                            color = ink,
                            fontSize = (18 + up).sp,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!now?.description.isNullOrBlank()) {
                        Text(
                            text = now!!.description!!,
                            style = LiveType.BodySynopsis.copy(
                                color = dim,
                                fontSize = (12 + up).sp,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val progress = progressOf(now)
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = (LocalFocusBorderColorOverride.current ?: LiveColors.Accent),
                            trackColor = if (glass) LiveColors.Divider else LiveColors.Panel,
                        )
                    }
                    if (next != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(LiveColors.Divider),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("NEXT", style = LiveType.SectionTag.copy(color = mute))
                            Text(
                                text = formatClock(next.startUtcMillis),
                                style = LiveType.TimeMono.copy(color = dim),
                            )
                            Text(
                                text = next.title,
                                style = LiveType.CellTitle.copy(
                                    color = dim,
                                    fontSize = (12 + up).sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (onBackClick != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(20.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (glass) panel else Color.Black.copy(alpha = 0.62f))
                        .clickable { onBackClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Go Back",
                        tint = if (glass) ink else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HudBadge(label: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = LiveType.Badge.copy(color = fg, fontSize = 10.sp))
    }
}
