package com.arflix.tv.ui.screens.tv.live

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.arflix.tv.music.MaLineup
import com.arflix.tv.music.MaMediaItem
import com.arflix.tv.music.MaTrack
import com.arflix.tv.music.MusicLyrics
import com.arflix.tv.music.MusicLyricsSource
import kotlinx.coroutines.delay

/** The song a Music channel's zone is playing right now, and when it started (wall clock). */
fun currentMusicSong(
    channelId: String?,
    musicItems: List<MaMediaItem>,
    lineups: List<MaLineup>,
    nowMillis: Long,
): Pair<MaTrack, Long>? {
    val item = musicItems.musicItemFor(channelId) ?: return null
    val lineup = musicLineupFor(lineups, item.uri)?.takeIf { !it.paused } ?: return null
    var start = lineup.currentStartMillis
    for (song in lineup.tracks) {
        val end = start + (song.durationSec.takeIf { it > 0 } ?: 210) * 1000L
        if (nowMillis < end) return song to start
        start = end
    }
    return null
}

/**
 * Guide top panel, Music rows: the line being sung and the one after it, in place of NEXT
 * (Joe, 2026-10-12: "what about the guide view? maybe in the top view?"). Synced lyrics only --
 * plain lyrics can't follow the song -- and [fallback] (the usual NEXT line) otherwise.
 */
@Composable
fun GuideLyricsStrip(song: Pair<MaTrack, Long>?, fallback: @Composable () -> Unit) {
    val track = song?.first
    val lyrics by produceState<MusicLyrics?>(initialValue = null, track?.uri, track?.name, track?.artist) {
        value = track?.let { MusicLyricsSource.lyricsFor(it) }
    }
    val synced = lyrics?.takeIf { it.synced }
    if (song == null || synced == null) {
        fallback()
        return
    }
    val start = song.second
    val index by produceState(initialValue = -1, synced, start) {
        while (true) {
            value = synced.currentIndex(System.currentTimeMillis() - start)
            delay(250)
        }
    }
    AnimatedContent(
        targetState = index,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "guide-lyric",
    ) { i ->
        val now = synced.lines.getOrNull(i)?.text?.ifBlank { "♪" } ?: "♪"
        val next = synced.lines.getOrNull(i + 1)?.text?.takeIf { it.isNotBlank() }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(now, style = LiveType.ProgramTitle.copy(color = LiveColors.Fg, fontSize = 17.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            next?.let {
                Text(it, style = LiveType.BodySynopsis.copy(color = LiveColors.FgMute, fontSize = 14.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
