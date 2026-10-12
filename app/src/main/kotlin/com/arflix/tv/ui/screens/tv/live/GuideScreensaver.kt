package com.arflix.tv.ui.screens.tv.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arflix.tv.music.KeepScreenOn
import com.arflix.tv.music.MUSIC_AMBIENT_AFTER_MS
import com.arflix.tv.music.MUSIC_AWAKE_CHECK_MS
import com.arflix.tv.music.MUSIC_AWAKE_GRACE_MS
import com.arflix.tv.music.MusicAmbientNow
import com.arflix.tv.music.MusicViewModel
import com.arflix.tv.ui.screens.tv.TvViewModel
import kotlinx.coroutines.delay

/** How long the guide sits untouched before a live channel goes back to fullscreen. */
// 90s was too soon for reading the guide (Joe, 2026-10-12).
private const val GUIDE_IDLE_TO_FULLSCREEN_MS = 5 * 60_000L

/**
 * The guide left alone (Joe, 2026-10-12: "ss mode should still kick in if guide left unattended.
 * either media if not on music or the ma ss"):
 * - music playing in this TV's room (and the TV isn't sounding over it) -> the music screensaver,
 *   same as the Music screen's, after a minute;
 * - otherwise a live channel playing with sound -> back to fullscreen video after 5 minutes.
 * [lastInputAt] is the guide's last key press (uptime ms); [enabled] is false while a menu,
 * search or the keypad is open, or already fullscreen. Returns whether the music screensaver is
 * up -- the guide's key handler swallows the waking press (see [wake]).
 */
class GuideSaverState {
    var lastInputAt by mutableLongStateOf(android.os.SystemClock.uptimeMillis())
    var musicUp by mutableStateOf(false)
        internal set

    private var swallowUp = false

    /**
     * Every guide key event goes through here first. Notes the press; a press that takes the
     * screensaver down is swallowed (down and up), so waking it doesn't also act on the guide.
     */
    fun consume(keyDown: Boolean): Boolean {
        if (!keyDown) {
            val swallow = swallowUp
            swallowUp = false
            return swallow
        }
        lastInputAt = android.os.SystemClock.uptimeMillis()
        if (!musicUp) return false
        musicUp = false
        swallowUp = true
        return true
    }
}

@Composable
fun GuideScreensaver(
    saver: GuideSaverState,
    viewModel: TvViewModel,
    enabled: Boolean,
    liveActive: Boolean,
    liveMuted: Boolean,
    onGoFullscreen: () -> Unit,
) {
    val lineups by viewModel.musicLineups.collectAsStateWithLifecycle()
    val speakers by viewModel.musicSpeakers.collectAsStateWithLifecycle()
    val roomZoneId by viewModel.musicRoomZoneId.collectAsStateWithLifecycle()
    val lastZoneId by viewModel.lastMusicZoneId.collectAsStateWithLifecycle()
    // The queue to show: this room's (or the group it's in), else -- with no TV playing -- the
    // zone this TV last started.
    val roomLeader = roomZoneId?.let { id -> speakers.firstOrNull { it.id == id }?.syncedTo ?: id }
    // Same test as the guide's TV mute (musicInRoom): a playing queue, songs loaded or not. Also
    // requiring loaded songs made this miss music the mute saw, and the guide went fullscreen
    // on muted Fox News over the music (Joe, 2026-10-12).
    fun playing(id: String?) = id != null && lineups.any { it.queueId == id && !it.paused }
    val musicZone = when {
        playing(roomLeader) -> roomLeader
        !liveActive && playing(lastZoneId) -> lastZoneId
        else -> null
    }
    val musicWanted = musicZone != null && (!liveActive || liveMuted)

    LaunchedEffect(enabled, musicWanted, liveActive) {
        if (!musicWanted) saver.musicUp = false
        if (!enabled) {
            saver.musicUp = false
            return@LaunchedEffect
        }
        while (true) {
            delay(5_000)
            val idle = android.os.SystemClock.uptimeMillis() - saver.lastInputAt
            when {
                musicWanted && idle > MUSIC_AMBIENT_AFTER_MS -> saver.musicUp = true
                // Fullscreen only for a channel you're actually hearing -- never one muted for music.
                !musicWanted && musicZone == null && liveActive && !liveMuted && idle > GUIDE_IDLE_TO_FULLSCREEN_MS -> {
                    saver.lastInputAt = android.os.SystemClock.uptimeMillis()
                    onGoFullscreen()
                }
            }
        }
    }

    AnimatedVisibility(
        visible = saver.musicUp && musicZone != null,
        enter = fadeIn(tween(1_200)),
        exit = fadeOut(tween(300)),
        modifier = Modifier.zIndex(600f),
    ) {
        val musicVm: MusicViewModel = androidx.hilt.navigation.compose.hiltViewModel()
        LaunchedEffect(musicZone) { musicZone?.let { musicVm.selectPlayer(it) } }
        // Keep the screen on like the Music screen does: an hour, then a minute's warning.
        var awakeIdle by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) {
                awakeIdle = android.os.SystemClock.uptimeMillis() - saver.lastInputAt
                delay(5_000)
            }
        }
        KeepScreenOn(awakeIdle < MUSIC_AWAKE_CHECK_MS + MUSIC_AWAKE_GRACE_MS)
        Box(Modifier.fillMaxSize()) {
            MusicAmbientNow(musicVm, sleepingSoon = awakeIdle > MUSIC_AWAKE_CHECK_MS)
        }
    }
}
