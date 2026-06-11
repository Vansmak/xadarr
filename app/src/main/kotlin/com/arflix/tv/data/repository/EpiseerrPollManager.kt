package com.arflix.tv.data.repository

import android.util.Log
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpiseerrPollManager @Inject constructor(
    private val episeerrRepository: EpiseerrRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "EpiseerrPollManager"

    private val _pendingTmdbIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingTmdbIds: StateFlow<Set<String>> = _pendingTmdbIds.asStateFlow()

    private var pollJob: Job? = null

    fun startPolling() {
        if (pollJob?.isActive == true) return
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
    }
}
