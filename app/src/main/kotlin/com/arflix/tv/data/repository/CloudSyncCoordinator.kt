package com.arflix.tv.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncCoordinator @Inject constructor(
    private val invalidationBus: CloudSyncInvalidationBus,
    private val cloudSyncRepository: CloudSyncRepository,
    private val authRepository: AuthRepository,
    private val lanSyncService: LanSyncService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var collectorJob: Job? = null
    private var flushJob: Job? = null

    private val started = AtomicBoolean(false)

    fun start() {
        synchronized(lifecycleLock) {
            if (!started.compareAndSet(false, true)) return
            collectorJob = scope.launch {
                invalidationBus.events.collectLatest { invalidation ->
                    // Always mark dirty regardless of auth — dirty flag gates pullFromCloud()
                    // pre-push, which uses xadarr-server and doesn't require Supabase auth.
                    // Auth check stays only in scheduleFlush to gate the debounced background push.
                    cloudSyncRepository.markLocalStateDirtyNow()
                    scheduleFlush(invalidation)
                }
            }
            // When a new LAN peer appears, push our current settings to it.
            // The receiving device's conflict check (master / last-change-wins) decides whether to apply.
            scope.launch {
                var prevPeerCount = 0
                lanSyncService.peers.collect { peers ->
                    val newCount = peers.size
                    if (newCount > prevPeerCount) {
                        delay(500L) // let mDNS resolve settle
                        runCatching { cloudSyncRepository.pushToCloud() }
                            .onFailure { Log.w("CloudSyncCoordinator", "Post-discovery push failed: ${it.message}") }
                    }
                    prevPeerCount = newCount
                }
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            started.set(false)
            collectorJob?.cancel()
            flushJob?.cancel()
            collectorJob = null
            flushJob = null
        }
    }

    private fun scheduleFlush(invalidation: CloudSyncInvalidation) {
        synchronized(lifecycleLock) {
            if (!started.get()) return
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(debounceMsFor(invalidation.scope))
                // Push to whichever backends are available (server, Drive, LAN peers)
                // No auth guard — works without Supabase login
                runCatching { cloudSyncRepository.pushToCloud() }
                    .onFailure { error ->
                        Log.w("CloudSyncCoordinator", "Cloud push failed after ${invalidation.scope}: ${error.message}")
                        cloudSyncRepository.markLocalStateDirty()
                    }
            }
        }
    }

    private fun debounceMsFor(scope: CloudSyncScope): Long {
        return when (scope) {
            CloudSyncScope.LOCAL_HISTORY -> 2_000L
            CloudSyncScope.IPTV -> 750L
            else -> 500L
        }
    }
}
