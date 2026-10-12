package com.arflix.tv.ui.screens.tv.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arflix.tv.ui.screens.tv.TvViewModel
import kotlinx.coroutines.delay

/**
 * Where the guide opens while music is still playing (Joe, 2026-10-12: "the guide doesn't
 * return to the music group when it's still playing"): the Now Playing row of this TV's room,
 * or of the zone this TV last started music on. Kept out of LiveTvScreen() -- that composable is
 * at the size the Shield's verifier rejects.
 */
class GuideMusicReturn(
    /** Still finding out whether music plays: hold the on-entry alignment. */
    val waiting: Boolean,
    /** The Now Playing row to open on, or null to resume the live channel as usual. */
    val channelId: String?,
)

@Composable
fun rememberGuideMusicReturn(viewModel: TvViewModel, indexHas: (String) -> Boolean): GuideMusicReturn {
    val configured by viewModel.musicConfigured.collectAsStateWithLifecycle()
    val loaded by viewModel.musicLineupsLoaded.collectAsStateWithLifecycle()
    val lineups by viewModel.musicLineups.collectAsStateWithLifecycle()
    val roomZoneId by viewModel.musicRoomZoneId.collectAsStateWithLifecycle()
    val lastZoneId by viewModel.lastMusicZoneId.collectAsStateWithLifecycle()
    // Never hold the guide for long: MA down or slow means open as usual.
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(2_500L) // inside the guide's own 3s startup settle
        timedOut = true
    }
    if (!configured) return GuideMusicReturn(waiting = false, channelId = null)
    // Timing out only ends the wait; a row that's already found still wins.
    val notYet = GuideMusicReturn(waiting = !timedOut, channelId = null)
    if (!loaded) return notYet
    val zones = listOfNotNull(roomZoneId, lastZoneId)
    val playing = lineups.firstOrNull { lineup ->
        !lineup.paused && lineup.tracks.isNotEmpty() && lineup.queueId in zones
    } ?: return GuideMusicReturn(waiting = false, channelId = null)
    val id = nowPlayingChannelId(playing.queueId)
    // The row exists once the guide has re-built with the Now Playing items.
    return if (indexHas(id)) GuideMusicReturn(waiting = false, channelId = id) else notYet
}
