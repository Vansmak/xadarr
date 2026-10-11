package com.arflix.tv.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class CloudSyncScope {
    PROFILE_SETTINGS,
    PROFILES,
    ADDONS,
    CATALOGS,
    IPTV,
    WATCHLIST,
    LOCAL_HISTORY,
    ACCOUNT
}

data class CloudSyncInvalidation(
    val scope: CloudSyncScope,
    val profileId: String? = null,
    val reason: String = "",
    val changedAt: Long = System.currentTimeMillis()
)

@Singleton
class CloudSyncInvalidationBus @Inject constructor() {
    private val _events = MutableSharedFlow<CloudSyncInvalidation>(
        extraBufferCapacity = 64
    )
    val events: SharedFlow<CloudSyncInvalidation> = _events.asSharedFlow()

    private val restoreDepth = AtomicInteger(0)

    val isApplyingRemoteState: Boolean
        get() = restoreDepth.get() > 0

    /**
     * [force] bypasses the remote-apply suppression, for genuine local changes (e.g. a favourite
     * id repair) that merely happen to run while a restore is being applied elsewhere.
     */
    fun markDirty(scope: CloudSyncScope, profileId: String? = null, reason: String = "", force: Boolean = false) {
        if (isApplyingRemoteState && !force) return
        _events.tryEmit(
            CloudSyncInvalidation(
                scope = scope,
                profileId = profileId?.trim()?.takeIf { it.isNotBlank() },
                reason = reason
            )
        )
    }

    suspend fun <T> suppressDuringRemoteApply(block: suspend () -> T): T {
        restoreDepth.incrementAndGet()
        return try {
            block()
        } finally {
            restoreDepth.updateAndGet { depth -> (depth - 1).coerceAtLeast(0) }
        }
    }
}
