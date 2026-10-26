package com.arflix.tv.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arflix.tv.ui.glass.LocalGlass

/**
 * Lyrics for the current song: synced (.lrc) lyrics highlight the line being sung and keep it
 * centred; plain lyrics are a static scrollable page. [focusedIndex] is the D-pad's line (null
 * when the remote isn't on the lyrics); while [following], the view tracks the song instead.
 * Used by the Lyrics tab and, larger and non-interactive, by the screensaver.
 */
@Composable
fun LyricsView(
    lyrics: MusicLyrics?,
    loaded: Boolean,
    positionMs: Long,
    modifier: Modifier = Modifier,
    focusedIndex: Int? = null,
    following: Boolean = true,
    big: Boolean = false,
    align: TextAlign = TextAlign.Start,
    onLineClick: ((Int) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    if (lyrics == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (loaded) "No lyrics found" else "Looking for lyrics…",
                color = colors.onSurfaceVariant, fontSize = if (big) 22.sp else 18.sp,
            )
        }
        return
    }
    val current = lyrics.currentIndex(positionMs)
    val target = when {
        !following && focusedIndex != null -> focusedIndex
        lyrics.synced -> current.coerceAtLeast(0)
        else -> focusedIndex ?: 0
    }
    val listState = rememberLazyListState()
    LaunchedEffect(target, lyrics) {
        runCatching { listState.animateScrollToItem(target.coerceIn(0, (lyrics.lines.size - 1).coerceAtLeast(0))) }
    }
    val lineSize = if (big) 30.sp else 22.sp
    // Glass: ink on the page (dark by day); the focused line is a soft chip, no ring.
    val glass = LocalGlass.current
    val ink = glass?.ink ?: Color.White
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Half-height padding above and below, so "scroll item to the top" puts it in the middle.
        val half = (maxHeight / 2 - (if (big) 28.dp else 22.dp)).coerceAtLeast(0.dp)
        LazyColumn(
            state = listState,
            userScrollEnabled = !big,
            contentPadding = PaddingValues(top = half, bottom = half),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lyrics.lines) { i, line ->
                val isCurrent = lyrics.synced && i == current
                val isPast = lyrics.synced && i < current
                val isFocused = focusedIndex == i && !following
                val color by animateColorAsState(
                    when {
                        isCurrent -> ink
                        isPast -> ink.copy(alpha = 0.38f)
                        lyrics.synced -> ink.copy(alpha = 0.6f)
                        else -> ink.copy(alpha = 0.85f)
                    },
                    tween(300), label = "lyric-color",
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isFocused && glass != null) Modifier.background(glass.chip)
                            else if (isFocused) Modifier.background(colors.primary.copy(alpha = 0.22f)).border(2.dp, colors.primary, RoundedCornerShape(10.dp))
                            else Modifier
                        )
                        .then(if (onLineClick != null) Modifier.clickable { onLineClick(i) } else Modifier)
                        .padding(horizontal = 14.dp, vertical = if (big) 8.dp else 6.dp),
                ) {
                    Text(
                        text = line.text.ifBlank { "♪" },
                        color = color,
                        fontSize = if (isCurrent) lineSize * 1.12f else lineSize,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        textAlign = align,
                        lineHeight = lineSize * 1.3f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
