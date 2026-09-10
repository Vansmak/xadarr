package com.arflix.tv.util

import android.view.Window
import android.view.WindowManager

/**
 * Single owner of [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON].
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
 * Requests are tracked by tag and the flag is held while any tag is active, so neither caller can
 * clear the other's. Callers must release their own tag on dispose. Main thread only.
 */
object KeepAwake {

    const val TAG_LIVE_TV = "live_tv"
    const val TAG_PLAYER = "player"

    private val active = mutableSetOf<String>()

    /** Assert or drop [tag]. The flag stays set while any other tag is still active. */
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
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
