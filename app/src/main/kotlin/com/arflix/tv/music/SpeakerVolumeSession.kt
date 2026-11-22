package com.arflix.tv.music

import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

/**
 * Claims the remote's volume keys for the room's speaker while the Music screen is up. On an
 * Android TV box with HDMI-CEC, Android sends volume keys straight to the soundbar over HDMI and
 * never to the app -- and a Sonos playing Music Assistant ignores those (gym, Joe, 2026-11-22: "the
 * remote doesn't control volume in Music but works outside it"). A media session that says its
 * playback is remote, with a VolumeProvider, is Android's way to have volume keys routed to the
 * app instead, which turns the selected MA speaker up or down. Only while Music is on screen:
 * switch to another app (the TV app) and the session lets go, so volume works as it always does.
 */
@Composable
fun SpeakerVolumeSession(viewModel: MusicViewModel) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsState()
    val player = ui.selectedPlayer
    val volume = player?.effectiveVolume ?: 0
    val session = remember { MediaSession(context, "XadarrMusicSpeakerVolume") }
    val provider = remember(player?.id) {
        object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, volume) {
            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) viewModel.changeVolume(direction * 2, announce = true)
            }

            override fun onSetVolumeTo(target: Int) {
                viewModel.changeVolume(target - (viewModel.ui.value.selectedPlayer?.effectiveVolume ?: target), announce = true)
            }
        }
    }
    LaunchedEffect(provider, volume) { provider.currentVolume = volume }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var onScreen by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> onScreen = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(provider, player != null, onScreen) {
        if (player != null && onScreen) {
            session.setPlaybackToRemote(provider)
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            session.isActive = true
        }
        onDispose { session.isActive = false }
    }
    DisposableEffect(Unit) { onDispose { session.release() } }
}
