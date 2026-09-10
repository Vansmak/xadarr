package com.arflix.tv.util

import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.Window
import android.view.WindowManager

/**
 * Single owner of [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] and of a platform
 * [MediaSession] used only to report [PlaybackState.STATE_PLAYING].
 *
 * Two independent places need the screen kept awake during playback: the fullscreen VOD player
 * ([com.arflix.tv.ui.screens.player.PlayerScreen], while it is composed) and the activity-scoped
 * live TV player ([com.arflix.tv.MainActivity], while it is actually playing and resumed).
 *
 * Both used to add and clear the window flag directly, so whichever ran last won. Opening a film
 * dismisses the live mini-player, which flips `isPlaying` to false and re-ran MainActivity's
 * effect — clearing the flag out from under the player screen that had just set it, and letting
 * the screensaver in mid-film. Sept 2026.
 *
 * That flag alone also turned out not to be enough on its own: Android TV's daydream trigger is
 * an idle-since-last-remote-key timer, independent of wake locks or the screen-on flag — it does
 * not know a video is playing unless the app publishes a system [MediaSession] in
 * [PlaybackState.STATE_PLAYING]. Without one (Xadarr never had one), the daydream launched right
 * over an actively-playing ExoPlayer instance whenever nobody touched the remote. This is the
 * documented Android TV contract other players (Netflix, YouTube, Plex) already follow.
 *
 * Requests are tracked by tag and both are held while any tag is active, so neither caller can
 * clear the other's. Callers must release their own tag on dispose. Main thread only.
 */
object KeepAwake {

    const val TAG_LIVE_TV = "live_tv"
    const val TAG_PLAYER = "player"

    private val active = mutableSetOf<String>()
    private var mediaSession: MediaSession? = null

    /** Assert or drop [tag]. The flag and session stay active while any other tag still is. */
    fun request(window: Window?, tag: String, wanted: Boolean) {
        if (wanted) active.add(tag) else active.remove(tag)
        apply(window)
    }

    /** Drop [tag] unconditionally — for `onDispose`. */
    fun release(window: Window?, tag: String) = request(window, tag, false)

    private fun apply(window: Window?) {
        window ?: return
        if (active.isEmpty()) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            mediaSession?.apply {
                setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
                isActive = false
                release()
            }
            mediaSession = null
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val session = mediaSession ?: MediaSession(window.context, "XadarrKeepAwake").also {
                mediaSession = it
            }
            session.isActive = true
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build()
            )
        }
    }
}
