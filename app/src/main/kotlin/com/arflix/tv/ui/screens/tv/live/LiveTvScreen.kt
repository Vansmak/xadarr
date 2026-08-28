@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.arflix.tv.data.model.GroupState
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvProgram
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.RawProviderStream
import com.arflix.tv.data.repository.reminderKey
import com.arflix.tv.ui.screens.tv.TvUiState
import com.arflix.tv.ui.screens.tv.TvViewModel
import com.arflix.tv.ui.components.AppTopBarHeight
import com.arflix.tv.util.LocalNeolinkConfigured
import com.arflix.tv.util.LocalDeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LiveTvFocusZone {
    CATEGORY_LIST,
    CHANNEL_LIST,
    EPG,
}

private fun chooseStartupChannelId(
    filteredChannels: List<EnrichedChannel>,
    explicitInitialChannelId: String?,
    sessionLastChannelId: String,
    hasOpenedBefore: Boolean,
    favoriteChannelIds: List<String>,
    isFullyEnriched: Boolean,
    // Every channel id regardless of category, not just the currently-selected one.
    // explicitInitialChannelId is an explicit request (program reminder deep link, or a Remote
    // Mode tune) — it must be honored no matter what category happens to be selected, but
    // non-touch devices default selectedCategoryId to "fav" on a fresh composition (see its
    // declaration), so gating this against filteredChannels silently dropped any tune to a
    // non-favorited channel on a TV. Confirmed on-device: toast said "Tuned", server emitted
    // correctly, fresh screen recomposed (the flicker), and it still stayed on the old channel.
    allChannelIds: Set<String> = emptySet(),
): String? {
    explicitInitialChannelId
        ?.takeIf { id -> allChannelIds.contains(id) || filteredChannels.any { it.id == id } }
        ?.let { return it }
    if (explicitInitialChannelId != null && !isFullyEnriched) return null

    // Resume where the last session left off before falling back to favorites — favorites
    // used to be checked first, which meant the startup channel never varied for anyone with
    // favorites set: it always won over sessionLastChannelId below, no matter what was last
    // watched. Favorites are still the right fallback for a brand-new session that has no
    // watch history yet.
    if (hasOpenedBefore) {
        sessionLastChannelId
            .takeIf { id -> id.isNotBlank() && filteredChannels.any { it.id == id } }
            ?.let { return it }

        if (sessionLastChannelId.isNotBlank() && !isFullyEnriched) return null
    }

    favoriteChannelIds
        .firstOrNull { id -> filteredChannels.any { it.id == id } }
        ?.let { return it }
    if (favoriteChannelIds.isNotEmpty() && !isFullyEnriched) return null

    return filteredChannels.first().id
}

