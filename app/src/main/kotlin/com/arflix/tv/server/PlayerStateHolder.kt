package com.arflix.tv.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerStateHolder @Inject constructor() {
    data class State(
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val title: String = "",
        val episodeTitle: String? = null,
        val overview: String? = null,
        val posterUrl: String? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val streamUrl: String? = null,
        val isLive: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = State()
    }
}
