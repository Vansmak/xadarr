package com.arflix.tv.music

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * The remote's volume keys for the room's speaker, while Music is on screen (gym, Joe,
 * 2026-11-22: "the remote doesn't control volume in Music but works outside it"). On an Android TV
 * box with HDMI-CEC, Android sends volume keys straight to the soundbar over HDMI and never to the
 * app -- and a Sonos playing Music Assistant ignores those. A remote media session doesn't change
 * that on Android 14 (tried, Music 1.16). An accessibility service sees keys before that routing,
 * so [SpeakerVolumeKeyService] hands them to whichever Music screen is up, and lets them through
 * untouched the rest of the time -- in the TV app volume works as it always does.
 */
object SpeakerVolumeKeys {
    /** Set while a Music screen is on screen: +1 / -1 per press. */
    @Volatile var handler: ((Int) -> Unit)? = null
}

/** Registers the Music screen for the volume keys while it's resumed. */
@Composable
fun ClaimSpeakerVolumeKeys(viewModel: MusicViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val mine: (Int) -> Unit = { d -> viewModel.changeVolume(d * 2, announce = true) }
        fun sync() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) SpeakerVolumeKeys.handler = mine
            else if (SpeakerVolumeKeys.handler === mine) SpeakerVolumeKeys.handler = null
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer)
        sync()
        onDispose {
            lifecycle.removeObserver(observer)
            if (SpeakerVolumeKeys.handler === mine) SpeakerVolumeKeys.handler = null
        }
    }
}

/**
 * "Xadarr Music volume keys" under Settings > Accessibility. Only filters volume up/down, and only
 * consumes them while a Music screen has claimed them.
 */
class SpeakerVolumeKeyService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return false
        }
        val handler = SpeakerVolumeKeys.handler ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) handler(direction)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