/**
 * Live TV screen — Xadarr spec §1. Three focus regions: Sidebar ↔ MiniPlayer ↔ EPG.
 * Preserves every IPTV feature from the legacy [com.arflix.tv.ui.screens.tv.TvScreen]
 * (favorites, hidden groups, EPG refresh, cloud sync) — only the UI shell is new.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LiveTvScreen(
    viewModel: TvViewModel = hiltViewModel(),
    playerViewModel: LiveTvPlayerViewModel,
    currentProfile: Profile? = null,
    initialChannelId: String? = null,
    initialStreamUrl: String? = null,
    // Remote Mode text-entry popup: a TypeText command lands here since Home IS the guide and
    // its own SearchOverlay is the app's only search surface post-TiviMate-redesign.
    initialSearchQuery: String? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToDiscover: () -> Unit = {},
    onNavigateToCameras: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAllApps: () -> Unit = {},
    onNavigateToMovies: () -> Unit = {},
    onNavigateToShows: () -> Unit = {},
    onNavigateToDetails: (MediaType, Int) -> Unit = { _, _ -> },
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    // Lifecycle-aware collection so the screen stops draining state updates
    // the instant the user backs out — matters on a long-running IPTV flow
    // where the ViewModel pushes EPG refreshes every few seconds.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentUiState by rememberUpdatedState(state)
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val deviceType = LocalDeviceType.current
    val isTouchDevice = deviceType.isTouchDevice()
    val useTouchRail = isTouchDevice && configuration.smallestScreenWidthDp < 600
    val compactTouchLayout = isTouchDevice && configuration.screenWidthDp < 900
    val showTopBar = !isTouchDevice
    val contentTopPadding = if (showTopBar) AppTopBarHeight else 0.dp
    var guideClockMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var epgScrollToNowSignal by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            guideClockMillis = System.currentTimeMillis()
        }
    }
    var selectedCategoryId by rememberSaveable { mutableStateOf(if (!isTouchDevice) "fav" else "all") }
    var guideGroupsVisible by rememberSaveable { mutableStateOf(false) }
    var miniPlayerHeightPx by remember { mutableIntStateOf(0) }
    val favoriteSortMode by viewModel.favoriteSortMode.collectAsStateWithLifecycle()
    val recents = remember { mutableStateOf<LinkedHashSet<String>>(LinkedHashSet()) }
    val favSet = remember(state.snapshot.favoriteChannels) { state.snapshot.favoriteChannels.toSet() }
    val hiddenGroupSet = remember(state.snapshot.hiddenGroups, state.snapshot.newGroups, state.snapshot.removedGroups) {
        (state.snapshot.hiddenGroups + state.snapshot.newGroups + state.snapshot.removedGroups).toSet()
    }
    val newGroupSet = remember(state.snapshot.newGroups) { state.snapshot.newGroups.toSet() }
    val removedGroupSet = remember(state.snapshot.removedGroups) { state.snapshot.removedGroups.toSet() }
    var seededRecentSessionChannel by rememberSaveable { mutableStateOf(false) }
    // Channels pinned from a full-provider catalog search (see SearchOverlay) — not part of
    // the Dispatcharr M3U, merged in here purely for guide rendering/playback so they behave
    // like any other channel without touching IptvRepository's M3U cache pipeline.
    val pinnedProviderChannels by viewModel.pinnedProviderChannels.collectAsStateWithLifecycle()
    val dispatcharrCatalogAvailable by viewModel.dispatcharrCatalogAvailable.collectAsStateWithLifecycle()
    val remoteTarget by viewModel.remoteTarget.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshDispatcharrCatalogAvailability() }
    // A "watch now" (not pinned) full-provider search pick — same reason pinned channels are
    // merged below: playback is derived from enrichedState.index.byId, so anything not merged
    // into it never actually resolves to a stream URL and playback silently no-ops (Joe,
    // 2026-08-14: "pressed something and it just returned to guide"). Single slot — a new pick
    // replaces the last one; pinning moves it into the persisted list instead.
    var ephemeralSearchPick by remember { mutableStateOf<IptvChannel?>(null) }
    LaunchedEffect(state.tvSession.lastChannelId) {
        if (!seededRecentSessionChannel && state.tvSession.lastChannelId.isNotBlank()) {
            recents.value = LinkedHashSet<String>().apply { add(state.tvSession.lastChannelId) }
            seededRecentSessionChannel = true
        }
    }

    // Enrichment runs on a background dispatcher and is published through state
    // — avoids blocking recomposition for 10k+ playlists. Result is cached in
    // the ViewModel so re-visits to the TV page are instant (no 2-3s stall).
    val enrichedState = remember {
        mutableStateOf<EnrichedChannels>(
            (viewModel.cachedEnrichedChannels as? EnrichedChannels) ?: EnrichedChannels.Empty
        )
    }
    // Keyed off a stable channel-identity signature (count + first/last id), not the
    // raw channels list — that list gets a new reference on every EPG/nowNext merge
    // (nowNext is applied as a separate field per channel, not just carried alongside),
    // even when the actual set of channels hasn't changed. Keying on the full list let
    // frequent EPG merges cancel-and-restart this block's expensive enrichment
    // (buildCategoryTree + per-channel enrich() over hundreds of channels) before it
    // could ever finish, permanently stuck showing the cheap "initial" partial result
    // from the first pass (Joe, 2026-07-12: TV guide stuck at 1 channel while
    // TvViewModel's own logs showed the full 681-channel load completing in the
    // background). nowNext itself is passed to EpgGrid separately (state.snapshot.nowNext)
    // so it keeps updating live regardless of this effect's key.
    val channelsIdentitySignature = "${state.snapshot.channels.size}:" +
        "${state.snapshot.channels.firstOrNull()?.id}:${state.snapshot.channels.lastOrNull()?.id}:" +
        "pinned=${pinnedProviderChannels.size}:${pinnedProviderChannels.joinToString(",") { it.id }}:" +
        "ephemeral=${ephemeralSearchPick?.id}"
    LaunchedEffect(channelsIdentitySignature) {
        val snapshot = state.snapshot.channels +
            pinnedProviderChannels.map { it.toIptvChannel() } +
            listOfNotNull(ephemeralSearchPick)
        if (snapshot.isEmpty()) {
            enrichedState.value = EnrichedChannels.Empty
            return@LaunchedEffect
        }
        // Skip re-enrichment if we already have a cache for the same playlist.
        val signature = channelsIdentitySignature
        if (viewModel.cachedChannelsSignature == signature &&
            viewModel.cachedEnrichedChannels is EnrichedChannels
        ) {
            enrichedState.value = viewModel.cachedEnrichedChannels as EnrichedChannels
            return@LaunchedEffect
        }

        val initialChannels = withContext(Dispatchers.Default) {
            buildInitialCategoryChannels(
                channels = snapshot,
                categoryId = selectedCategoryId,
                favorites = favSet,
                recents = recents.value,
                limit = snapshot.size,
            )
        }
        val initialIndex = withContext(Dispatchers.Default) { buildCategoryIndex(initialChannels) }
        val initialTree = withContext(Dispatchers.Default) {
            buildCategoryTree(
                channels = initialChannels,
                favoritesCount = favSet.count { it in initialIndex.byId },
                recentCount = recents.value.count { it in initialIndex.byId },
                hiddenGroups = state.snapshot.hiddenGroups.toSet(),
                newGroups = newGroupSet,
                removedGroups = removedGroupSet,
                groupOrder = state.snapshot.groupOrder,
            )
        }
        enrichedState.value = EnrichedChannels(
            all = initialChannels,
            tree = initialTree,
            index = initialIndex,
        )
        val enriched = withContext(Dispatchers.Default) {
            snapshot.mapIndexed { idx, ch -> ch.enrich(100 + idx) }
        }
        val index = withContext(Dispatchers.Default) { buildCategoryIndex(enriched) }
        val tree = withContext(Dispatchers.Default) {
            buildCategoryTree(
                channels = enriched,
                favoritesCount = favSet.count { it in index.byId },
                recentCount = recents.value.count { it in index.byId },
                hiddenGroups = state.snapshot.hiddenGroups.toSet(),
                newGroups = newGroupSet,
                removedGroups = removedGroupSet,
                groupOrder = state.snapshot.groupOrder,
            )
        }
        val value = EnrichedChannels(all = enriched, tree = tree, index = index)
        enrichedState.value = value
        viewModel.cachedEnrichedChannels = value
        viewModel.cachedChannelsSignature = signature
    }
    // Re-evaluate only dynamic counts when favorites/recents/hidden change.
    LaunchedEffect(favSet, hiddenGroupSet, newGroupSet, removedGroupSet, state.snapshot.groupOrder, recents.value, enrichedState.value.all) {
        val current = enrichedState.value
        if (current === EnrichedChannels.Empty) return@LaunchedEffect
        val byId = current.index.byId
        val tree = withContext(Dispatchers.Default) {
            buildCategoryTree(
                channels = current.all,
                favoritesCount = favSet.count { it in byId },
                recentCount = recents.value.count { it in byId },
                hiddenGroups = state.snapshot.hiddenGroups.toSet(),
                newGroups = newGroupSet,
                removedGroups = removedGroupSet,
                groupOrder = state.snapshot.groupOrder,
            )
        }
        enrichedState.value = current.copy(tree = tree)
    }
    LaunchedEffect(hiddenGroupSet, selectedCategoryId, enrichedState.value.tree) {
        val builtIn = selectedCategoryId == "all" || selectedCategoryId == "favorites" || selectedCategoryId == "recent"
        if (!builtIn && enrichedState.value.tree.byId(selectedCategoryId) == null) {
            selectedCategoryId = "all"
        }
    }

    // Selected category (persist across nav). Defaults to "all".
    val hasProfile = currentProfile != null
    val neolinkConfigured = LocalNeolinkConfigured.current
    val navSections = com.arflix.tv.util.LocalNavSections.current
    var focusZone by rememberSaveable { mutableStateOf(LiveTvFocusZone.CATEGORY_LIST) }
    val isNavRailOpen = com.arflix.tv.ui.components.rememberNavRailOpen()
    // Driven directly by this screen's own key handler below rather than NavRail's
    // internal FocusRequester — see NavRail.kt's doc comment / HomeScreen.kt's
    // identical fix (real Compose focus never reliably lands inside NavRail).
    val navRailFocusedIndex = remember { mutableStateOf(0) }
    LaunchedEffect(isNavRailOpen.value) {
        if (isNavRailOpen.value) navRailFocusedIndex.value = 0
    }
    // A KeyDown consumed by the rail block below still has a matching KeyUp on
    // the way, arriving after isNavRailOpen.value has already flipped back to
    // false — swallow it explicitly so it can't leak through to a background
    // card's own click (Joe, 2026-07-11: activating a NavRail entry was
    // landing on whatever card had focus before the rail opened).
    var pendingRailKeyUp by remember { mutableStateOf<Key?>(null) }

    // Category switches are served from prebuilt buckets. Favorites and
    // recents remain ordered dynamic lists, but they are simple id lookups.
    val filteredChannelsState = remember { mutableStateOf<List<EnrichedChannel>>(emptyList()) }
    val recentsFilterKey = if (selectedCategoryId == "recent") recents.value else Unit
    LaunchedEffect(enrichedState.value.index, selectedCategoryId, favSet, recentsFilterKey, favoriteSortMode) {
        val result = withContext(Dispatchers.Default) {
            enrichedState.value.index.channelsFor(
                categoryId = selectedCategoryId,
                favorites = state.snapshot.favoriteChannels,
                recents = recents.value,
                sortMode = favoriteSortMode,
            )
        }
        filteredChannelsState.value = result
    }

    // Kick EPG prefetch for favorites as soon as IDs are known — before channel
    // enrichment finishes — so the guide data is ready when the user enters the list.
    LaunchedEffect(state.snapshot.favoriteChannels) {
        val favIds = state.snapshot.favoriteChannels
        if (favIds.isEmpty()) return@LaunchedEffect
        viewModel.prefetchVisibleCategoryEpg(
            channelIds = favIds,
            selectedChannelId = null,
            eagerLimit = 64,
            backgroundLimit = 240,
        )
    }
    val filteredChannels = filteredChannelsState.value
    // Fall back to "all" only when preferences have loaded and the user genuinely
    // has zero favorites — not just because the async filter hasn't run yet.
    LaunchedEffect(state.iptvPreferencesLoaded, state.snapshot.favoriteChannels.size) {
        if (!isTouchDevice && state.iptvPreferencesLoaded
            && (selectedCategoryId == "fav" || selectedCategoryId == "favorites")
            && state.snapshot.favoriteChannels.isEmpty()
        ) {
            selectedCategoryId = "all"
        }
    }

    // Playing channel — default to the one we were navigated to, else the first
    // channel of the first non-empty category.
    var playingChannelId by rememberSaveable { mutableStateOf<String?>(initialChannelId) }
    var previousChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedChannelId by rememberSaveable { mutableStateOf<String?>(initialChannelId) }
    var playingCatchupProgram by remember { mutableStateOf<IptvProgram?>(null) }
    val playingChannel = remember(playingChannelId, enrichedState.value, filteredChannels) {
        playingChannelId?.let { enrichedState.value.index.byId[it] }
            ?: filteredChannels.firstOrNull { it.id == playingChannelId }
    }
    val currentNowNext = remember(playingChannelId, playingCatchupProgram, state.snapshot.nowNext) {
        val live = playingChannelId?.let { state.snapshot.nowNext[it] }
        val catchup = playingCatchupProgram
        if (catchup != null) {
            com.arflix.tv.data.model.IptvNowNext(
                now = catchup,
                next = null,
                later = null,
                upcoming = emptyList(),
                recent = emptyList()
            )
        } else {
            live
        }
    }

    val epgPrefetchIds = remember(filteredChannels, selectedCategoryId, playingChannelId) {
        val maxPrefetch = if (selectedCategoryId == "all") 96 else 180
        buildList<String> {
            playingChannelId
                ?.takeIf { current -> filteredChannels.any { channel -> channel.id == current } }
                ?.let { add(it) }
            filteredChannels
                .asSequence()
                .map { it.id }
                .filterNot { it == playingChannelId }
                .take((maxPrefetch - size).coerceAtLeast(0))
                .forEach { add(it) }
        }
    }
    LaunchedEffect(selectedCategoryId, epgPrefetchIds, playingChannelId) {
        if (epgPrefetchIds.isNotEmpty()) {
            viewModel.prefetchVisibleCategoryEpg(
                channelIds = epgPrefetchIds,
                selectedChannelId = playingChannelId,
                eagerLimit = if (selectedCategoryId == "all") 32 else 64,
                backgroundLimit = if (selectedCategoryId == "all") 120 else 240,
            )
        }
    }

    val allChannelIds = remember(state.snapshot.channels) { state.snapshot.channels.mapTo(mutableSetOf()) { it.id } }
    // Pick the startup channel only after saved IPTV preferences/session have
    // loaded. Favorites win over a stale recent channel, then we fall back to
    // the persisted recent channel, then the first filtered entry.
    LaunchedEffect(filteredChannels, playingChannelId, initialChannelId, state.tvSession, state.snapshot.favoriteChannels, enrichedState.value.all.size, state.snapshot.channels.size, state.iptvPreferencesLoaded, state.tvSessionLoaded) {
        val startupStateReady = state.iptvPreferencesLoaded && state.tvSessionLoaded
        if (playingChannelId == null && filteredChannels.isNotEmpty() && (initialChannelId != null || startupStateReady)) {
            val result = chooseStartupChannelId(
                filteredChannels = filteredChannels,
                explicitInitialChannelId = initialChannelId,
                sessionLastChannelId = state.tvSession.lastChannelId,
                hasOpenedBefore = state.tvSession.lastOpenedAt > 0L,
                favoriteChannelIds = state.snapshot.favoriteChannels,
                isFullyEnriched = enrichedState.value.all.size >= state.snapshot.channels.size,
                allChannelIds = allChannelIds,
            )
            // TEMPORARY diagnostic — Remote Mode channel-tune has failed four fix attempts in a
            // row on-device despite each looking correct from the code. Rather than guess a
            // fifth time, surface the actual values so the next test tells us where it really
            // breaks instead of us finding out after another round-trip. Remove once resolved.
            if (initialChannelId != null) {
                Toast.makeText(
                    context,
                    "DEBUG tune: want=$initialChannelId inAll=${allChannelIds.contains(initialChannelId)} " +
                        "inCat($selectedCategoryId)=${filteredChannels.any { it.id == initialChannelId }} " +
                        "fullyEnriched=${enrichedState.value.all.size >= state.snapshot.channels.size} " +
                        "(${enrichedState.value.all.size}/${state.snapshot.channels.size}) got=$result",
                    Toast.LENGTH_LONG,
                ).show()
            }
            playingChannelId = result
            // An explicit request (deep link / Remote Mode tune) just got honored outside
            // whatever category was selected (e.g. this device's default "fav" on first
            // launch) — switch to "all" so the guide actually shows the channel that's now
            // playing instead of a category list that doesn't contain it.
            if (playingChannelId != null && initialChannelId == playingChannelId &&
                filteredChannels.none { it.id == playingChannelId }
            ) {
                selectedCategoryId = "all"
            }
        }
        if (focusedChannelId == null || filteredChannels.none { it.id == focusedChannelId }) {
            focusedChannelId = playingChannelId?.takeIf { id -> filteredChannels.any { it.id == id } }
                ?: filteredChannels.firstOrNull()?.id
        }
    }

    val sidebarExpanded = !useTouchRail
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var showRemoteModeSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialSearchQuery) {
        if (!initialSearchQuery.isNullOrBlank()) searchOpen = true
    }
    // Long-press Down in fullscreen jumps to the Recent category (TiviMate convention, Joe
    // 2026-08-15) instead of the normal quick-zap-to-next-channel a short press does.
    val fsScope = rememberCoroutineScope()
    var downPressed by remember { mutableStateOf(false) }
    var downLongPressConsumed by remember { mutableStateOf(false) }
    var downLongPressJob by remember { mutableStateOf<Job?>(null) }
    // Long-press Up opens search — same reasoning as long-press Down above, but for search
    // specifically: Key.Search (bound elsewhere) is unreliable across remotes (Shield's has no
    // dedicated search key), so this is the guaranteed-to-work path (Joe, 2026-08-15).
    var upPressed by remember { mutableStateOf(false) }
    var upLongPressConsumed by remember { mutableStateOf(false) }
    var upLongPressJob by remember { mutableStateOf<Job?>(null) }
    var favoriteMenuChannel by remember { mutableStateOf<EnrichedChannel?>(null) }
    var programInfoTarget by remember { mutableStateOf<Pair<EnrichedChannel, IptvProgram>?>(null) }
    var focusSelectedChannelSignal by remember { mutableIntStateOf(0) }
    var focusEpgSignal by remember { mutableIntStateOf(0) }
    var focusSearchCategorySignal by remember { mutableIntStateOf(1) }
    var focusCategorySignal by remember { mutableIntStateOf(0) }
    var focusActiveCategorySignal by remember { mutableIntStateOf(0) }
    val rememberedChannelByCategory = remember { mutableMapOf<String, String>() }
    // Full-screen playback mode — pressing OK on an EPG row expands the
    // mini-player to cover the whole screen. Back collapses back to the grid.
    var isFullScreen by rememberSaveable { mutableStateOf(initialStreamUrl != null) }
    LaunchedEffect(isFullScreen) {
        onFullscreenChanged(isFullScreen)
    }
    DisposableEffect(Unit) {
        onDispose { onFullscreenChanged(false) }
    }
    // Focus requesters for the three regions.
    val sidebarFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
    val fsFocus = remember { FocusRequester() }

    // Monotonic counter bumped on every DPAD key while in fullscreen —
    // the HUD observes this to re-show and reset its auto-hide timer.
    var hudPokeSignal by remember { mutableStateOf(0) }

    DisposableEffect(activity, isFullScreen, isTouchDevice) {
        if (!isTouchDevice || !isFullScreen) {
            return@DisposableEffect onDispose { }
        }

        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window
        if (window != null) {
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            if (previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
            if (window != null) {
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Prev/next zapping across the full enriched list (not the filtered
    // category) per user spec. Wraps around.
    fun zap(delta: Int) {
        val all = enrichedState.value.all
        if (all.isEmpty()) return
        val currentIdx = all.indexOfFirst { it.id == playingChannelId }
        val start = if (currentIdx >= 0) currentIdx else 0
        val size = all.size
        val nextIdx = ((start + delta) % size + size) % size
        previousChannelId = playingChannelId
        playingChannelId = all[nextIdx].id
        focusedChannelId = all[nextIdx].id
        rememberedChannelByCategory[selectedCategoryId] = all[nextIdx].id
        playingCatchupProgram = null
    }

    fun returnToPreviousChannel() {
        val prev = previousChannelId?.takeIf { id -> enrichedState.value.all.any { it.id == id } } ?: return
        previousChannelId = playingChannelId
        playingChannelId = prev
        focusedChannelId = prev
        rememberedChannelByCategory[selectedCategoryId] = prev
        playingCatchupProgram = null
        hudPokeSignal++
    }

    fun openSidebar() {
        guideGroupsVisible = true
        focusZone = LiveTvFocusZone.CATEGORY_LIST
        focusActiveCategorySignal += 1
        runCatching { sidebarFocus.requestFocus() }
    }

    fun focusPlaylistSearch() {
        guideGroupsVisible = true
        focusZone = LiveTvFocusZone.CATEGORY_LIST
        focusSearchCategorySignal += 1
        runCatching { sidebarFocus.requestFocus() }
    }

    fun focusFirstCategory() {
        guideGroupsVisible = true
        focusZone = LiveTvFocusZone.CATEGORY_LIST
        focusCategorySignal += 1
        runCatching { sidebarFocus.requestFocus() }
    }

    fun focusChannelList(channelId: String? = focusedChannelId ?: playingChannelId) {
        guideGroupsVisible = false
        channelId?.let {
            focusedChannelId = it
            rememberedChannelByCategory[selectedCategoryId] = it
        }
        focusZone = LiveTvFocusZone.CHANNEL_LIST
        // Only bump the signal — EpgGrid's own LaunchedEffect(focusSelectedChannelSignal, ...)
        // retries onto the specific row's FocusRequester (the one that actually handles
        // Key.DirectionLeft etc). A synchronous epgFocus.requestFocus() here used to win that
        // race and land on the grid's outer container instead — a target with no Left-key
        // handling of its own — so the very next Left press (right after exiting fullscreen)
        // was silently swallowed until the row-level retry corrected focus ~30-180ms later.
        focusSelectedChannelSignal += 1
    }

    fun focusEpg(channelId: String) {
        focusedChannelId = channelId
        rememberedChannelByCategory[selectedCategoryId] = channelId
        focusZone = LiveTvFocusZone.EPG
        // Same reasoning as focusChannelList() above — let EpgGrid's own
        // LaunchedEffect(focusEpgSignal, ...) retry onto the actual program cell.
        focusEpgSignal += 1
    }

    fun exitFullScreenPlayback() {
        isFullScreen = false
        focusChannelList(playingChannelId ?: focusedChannelId)
    }

    // Remote Mode — when a target is set, channel selection dispatches to that device
    // instead of tuning locally. Returns true if the tap was handled remotely (caller should
    // do nothing else); false means proceed with normal local tuning.
    fun remoteTuneOrHandled(channel: EnrichedChannel): Boolean {
        val target = viewModel.remoteTarget.value ?: return false
        val epgId = channel.source.epgId
        if (epgId.isNullOrBlank()) {
            Toast.makeText(context, "This channel can't be remote-tuned (no EPG id)", Toast.LENGTH_SHORT).show()
            return true
        }
        // This device's own live audio shouldn't keep playing once we're redirecting to the
        // target — same reasoning as DetailsScreen's remote-play branch.
        playerViewModel.pauseForVod()
        fsScope.launch {
            val ok = viewModel.sendRemoteTuneChannel(epgId)
            Toast.makeText(
                context,
                if (ok) "Tuned ${channel.name} on ${target.displayName}" else "Couldn't reach ${target.displayName}",
                Toast.LENGTH_SHORT,
            ).show()
        }
        return true
    }

    fun selectChannel(channel: EnrichedChannel) {
        if (remoteTuneOrHandled(channel)) return
        // Selecting any channel goes straight to fullscreen — one press, like
        // TiviMate. Previously a new channel only started in the small
        // MiniPlayerRow, and selecting it again was needed to go fullscreen.
        focusedChannelId = channel.id
        rememberedChannelByCategory[selectedCategoryId] = channel.id
        if (channel.id != playingChannelId) previousChannelId = playingChannelId
        playingChannelId = channel.id
        playingCatchupProgram = null
        isFullScreen = true
        hudPokeSignal++
    }

    fun playProgramInMini(channel: EnrichedChannel, program: IptvProgram?) {
        if (remoteTuneOrHandled(channel)) return
        focusedChannelId = channel.id
        rememberedChannelByCategory[selectedCategoryId] = channel.id
        playingChannelId = channel.id
        playingCatchupProgram = program
        focusChannelList(channel.id)
    }

    // Remote Mode, receiving side — TuneChannel is deliberately NOT handled here anymore.
    // It used to mutate playingChannelId in place, but that left currentStreamUrl resolving
    // against a category-scoped lookup (see its own comment) and silently not switching
    // channel while this screen was already showing — confirmed on-device. MainActivity now
    // forces a fresh screen composition for every remote tune instead (pendingRemoteChannelId),
    // which is the path already confirmed to work. TypeText doesn't have that problem — it's
    // plain local UI state, not stream resolution — so it stays handled directly here.
    var remoteSearchQuery by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.incomingRemoteCommands.collect { command ->
            when (command) {
                is com.arflix.tv.data.repository.RemoteCommand.TypeText -> {
                    remoteSearchQuery = command.text
                    searchOpen = true
                }
                else -> Unit
            }
        }
    }

    // ExoPlayer lives in playerViewModel (activity-scoped) so it survives navigation.
    // The player config (OkHttp, load control) is set up once in LiveTvPlayerViewModel.
    val exoPlayer = playerViewModel.player

    // Channel zaps used to give zero feedback while the new stream loaded —
    // the last frame just froze with no indication the press registered.
    // Surface Player.STATE_BUFFERING so the fullscreen view can show a spinner.
    var isBuffering by remember { mutableStateOf(false) }
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }
        }
        exoPlayer.addListener(listener)
        isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING
        onDispose { exoPlayer.removeListener(listener) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                // Do NOT pause on screen-level PAUSE — player should keep playing
                // in the mini-player overlay while the user navigates other screens.
                // Process-level backgrounding is handled by LiveTvPlayerViewModel's
                // ProcessLifecycleOwner observer.
                Lifecycle.Event.ON_RESUME -> {
                    guideClockMillis = System.currentTimeMillis()
                    epgScrollToNowSignal++
                    // Losing and regaining window focus (e.g. handing off to Plex/TiviMate and
                    // coming back) drops real Compose focus without restoring it. The highlighted
                    // channel row is just styling (the isActive prop), not real focus, so D-pad
                    // input silently went nowhere on return until backing all the way out of the
                    // screen. Re-request focus onto whatever's logically current so the remote
                    // works immediately again.
                    when {
                        isFullScreen -> runCatching { fsFocus.requestFocus() }
                        guideGroupsVisible -> focusActiveCategorySignal++
                        focusZone == LiveTvFocusZone.EPG -> focusEpgSignal++
                        else -> focusSelectedChannelSignal++
                    }
                    // Resume if the ViewModel still has an active stream
                    if (playingChannelId != null && playerViewModel.state.value.isActive) {
                        exoPlayer.play()
                    }
                    if (currentUiState.isConfigured &&
                        currentUiState.snapshot.channels.isNotEmpty() &&
                        viewModel.iptvRepository.cachedEpgAgeMs() > 90_000L
                    ) {
                        viewModel.refresh(force = false, showLoading = false, forceEpg = true)
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // When the selected channel changes, swap media item.
    val currentStreamUrl = remember(playingChannel, playingChannelId, playingCatchupProgram, state.snapshot.channels) {
        val ch = playingChannel
        if (ch != null) {
            val pr = playingCatchupProgram
            if (pr != null) viewModel.iptvRepository.getCatchupUrl(ch.source, pr) else ch.streamUrl
        } else {
            // playingChannel is scoped to the currently-enriched category batch, so a
            // Remote Mode tune to a channel outside it resolves to null here even though
            // playingChannelId is correctly set — confirmed on-device: tuning worked from a
            // fresh screen (waits for full enrichment) but silently did nothing while already
            // on the guide (partial/category-scoped enrichment missed the target channel).
            // state.snapshot.channels is the full unenriched list, never category-scoped, so
            // it always has a match — falls back to it for the raw stream URL specifically;
            // display metadata (name/genre/etc.) still comes from playingChannel and just
            // catches up once enrichment includes this channel.
            playingChannelId?.let { id -> state.snapshot.channels.firstOrNull { it.id == id }?.streamUrl }
                ?: initialStreamUrl
        }
    }
    val openFullScreenPlayer = remember(playingChannelId, currentStreamUrl) {
        {
            if (playingChannelId != null || currentStreamUrl != null) {
                isFullScreen = true
                hudPokeSignal++
            }
        }
    }
    LaunchedEffect(currentStreamUrl, playingCatchupProgram) {
        val stream = currentStreamUrl ?: return@LaunchedEffect

        // If this exact URL is already playing in the ViewModel (e.g. user returned
        // from another screen), skip re-setup to avoid interrupting playback.
        if (stream == playerViewModel.state.value.streamUrl && exoPlayer.isPlaying) {
            playerViewModel.updateNowPlaying(
                channelName = playingChannel?.name.orEmpty(),
                programTitle = currentNowNext?.now?.title.orEmpty(),
            )
            return@LaunchedEffect
        }

        delay(90L)
        exoPlayer.setMediaItem(
            MediaItem.Builder()
                .setUri(stream)
                .apply {
                    if (playingCatchupProgram == null) {
                        setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setMinPlaybackSpeed(1.0f).setMaxPlaybackSpeed(1.0f)
                                .setTargetOffsetMs(4_000).build()
                        )
                    }
                }
                .build()
        )
        exoPlayer.prepare()
        exoPlayer.play()
        // Persist "recent" as soon as playback starts.
        playingChannelId?.let { id ->
            val set = LinkedHashSet(recents.value)
            set.remove(id); set.add(id)
            while (set.size > 40) set.remove(set.first())
            recents.value = set
            viewModel.rememberTvSession(
                lastChannelId = id,
                lastGroupName = selectedCategoryId,
                lastFocusedZone = "GUIDE",
                markOpened = true,
            )
            // Tell the ViewModel which channel/stream is active — used for pause/resume around
            // VOD and camera playback (LiveTvPlayerViewModel.pauseForVod()/resumeIfActive()).
            playerViewModel.setActiveChannel(
                channelId = id,
                streamUrl = stream,
                channelName = playingChannel?.name.orEmpty(),
                programTitle = currentNowNext?.now?.title.orEmpty(),
            )
        }
    }

    // Keep mini-player metadata in sync with EPG changes while on this screen.
    LaunchedEffect(currentNowNext?.now?.title, playingChannel?.name) {
        if (playerViewModel.state.value.isActive && playingChannelId != null) {
            playerViewModel.updateNowPlaying(
                channelName = playingChannel?.name.orEmpty(),
                programTitle = currentNowNext?.now?.title.orEmpty(),
            )
        }
    }

    // Default focus to channel list on load (sidebar is hidden by default).
    // Always snap back to Favorites on entry if the user has any.
    LaunchedEffect(enrichedState.value !== EnrichedChannels.Empty) {
        if (!isTouchDevice && enrichedState.value !== EnrichedChannels.Empty) {
            if (state.snapshot.favoriteChannels.isNotEmpty()) {
                selectedCategoryId = "fav"
            }
            focusZone = LiveTvFocusZone.CHANNEL_LIST
            delay(80L)
            focusSelectedChannelSignal += 1
            runCatching { epgFocus.requestFocus() }
        }
    }

    // Cold launch lands in the windowed grid on the startup channel (selected/
    // highlighted, previewing in the mini-player box) instead of jumping straight
    // to fullscreen — lets the user glance at what's on and either select that
    // channel or navigate elsewhere first. Explicit "resume this channel"
    // requests (e.g. mini-player expand, initialStreamUrl != null) still land in
    // fullscreen immediately via isFullScreen's own initial value above — this
    // only removes the *automatic* jump on a plain cold start.

    // If a channel was started from outside the TV screen (e.g. Home On Now row),
    // sync the playing channel ID so the guide follows the mini-player's channel.
    LaunchedEffect(playerViewModel.state.value.channelId) {
        val vmChannelId = playerViewModel.state.value.channelId ?: return@LaunchedEffect
        if (vmChannelId != playingChannelId) {
            playingChannelId = vmChannelId
            focusedChannelId = vmChannelId
        }
    }

    BackHandler(enabled = searchOpen) { searchOpen = false }
    BackHandler(enabled = !searchOpen && isFullScreen) { exitFullScreenPlayback() }
    // ProgramInfoPopup traps Back itself via onPreviewKeyEvent once it actually has
    // Compose focus, but its focus-acquisition LaunchedEffect retries across a few
    // frames after opening — a Back press in that window has nothing to consume it
    // there yet and falls through to the dispatcher-registered BackHandler below,
    // which moves focus in the guide underneath without closing the popup (Joe,
    // 2026-08-17: "stuck popup, navigate is behind it"). Gate this handler so a
    // stray early Back closes the popup instead.
    BackHandler(enabled = !searchOpen && !isFullScreen && programInfoTarget != null) {
        programInfoTarget = null
        focusChannelList(focusedChannelId ?: playingChannelId)
    }
    // Back always steps back exactly one level: fullscreen -> windowed grid (handled by the
    // isFullScreen BackHandler above) -> [categories close first if open] -> exit screen.
    // CATEGORY_LIST used to jump straight to onBack() (exit), skipping the "close sidebar"
    // step entirely — the one gap in an otherwise consistent one-step-back model.
    BackHandler(enabled = !searchOpen && !isFullScreen && programInfoTarget == null) {
        when (focusZone) {
            LiveTvFocusZone.EPG -> focusChannelList(focusedChannelId ?: playingChannelId)
            LiveTvFocusZone.CATEGORY_LIST -> focusChannelList(focusedChannelId ?: playingChannelId)
            LiveTvFocusZone.CHANNEL_LIST -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveColors.Bg)
            .then(
                if (!isTouchDevice) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (searchOpen || isFullScreen) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Search) {
                            searchOpen = true
                            return@onPreviewKeyEvent true
                        }
                        if (event.type == KeyEventType.KeyUp && event.key == pendingRailKeyUp) {
                            pendingRailKeyUp = null
                            return@onPreviewKeyEvent true
                        }
                        if (isNavRailOpen.value) {
                            if (event.type == KeyEventType.KeyDown) pendingRailKeyUp = event.key
                            val railEntries = com.arflix.tv.ui.components.computeNavRailEntries(
                                currentScreen = com.arflix.tv.data.model.NavSectionKind.TV,
                                navSections = navSections,
                                neolinkConfigured = neolinkConfigured,
                            )
                            com.arflix.tv.ui.components.navRailHandleKey(
                                event = event,
                                entries = railEntries,
                                focusedIndex = navRailFocusedIndex,
                                onClose = { isNavRailOpen.value = false },
                                context = context,
                                actions = com.arflix.tv.ui.components.NavRailActions(
                                    onNavigateToHome = onNavigateToHome,
                                    onNavigateToSearch = onNavigateToSearch,
                                    onNavigateToDiscover = onNavigateToDiscover,
                                    onNavigateToCameras = onNavigateToCameras,
                                    onNavigateToSettings = onNavigateToSettings,
                                    onNavigateToWatchlist = onNavigateToWatchlist,
                                    onNavigateToAllApps = onNavigateToAllApps,
                    onNavigateToMovies = onNavigateToMovies,
                    onNavigateToShows = onNavigateToShows,
                                    onNavigateToPlex = {
                                        playerViewModel.pauseForVod()
                                        context.packageManager.getLaunchIntentForPackage("com.plexapp.android")?.let {
                                            context.startActivity(it)
                                        }
                                    },
                                ),
                            )
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // Content area starts below the translucent top bar so it doesn't get
        // overwritten.
        if (isFullScreen) {
            // Full-screen playback only — no grid rendered so the single
            // PlayerView owns ExoPlayer.
        } else if (!state.isConfigured && state.snapshot.channels.isEmpty()) {
            EmptyStatePane(
                message = "No IPTV playlist configured.",
                actionLabel = "Open settings",
                onAction = onNavigateToSettings,
            )
        } else {
            // Content starts right under the pill row — 52 dp puts the first
            // row/search field 4 dp below the pills. The remaining top-bar
            // gradient tail is transparent enough to vanish over our near-
            // black Bg so the two regions read as one surface.
            // Content sits under the top bar (82dp tall with a dark-to-
            // transparent gradient). Starting at 0dp lets the grid/sidebar
            // background bleed up into the transparent tail of the gradient
            // so the two regions read as one surface instead of a hovering
            // chip row. The content itself gets an internal top padding so
            // nothing important renders under the opaque chips.
            if (useTouchRail) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = contentTopPadding),
                ) {
                    MiniPlayerRow(
                        exoPlayer = exoPlayer,
                        channel = playingChannel,
                        clockTickMillis = guideClockMillis,
                        nowNext = currentNowNext,
                        onFavoriteToggle = { viewModel.toggleFavoriteChannel(it) },
                        favoriteSet = favSet,
                        onFullscreenClick = openFullScreenPlayer,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TouchCategoryRail(
                        tree = enrichedState.value.tree,
                        selectedId = selectedCategoryId,
                        onSelect = { id -> selectedCategoryId = id },
                        onOpenSearch = { searchOpen = true },
                        remoteModeActive = remoteTarget != null,
                        onOpenRemoteMode = { showRemoteModeSheet = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EpgGrid(
                        channels = filteredChannels,
                        clockTickMillis = guideClockMillis,
                        nowNext = state.snapshot.nowNext,
                        selectedChannelId = focusedChannelId ?: playingChannelId,
                        focusSelectedChannelSignal = focusSelectedChannelSignal,
                        focusEpgSignal = focusEpgSignal,
                        scrollToNowSignal = epgScrollToNowSignal,
                        focusMode = if (focusZone == LiveTvFocusZone.EPG) {
                            EpgGridFocusMode.Epg
                        } else {
                            EpgGridFocusMode.ChannelList
                        },
                        compact = true,
                        gridFocused = focusZone == LiveTvFocusZone.EPG,
                        onChannelSelect = { channel, _ ->
                            focusZone = LiveTvFocusZone.CHANNEL_LIST
                            selectChannel(channel)
                        },
                        onProgramSelect = { channel, program ->
                            if (program != null) {
                                programInfoTarget = channel to program
                            } else {
                                playProgramInMini(channel, null)
                            }
                        },
                        onChannelFocused = { channel ->
                            focusedChannelId = channel.id
                            rememberedChannelByCategory[selectedCategoryId] = channel.id
                        },
                        onChannelLongPress = { channel -> favoriteMenuChannel = channel },
                        favorites = favSet,
                        onMoveLeftFromChannels = { focusPlaylistSearch() },
                        onEnterEpg = { channel -> focusEpg(channel.id) },
                        onExitEpg = { channel -> focusChannelList(channel?.id ?: focusedChannelId ?: playingChannelId) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                // Animate EpgGrid left padding to match the sidebar width so the
                // channel column stays visible when groups are open.
                val epgStartOffset by animateDpAsState(
                    targetValue = if (guideGroupsVisible) LiveDims.SidebarExpanded else 0.dp,
                    animationSpec = tween(180),
                    label = "epg-start",
                )
                Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = contentTopPadding),
                ) {
                    MiniPlayerRow(
                        exoPlayer = exoPlayer,
                        channel = playingChannel,
                        clockTickMillis = guideClockMillis,
                        nowNext = currentNowNext,
                        onFavoriteToggle = { viewModel.toggleFavoriteChannel(it) },
                        favoriteSet = favSet,
                        onFullscreenClick = openFullScreenPlayer,
                        compact = compactTouchLayout,
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { coords ->
                            miniPlayerHeightPx = coords.size.height
                        },
                    )
                    EpgGrid(
                            channels = filteredChannels,
                            clockTickMillis = guideClockMillis,
                            nowNext = state.snapshot.nowNext,
                            selectedChannelId = focusedChannelId ?: playingChannelId,
                            focusSelectedChannelSignal = focusSelectedChannelSignal,
                            focusEpgSignal = focusEpgSignal,
                            scrollToNowSignal = epgScrollToNowSignal,
                            focusMode = if (focusZone == LiveTvFocusZone.EPG) {
                                EpgGridFocusMode.Epg
                            } else {
                                EpgGridFocusMode.ChannelList
                            },
                            compact = compactTouchLayout,
                            gridFocused = focusZone == LiveTvFocusZone.CHANNEL_LIST || focusZone == LiveTvFocusZone.EPG,
                            onChannelSelect = { channel, _ -> selectChannel(channel) },
                            onProgramSelect = { channel, program ->
                            if (program != null) {
                                programInfoTarget = channel to program
                            } else {
                                playProgramInMini(channel, null)
                            }
                        },
                            onChannelFocused = { channel ->
                                focusedChannelId = channel.id
                                rememberedChannelByCategory[selectedCategoryId] = channel.id
                            },
                            onChannelLongPress = { channel -> favoriteMenuChannel = channel },
                            favorites = favSet,
                            onMoveLeftFromChannels = { openSidebar() },
                            onMoveUpFromTopOfChannels = {},
                            onEnterEpg = { channel -> focusEpg(channel.id) },
                            onExitEpg = { channel -> focusChannelList(channel?.id ?: focusedChannelId ?: playingChannelId) },
                            modifier = Modifier
                                .padding(start = epgStartOffset)
                                .fillMaxSize()
                                .onFocusChanged {
                                    if (it.hasFocus && focusZone == LiveTvFocusZone.CATEGORY_LIST) {
                                        focusZone = LiveTvFocusZone.CHANNEL_LIST
                                    }
                                }
                                .then(if (!isTouchDevice) Modifier.focusRequester(epgFocus) else Modifier),
                        )
                }

                // Sidebar slides in from left as an overlay (TiVimate-style).
                // Direct child of the outer Box so it has BoxScope and avoids
                // ColumnScope.AnimatedVisibility resolution. Top offset measured
                // from MiniPlayerRow so the sidebar starts below the player.
                val density = LocalDensity.current
                val miniPlayerOffsetDp = contentTopPadding + with(density) { miniPlayerHeightPx.toDp() }
                AnimatedVisibility(
                    visible = guideGroupsVisible,
                    enter = fadeIn(tween(180)) + slideInHorizontally(tween(180)) { -it },
                    exit = fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it },
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(top = miniPlayerOffsetDp),
                ) {
                    CategorySidebar(
                        tree = enrichedState.value.tree,
                        selectedId = selectedCategoryId,
                        expanded = sidebarExpanded,
                        favoriteSortMode = favoriteSortMode,
                        groupBlacklistEnabled = state.groupBlacklistEnabled,
                        onFavoriteSortToggle = { viewModel.cycleFavoriteSortMode() },
                        onSelect = { id -> selectedCategoryId = id },
                        onOpenSearch = { searchOpen = true },
                        onHideCategory = { groupName ->
                            selectedCategoryId = "all"
                            viewModel.setGroupState(groupName, GroupState.Hide)
                        },
                        onUnhideCategory = { groupName ->
                            viewModel.setGroupState(groupName, GroupState.Show)
                        },
                        onSetGroupState = { groupName, newState ->
                            if (newState == GroupState.Hide) selectedCategoryId = "all"
                            viewModel.setGroupState(groupName, newState)
                        },
                        onMoveCategoryUp = { groupName ->
                            viewModel.moveGroupUp(groupName)
                        },
                        onMoveCategoryToTop = { groupName ->
                            viewModel.moveGroupToTop(groupName)
                        },
                        onMoveCategoryDown = { groupName ->
                            viewModel.moveGroupDown(groupName)
                        },
                        onFocusEnter = { focusZone = LiveTvFocusZone.CATEGORY_LIST },
                        onMoveRight = {
                            val remembered = rememberedChannelByCategory[selectedCategoryId]
                                ?.takeIf { id -> filteredChannels.any { it.id == id } }
                            val target = remembered
                                ?: focusedChannelId?.takeIf { id -> filteredChannels.any { it.id == id } }
                                ?: playingChannelId?.takeIf { id -> filteredChannels.any { it.id == id } }
                                ?: filteredChannels.firstOrNull()?.id
                            focusChannelList(target)
                        },
                        onMoveUpFromSearch = { isNavRailOpen.value = true },
                        onOpenNavRail = { isNavRailOpen.value = true },
                        focusSearchSignal = focusSearchCategorySignal,
                        focusFirstCategorySignal = focusCategorySignal,
                        focusActiveCategorySignal = focusActiveCategorySignal,
                        modifier = Modifier
                            .fillMaxHeight()
                            .focusRequester(sidebarFocus),
                    )
                }
            }
            }
        }

        // Full-screen playback — mini-player grows into fullscreen (scale+alpha animation).
        // Back collapses it; up/down zaps channels.
        val fsProgress by animateFloatAsState(
            targetValue = if (isFullScreen) 1f else 0f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "tv-fullscreen-progress",
        )
        val showFsBox = fsProgress > 0f && playingChannel != null
        if (showFsBox) {
            val scale = 0.35f + 0.65f * fsProgress
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(
                            pivotFractionX = 0.22f,
                            pivotFractionY = 0.18f,
                        )
                        scaleX = scale
                        scaleY = scale
                        alpha = fsProgress
                    }
                    .background(Color.Black)
                    .focusRequester(fsFocus)
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        if (!isFullScreen) return@onPreviewKeyEvent false
                        if (ev.key == Key.DirectionUp || ev.key == Key.ChannelUp) {
                            // Short press: existing quick-zap-to-next-channel-up. Long press:
                            // open search instead of the unreliable Key.Search shortcut.
                            return@onPreviewKeyEvent when (ev.type) {
                                KeyEventType.KeyDown -> {
                                    if (!upPressed) {
                                        upPressed = true
                                        upLongPressConsumed = false
                                        upLongPressJob?.cancel()
                                        upLongPressJob = fsScope.launch {
                                            delay(520L)
                                            if (upPressed) {
                                                upLongPressConsumed = true
                                                searchOpen = true
                                            }
                                        }
                                    }
                                    true
                                }
                                KeyEventType.KeyUp -> {
                                    upLongPressJob?.cancel()
                                    upPressed = false
                                    if (!upLongPressConsumed) { zap(+1); hudPokeSignal++ }
                                    upLongPressConsumed = false
                                    true
                                }
                                else -> false
                            }
                        }
                        if (ev.key == Key.DirectionDown || ev.key == Key.ChannelDown) {
                            // Short press: existing quick-zap-to-next-channel. Long press
                            // (520ms, matching the pin-toggle precedent in SearchOverlay's
                            // RemoteStreamRow): jump straight to the Recent category instead.
                            return@onPreviewKeyEvent when (ev.type) {
                                KeyEventType.KeyDown -> {
                                    if (!downPressed) {
                                        downPressed = true
                                        downLongPressConsumed = false
                                        downLongPressJob?.cancel()
                                        downLongPressJob = fsScope.launch {
                                            delay(520L)
                                            if (downPressed) {
                                                downLongPressConsumed = true
                                                selectedCategoryId = "recent"
                                                // exitFullScreenPlayback() alone leaves the
                                                // category sidebar closed (focusChannelList()
                                                // hides it) — no on-screen sign anything
                                                // changed (Joe, 2026-08-15: "not doing
                                                // anything but going to guide"). Open it so
                                                // Recent is visibly the highlighted category.
                                                exitFullScreenPlayback()
                                                openSidebar()
                                            }
                                        }
                                    }
                                    true
                                }
                                KeyEventType.KeyUp -> {
                                    downLongPressJob?.cancel()
                                    downPressed = false
                                    if (!downLongPressConsumed) { zap(-1); hudPokeSignal++ }
                                    downLongPressConsumed = false
                                    true
                                }
                                else -> false
                            }
                        }
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Back, Key.Escape -> { exitFullScreenPlayback(); true }
                            Key.DirectionCenter, Key.Enter -> { hudPokeSignal++; true }
                            Key.DirectionRight -> { returnToPreviousChannel(); true }
                            // Matches TiviMate's cascade: Left/Back from fullscreen both drop to
                            // the windowed grid first (not straight to the sidebar) — a second
                            // Left from the channel list opens the sidebar (onMoveLeftFromChannels
                            // below), and a third Left from within the sidebar opens the nav rail
                            // (CategorySidebar's own Key.DirectionLeft -> onOpenNavRail()).
                            Key.DirectionLeft -> { exitFullScreenPlayback(); true }
                            Key.Search -> { searchOpen = true; true }
                            else -> false
                        }
                    }
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { hudPokeSignal++ },
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        androidx.media3.ui.PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            setKeepContentOnPlayerReset(true)
                        }
                    },
                    update = { it.player = exoPlayer },
                    modifier = Modifier.fillMaxSize(),
                )

                if (isFullScreen) {
                    FullscreenHud(
                        channel = playingChannel,
                        nowNext = currentNowNext,
                        pokeSignal = hudPokeSignal,
                        onBackClick = { exitFullScreenPlayback() },
                        modifier = Modifier,
                    )
                }

                // Independent of the HUD's auto-hide timer — shows immediately
                // on a channel zap so the press has visible confirmation instead
                // of freezing on the previous frame with no feedback at all.
                if (isBuffering) {
                    CircularProgressIndicator(
                        color = LiveColors.Accent,
                        modifier = Modifier.align(Alignment.Center).size(48.dp),
                    )
                }
            }
        }

        LaunchedEffect(isFullScreen) {
            if (isFullScreen) {
                runCatching { fsFocus.requestFocus() }
            }
        }

        // Top chrome only shows when NOT in full-screen playback.
        // Fade with the fullscreen progress so it doesn't pop in/out — looks
        // natural next to the grow animation below.
        if (showTopBar && fsProgress < 1f) {
            Box(modifier = Modifier.graphicsLayer { alpha = 1f - fsProgress }) {
                com.arflix.tv.ui.components.MinimalTopChrome(profile = currentProfile)
            }
        }

        if (showRemoteModeSheet) {
            val remoteLanPeers by viewModel.remoteLanPeers.collectAsStateWithLifecycle()
            com.arflix.tv.ui.components.RemoteModeSheet(
                peers = remoteLanPeers,
                target = remoteTarget,
                onSelectTarget = { viewModel.setRemoteTarget(it) },
                onSendDpad = { key -> viewModel.sendRemoteDpad(key) },
                onSendText = { text -> viewModel.sendRemoteText(text) },
                onDismiss = { showRemoteModeSheet = false },
            )
        }

        if (showTopBar) {
            // zIndex forces this above the guide/category content regardless of
            // composition order — see HomeScreen.kt/SettingsScreen.kt's identical fix.
            Box(modifier = Modifier.zIndex(10f)) {
            com.arflix.tv.ui.components.NavRail(
                isOpen = isNavRailOpen.value,
                onClose = {
                    isNavRailOpen.value = false
                    runCatching { sidebarFocus.requestFocus() }
                },
                currentScreen = com.arflix.tv.data.model.NavSectionKind.TV,
                navSections = navSections,
                neolinkConfigured = neolinkConfigured,
                currentProfile = currentProfile,
                actions = com.arflix.tv.ui.components.NavRailActions(
                    onNavigateToHome = onNavigateToHome,
                    onNavigateToSearch = onNavigateToSearch,
                    onNavigateToDiscover = onNavigateToDiscover,
                    onNavigateToCameras = onNavigateToCameras,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToWatchlist = onNavigateToWatchlist,
                    onNavigateToAllApps = onNavigateToAllApps,
                    onNavigateToMovies = onNavigateToMovies,
                    onNavigateToShows = onNavigateToShows,
                    onNavigateToPlex = {
                        playerViewModel.pauseForVod()
                        context.packageManager.getLaunchIntentForPackage("com.plexapp.android")?.let {
                            context.startActivity(it)
                        }
                    },
                ),
                focusedIndex = navRailFocusedIndex.value,
            )
            }
        }

        fun tuneFromSearch(channel: EnrichedChannel) {
            if (remoteTuneOrHandled(channel)) { searchOpen = false; return }
            previousChannelId = playingChannelId
            // A raw provider-search pick not already resolvable (i.e. not pinned) isn't
            // in the tree yet, so bestCategoryIdForChannel would look it up against a
            // tree that doesn't know it exists — queue it into the enrichment merge
            // instead of touching category selection off a stale tree.
            if (channel.id.startsWith("raw:") && enrichedState.value.index.byId[channel.id] == null) {
                ephemeralSearchPick = channel.source
            } else {
                selectedCategoryId = bestCategoryIdForChannel(channel, enrichedState.value.tree)
            }
            playingChannelId = channel.id
            focusedChannelId = channel.id
            searchOpen = false
            focusChannelList(channel.id)
        }

        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            SearchOverlay(
                initialQuery = remoteSearchQuery ?: initialSearchQuery.orEmpty(),
                channels = remember(enrichedState.value.all, state.snapshot.removedGroups) {
                    val removed = state.snapshot.removedGroups.toSet()
                    enrichedState.value.all.filterNot { it.source.group in removed }
                },
                nowNext = state.snapshot.nowNext,
                offLineupGroups = remember(state.snapshot.hiddenGroups, state.snapshot.newGroups) {
                    (state.snapshot.hiddenGroups + state.snapshot.newGroups).toSet()
                },
                remoteSearchAvailable = dispatcharrCatalogAvailable,
                onRemoteSearch = { q -> viewModel.dispatcharrCatalogRepository.search(q) },
                pinnedStreamIds = remember(pinnedProviderChannels) { pinnedProviderChannels.map { it.id }.toSet() },
                onTogglePin = { stream ->
                    if (pinnedProviderChannels.any { it.id == stream.id }) {
                        viewModel.unpinProviderStream(stream.id)
                    } else {
                        viewModel.pinProviderStream(stream)
                    }
                },
                onMediaSearch = { q -> viewModel.searchMedia(q) },
                onPickMedia = { media ->
                    searchOpen = false
                    onNavigateToDetails(media.mediaType, media.id)
                },
                onDismiss = { searchOpen = false },
                onPick = { channel -> tuneFromSearch(channel) },
                onShowInfo = { channel, program ->
                    // No specific program matched (a plain channel/genre-name hit) — fall back to
                    // whatever's live on that channel right now; if even that's unknown, there's
                    // nothing to show info about, so just tune like a normal pick.
                    val resolvedProgram = program ?: state.snapshot.nowNext[channel.id]?.now
                    if (resolvedProgram != null) {
                        searchOpen = false
                        programInfoTarget = channel to resolvedProgram
                    } else {
                        tuneFromSearch(channel)
                    }
                },
            )
        }

        val menuChannel = favoriteMenuChannel
        com.arflix.tv.ui.components.ChannelContextMenu(
            isVisible = menuChannel != null,
            channelName = menuChannel?.name.orEmpty(),
            isFavorite = menuChannel != null && menuChannel.id in favSet,
            onToggleFavorite = { menuChannel?.let { viewModel.toggleFavoriteChannel(it.id) } },
            onDismiss = {
                // The popup steals real focus to receive D-pad input; nothing
                // hands it back when it closes, which left the guide with no
                // focused node at all (stuck — Back/arrows did nothing).
                // Explicitly reclaim the channel list, same helper used when
                // exiting the EPG or fullscreen playback.
                val id = menuChannel?.id
                favoriteMenuChannel = null
                focusChannelList(id ?: focusedChannelId ?: playingChannelId)
            },
        )

        programInfoTarget?.let { (infoChannel, infoProgram) ->
            val reminders by viewModel.programReminders.collectAsStateWithLifecycle()
            val reminderKey = remember(infoChannel.id, infoProgram) { reminderKey(infoChannel.id, infoProgram) }
            ProgramInfoPopup(
                channel = infoChannel,
                program = infoProgram,
                nowMillis = guideClockMillis,
                isReminderSet = reminders.any { it.key == reminderKey },
                notificationsEnabled = remember(infoChannel.id, infoProgram) { viewModel.notificationsEnabled() },
                onToggleReminder = {
                    if (reminders.any { it.key == reminderKey }) {
                        viewModel.cancelProgramReminder(infoChannel.id, infoProgram)
                    } else {
                        viewModel.setProgramReminder(infoChannel.id, infoChannel.name, infoProgram)
                    }
                },
                onWatch = {
                    playProgramInMini(infoChannel, infoProgram)
                    programInfoTarget = null
                },
                onDismiss = {
                    // Same fix as ChannelContextMenu's onDismiss above: this popup
                    // steals real focus, so closing it without reclaiming a target
                    // leaves the guide with no focused node at all.
                    val id = infoChannel.id
                    programInfoTarget = null
                    focusChannelList(id)
                },
            )
        }
    }
}

/** State bundle of the enriched channel list + category tree. */
/** A pinned full-provider search result, as an ordinary playable channel in the guide. */
fun RawProviderStream.toIptvChannel(): IptvChannel = IptvChannel(
    id = id,
    name = name,
    streamUrl = streamUrl,
    group = group,
    logo = logo,
    epgId = tvgId,
)

data class EnrichedChannels(
    val all: List<EnrichedChannel>,
    val tree: LiveCategoryTree,
    val index: LiveCategoryIndex = LiveCategoryIndex.Empty,
) {
    companion object {
        val Empty = EnrichedChannels(
            all = emptyList(),
            tree = LiveCategoryTree(
                top = emptyList(),
                global = LiveSection("global", "GLOBAL", emptyList()),
                countries = LiveSection("countries", "COUNTRIES", emptyList()),
                adult = LiveSection("adult", "ADULT", emptyList()),
            ),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
