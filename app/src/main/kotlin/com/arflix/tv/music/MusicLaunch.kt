package com.arflix.tv.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Counts Xadarr Music launches (MainActivity onCreate/onNewIntent: the launcher, or Home
 * Assistant's xadarrmusic://open). Each one re-opens the Music screen on this TV's home room.
 */
object MusicLaunch {
    private val _opens = MutableStateFlow(0)
    val opens: StateFlow<Int> = _opens.asStateFlow()
    fun opened() { _opens.value++ }
}
