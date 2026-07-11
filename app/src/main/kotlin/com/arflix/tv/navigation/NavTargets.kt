package com.arflix.tv.navigation

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One-shot pending action for a `seeall:*` (or future Home-only) nav target
 * activated from a screen other than Home. The activating screen sets this
 * and navigates Home via its existing `onNavigateToHome()` callback; Home
 * consumes and clears it on next composition so the see-all opens on arrival
 * without a second press. See NavTargets.activate.
 */
object PendingHomeNavTarget {
    val value = MutableStateFlow<String?>(null)
}

/**
 * Resolves an open-ended NavSectionConfig.target string (kind == CUSTOM) into
 * an action, using whichever of the fixed onNavigateToX callbacks the calling
 * screen already has. Route-shaped targets (guide/search/app:<id>) reuse
 * those callbacks directly — no new navigation plumbing needed. `seeall:*` is
 * Home-only (it opens Home's row-browse overlay), so it's deferred via
 * [PendingHomeNavTarget] + onNavigateToHome().
 *
 * Unknown/not-yet-built targets (e.g. "recordings") are a safe no-op.
 */
object NavTargets {
    fun activate(
        target: String?,
        onNavigateToHome: () -> Unit,
        onNavigateToSearch: () -> Unit,
        onNavigateToTv: () -> Unit,
        onNavigateToCameras: () -> Unit,
        onNavigateToWatchlist: () -> Unit = {},
    ) {
        if (target.isNullOrBlank()) return
        when {
            target == "guide" -> onNavigateToTv()
            target == "search" -> onNavigateToSearch()
            target == "app:cameras" -> onNavigateToCameras()
            target == "watchlist" -> onNavigateToWatchlist()
            target.startsWith("seeall:") -> {
                PendingHomeNavTarget.value.value = target
                onNavigateToHome()
            }
            else -> Unit
        }
    }
}
