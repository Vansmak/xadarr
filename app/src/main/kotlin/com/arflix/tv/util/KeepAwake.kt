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
 *
 * The window flag and the MediaSession are driven separately. Before any of the above existed,
 * the flag was held unconditionally for as long as Xadarr was open at all, playing or not — a
 * real bug (the TV could never sleep while the app was simply open), fixed by scoping the flag to
 * actual playback. That fix had a side effect nobody wanted: sitting idle on the guide, not
 * playing anything, now let the daydream in mid-session — something that literally could not
 * happen before, because the flag was always held. [setForeground] restores that: the screen
 * stays on for as long as Xadarr is the foregrounded app, independent of playback, and releases
 * the moment the app is actually left (Home, another app) — unlike the original bug, which never
 * released at all. The MediaSession stays scoped to genuine playback only ([request]/[release]):
 * claiming STATE_PLAYING while just browsing menus would be a false signal to the system and to
 * voice/media controls, so it must not follow foreground state.
 */
object KeepAwake {

    const val TAG_LIVE_TV = "live_tv"
    const val TAG_PLAYER = "player"

    private val playbackTags = mutableSetOf<String>()
    private var foreground = false
    private var mediaSession: MediaSession? = null

    /** Assert or drop [tag]. The session stays active while any other playback tag still is. */
    fun request(window: Window?, tag: String, wanted: Boolean) {
        if (wanted) playbackTags.add(tag) else playbackTags.remove(tag)
        apply(window)
    }

    /** Drop [tag] unconditionally — for `onDispose`. */
    fun release(window: Window?, tag: String) = request(window, tag, false)

    /** Hold the screen on for as long as Xadarr is the foregrounded app, playback aside. */
    fun setForeground(window: Window?, active: Boolean) {
        foreground = active
        apply(window)
    }

    private fun apply(window: Window?) {
        window ?: return
        if (foreground || playbackTags.isNotEmpty()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
