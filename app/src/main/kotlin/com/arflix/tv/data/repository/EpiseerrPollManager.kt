package com.arflix.tv.data.repository

import android.util.Log
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class EpiseerrToast(
    val event: String,
    val title: String,
    val rule: String?,
    val season: Int?,
    val episode: Int?,
)

@Singleton
class EpiseerrPollManager @Inject constructor(
    private val episeerrRepository: EpiseerrRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "EpiseerrPollManager"

    private val _pendingTmdbIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingTmdbIds: StateFlow<Set<String>> = _pendingTmdbIds.asStateFlow()

    private val _toastEvents = MutableSharedFlow<EpiseerrToast>(replay = 0, extraBufferCapacity = 1)
    val toastEvents: SharedFlow<EpiseerrToast> = _toastEvents.asSharedFlow()

    private var lastSeenTimestamp: String? = null
    private var pollJob: Job? = null
    private var sessionStartedAtMs = 0L

    fun startPolling() {
        if (pollJob?.isActive == true) return
        sessionStartedAtMs = System.currentTimeMillis()
        pollJob = scope.launch {
            while (true) {
                refresh()
                delay(60_000L)
            }
        }
    }

    fun refreshOnResume() {
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        if (!episeerrRepository.isConfigured()) return
        try {
            val pending = episeerrRepository.getPendingItems()
            _pendingTmdbIds.value = pending.mapNotNull { it.tmdbId }.toSet()
        } catch (e: Exception) {
            Log.d(tag, "pending refresh failed: ${e.message}")
        }

        try {
            val events = episeerrRepository.getRecentEpiseerrEvents(lastSeenTimestamp)
            if (events.isNotEmpty()) {
                lastSeenTimestamp = events.first().optString("timestamp").ifBlank { null }
                val sessionWarm = System.currentTimeMillis() - sessionStartedAtMs > 5_000L
                if (sessionWarm) {
                    for (evt in events) {
                        _toastEvents.tryEmit(
                            EpiseerrToast(
                                event = evt.optString("event"),
                                title = evt.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: "Unknown",
                                rule = evt.optString("rule").takeIf { it.isNotBlank() && it != "null" },
                                season = evt.optInt("season").takeIf { it != 0 },
                                episode = evt.optInt("episode").takeIf { it != 0 },
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "events refresh failed: ${e.message}")
        }
    }
}
