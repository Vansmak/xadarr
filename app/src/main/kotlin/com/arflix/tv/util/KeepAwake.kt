package com.arflix.tv.util

import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.Window

/**
 * Owns a platform [MediaSession] used only to report [PlaybackState.STATE_PLAYING] during real
 * playback — the fullscreen VOD player ([com.arflix.tv.ui.screens.player.PlayerScreen], while
 * composed) and the activity-scoped live TV player ([com.arflix.tv.MainActivity], while actually
 * playing and resumed).
 *
 * Android TV's daydream trigger is an idle-since-last-remote-key timer, independent of wake locks
 * or [android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] — it does not know a video is
 * playing unless the app publishes a system [MediaSession] in [PlaybackState.STATE_PLAYING].
 * Without one (Xadarr never had one before Sept 2026), the daydream launched right over an
 * actively-playing ExoPlayer instance whenever nobody touched the remote. This is the documented
 * Android TV contract other players (Netflix, YouTube, Plex) already follow.
 *
 * This object does **not** own [android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]
 * itself — that flag is held unconditionally by [com.arflix.tv.MainActivity]'s own
 * onResume/onPause for as long as the app is open at all, deliberately outside Compose. An
 * earlier version tried to scope the flag to a Compose-tracked foreground/playback state through
 * this singleton; it still let the screen sleep and the daydream launch mid-movie on a real
 * device on 2026-09-10 (ExoPlayer actively playing, zero MediaSession registered, then a genuine
 * "Going to sleep due to timeout" a few minutes later) — a Compose effect is one more layer that
 * can silently fail to fire or fire out of order, and this doesn't need to be a Compose problem.
 *
 * Requests are tracked by tag and the session is held active while any tag is active, so neither
 * caller can clear the other's. Callers must release their own tag on dispose. Main thread only.
 */
object KeepAwake {

    const val TAG_LIVE_TV = "live_tv"
    const val TAG_PLAYER = "player"

    private val playbackTags = mutableSetOf<String>()
    private var mediaSession: MediaSession? = null

    /** Assert or drop [tag]. The session stays active while any other playback tag still is. */
    fun request(window: Window?, tag: String, wanted: Boolean) {
        if (wanted) playbackTags.add(tag) else playbackTags.remove(tag)
        apply(window)
    }

    /** Drop [tag] unconditionally — for `onDispose`. */
    fun release(window: Window?, tag: String) = request(window, tag, false)

    private fun apply(window: Window?) {
        window ?: return
        if (playbackTags.isEmpty()) {
            mediaSession?.apply {
                setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
                isActive = false
                release()
            }
            mediaSession = null
        } else {
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
