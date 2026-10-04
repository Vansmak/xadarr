@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.key
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.unit.sp
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
import com.arflix.tv.data.model.IptvNowNext
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
import androidx.tv.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arflix.tv.ui.skin.LocalFocusBorderColorOverride
import androidx.compose.ui.graphics.Brush

private enum class LiveTvFocusZone {
    CATEGORY_LIST,
    CHANNEL_LIST,
    EPG,
}

/**
 * Last channel actually played, kept in process memory (freshest source); the persisted
 * tvSession.lastChannelId covers restarts. Now Playing always resumes it in its own group.
 */
private fun digitForKey(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

private object LiveTvResumeMemory {
    var channelId: String? = null
    var categoryId: String? = null
    var epochDay: Long = -1L

    fun current(): Pair<String, String?>? {
        val id = channelId ?: return null
        if (epochDay != java.time.LocalDate.now().toEpochDay()) return null
        return id to categoryId
    }

    fun remember(id: String, category: String?) {
        channelId = id
        categoryId = category
        epochDay = java.time.LocalDate.now().toEpochDay()
    }
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
        // Any category: the resumed channel's own group gets reselected by the caller. Checking
        // only filteredChannels (the default "fav" category) meant a non-favorite last channel
        // was never found and it fell through to the first favorite every time.
        sessionLastChannelId
            .takeIf { id -> id.isNotBlank() && (allChannelIds.contains(id) || filteredChannels.any { it.id == id }) }
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
    // Selecting a specific episode cell in a synthetic Shows-channel row plays it directly, the
    // same path DetailsScreen's own episode click uses -- see playShowEpisode below. Signature
    // matches DetailsScreen.kt's onNavigateToPlayer exactly (mediaType, tmdbId, season, episode,
    // imdbId, streamUrl, preferredAddonId, preferredSourceName, startPositionMs, isDeliberateSourcePick).
    onNavigateToPlayer: (MediaType, Int, Int?, Int?, String?, String?, String?, String?, Long?, Boolean) -> Unit =
        { _, _, _, _, _, _, _, _, _, _ -> },
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
    // Touch used to default to "all" here, which is defined as "everything except adult" and was
    // never built to respect hiddenGroups -- so a device landing on it looked like hidden-group
    // curation had no effect, even though the category pill row itself correctly excludes hidden
    // groups. Matching the D-pad default (Joe, 2026-09-27: "ok but showing ALL groups", after
    // confirming server-side hiddenGroups was correctly synced) is the smaller, safer fix over
    // teaching "all" itself to filter, since "all" is relied on elsewhere as a true unfiltered
    // catch-all.
    var selectedCategoryId by rememberSaveable { mutableStateOf("fav") }
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
    // Synthetic "Shows" guide channel — one row per Sonarr-monitored show, spliced in the exact
    // same way pinnedProviderChannels are, below. See project_nostalgex_style_media_channel memory.
    val showsGuideSchedule by viewModel.showsGuideSchedule.collectAsStateWithLifecycle()
    val movieGuide by viewModel.movieGuide.collectAsStateWithLifecycle()
    val musicPlaylists by viewModel.musicPlaylists.collectAsStateWithLifecycle()
    val musicConfigured by viewModel.musicConfigured.collectAsStateWithLifecycle()
    val musicTracks by viewModel.musicTracks.collectAsStateWithLifecycle()
    val musicLineups by viewModel.musicLineups.collectAsStateWithLifecycle()
    MusicGuideFeeds(viewModel, musicPlaylists, guideClockMillis)
    val dispatcharrCatalogAvailable by viewModel.dispatcharrCatalogAvailable.collectAsStateWithLifecycle()
    val remoteTarget by viewModel.remoteTarget.collectAsStateWithLifecycle()
    // Collected at screen level, not just inside the panel: the Remote pill's highlight depends on
    // it, and a control device that isn't an Xadarr instance (a TV, a speaker) never sets
    // remoteTarget — so binding the highlight to the target alone left the pill dark after a
    // successful switch, which is indistinguishable from the toggle not working.
    val remoteControl by viewModel.controlDevice.collectAsStateWithLifecycle()
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
        "shows=${showsGuideSchedule.size}:" +
        "movies=${movieGuide.movies.size}/${movieGuide.premiering.size}:" +
        "music=${musicPlaylists.size}:" +
        "ephemeral=${ephemeralSearchPick?.id}"
    LaunchedEffect(channelsIdentitySignature) {
        // Returning to the guide: the new TvViewModel hasn't re-read the playlist yet, but the
        // session cache already holds the fully built guide -- keep showing it instead of
        // blanking to the loading screen. Real playlist changes clear the cache.
        if (state.snapshot.channels.isEmpty() && viewModel.cachedEnrichedChannels is EnrichedChannels) {
            enrichedState.value = viewModel.cachedEnrichedChannels as EnrichedChannels
            return@LaunchedEffect
        }
        val snapshot = state.snapshot.channels +
            pinnedProviderChannels.map { it.toIptvChannel(PinnedChannelsGroup) } +
            showsGuideSchedule.map { it.toIptvChannel() } +
            movieGuide.toIptvChannels() +
            musicPlaylists.toMusicChannels() +
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
        // Compare-and-set: the full enrichment pass above can land while this was computing from
        // the quick favorites-only first pass. Writing `current.copy(...)` unconditionally then
        // put that partial channel set back over the full one, and nothing re-ran afterwards --
        // the sidebar stuck with no Shows/Movies/Hidden/OTA (Joe, 2026-09-30, Shield). More
        // likely now that Shows and Movies arriving separately each re-run enrichment.
        if (enrichedState.value === current) {
            enrichedState.value = current.copy(tree = tree)
        }
    }
    LaunchedEffect(hiddenGroupSet, selectedCategoryId, enrichedState.value.tree) {
        // Was checking the string "favorites", which is not a real id anywhere in this file (the
        // actual id is "fav" -- see LiveCategory("fav", "Favorites", ...) in LiveCategory.kt). On
        // the very first composition, before any channels have loaded, enrichedState.value.tree is
        // still its empty default, so tree.byId("fav") returns null and this immediately reset the
        // new touch default of "fav" back to "all" before real data ever arrived -- permanently for
        // that session, since nothing here restores it once the tree populates. "all" was never
        // affected because it was (accidentally) the one default this typo didn't break. Joe,
        // 2026-09-27, after a full data-clear + reinstall still showed unfiltered "all": "it's doing
        // same".
        val builtIn = selectedCategoryId == "all" || selectedCategoryId == "fav" || selectedCategoryId == "recent"
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
    // Real nowNext plus synthetic Shows-channel entries, merged for every render site that
    // reads the guide's now/next data (both EpgGrid layouts, SearchOverlay) -- mirrors how
    // pinnedProviderChannels/ephemeralSearchPick are merged into the channel list above, just
    // for the nowNext map instead, since IptvSnapshot keeps the two decoupled (channels vs.
    // nowNext keyed separately by id).
    val effectiveSnapshotNowNext = remember(state.snapshot.nowNext, showsGuideSchedule, movieGuide, musicPlaylists, musicTracks, musicLineups, guideClockMillis) {
        if (showsGuideSchedule.isEmpty() && movieGuide.movies.isEmpty() && movieGuide.premiering.isEmpty() && musicPlaylists.isEmpty()) {
            state.snapshot.nowNext
        } else {
            state.snapshot.nowNext + showsGuideSchedule.associate {
                "$ShowsChannelIdPrefix${it.seriesId}" to it.toIptvNowNext(guideClockMillis)
            } + movieGuide.toIptvNowNext(guideClockMillis) + musicPlaylists.toMusicNowNext(guideClockMillis, musicTracks, musicLineups)
        }
    }
    val currentNowNext = remember(playingChannelId, playingCatchupProgram, effectiveSnapshotNowNext) {
        val live = playingChannelId?.let { effectiveSnapshotNowNext[it] }
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
        val entersBlock = playingChannelId == null && filteredChannels.isNotEmpty() && (initialChannelId != null || startupStateReady)
        if (entersBlock) {
            // Always resume the last channel actually watched (= top of Recent), across restarts
            // and days too (Joe, 2026-09-30: "if recents persists then yes" -- the top of Recent is
            // the persisted tvSession.lastChannelId). In-process memory first, it's freshest.
            val resume = LiveTvResumeMemory.current()
                ?: state.tvSession.lastChannelId.takeIf { it.isNotBlank() }
                    ?.let { it to state.tvSession.lastGroupName.takeIf { g -> g.isNotBlank() } }
            val result = chooseStartupChannelId(
                filteredChannels = filteredChannels,
                explicitInitialChannelId = initialChannelId,
                sessionLastChannelId = resume?.first.orEmpty(),
                hasOpenedBefore = resume != null,
                favoriteChannelIds = state.snapshot.favoriteChannels,
                isFullyEnriched = enrichedState.value.all.size >= state.snapshot.channels.size,
                allChannelIds = allChannelIds,
            )
            playingChannelId = result
            // An explicit request (deep link / Remote Mode tune) just got honored outside
            // whatever category was selected (e.g. this device's default "fav" on first
            // launch) — switch to "all" so the guide actually shows the channel that's now
            // playing instead of a category list that doesn't contain it.
            if (playingChannelId != null && initialChannelId == playingChannelId &&
                filteredChannels.none { it.id == playingChannelId }
            ) {
                selectedCategoryId = "all"
            } else if (playingChannelId != null && playingChannelId == resume?.first &&
                filteredChannels.none { it.id == playingChannelId }
            ) {
                // Resumed a channel outside the default category: reopen the group it was in.
                selectedCategoryId = resume?.second ?: "all"
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
    // Selecting (or long-pressing) a Shows/Movies channel: Episodes & Info / Movie Info, Change Rule.
    var libraryMenuChannel by remember { mutableStateOf<EnrichedChannel?>(null) }
    // The zone picker acts on select's key-down, but guide rows act on key-up: closing the
    // picker hands focus back to the row, and the release of that same press "selected" the row
    // again and reopened the picker. Re-opening within this window of a close is ignored.
    var musicMenuClosedAt by remember { mutableLongStateOf(0L) }
    // A song/artist/album/playlist picked in search: the same room picker plays it.
    var musicSearchPick by remember { mutableStateOf<com.arflix.tv.music.MaMediaItem?>(null) }
    // Same for the Shows/Movies menus: Close on key-down, then the release re-selected the row and
    // the movie menu came straight back -- it looked stuck on screen, even over fullscreen video
    // (Joe, 2026-10-04, "Heart of the Beast" over RedZone).
    @Suppress("UNUSED_PARAMETER")
    fun musicMenuJustClosed(channelId: String) =
        android.os.SystemClock.uptimeMillis() - musicMenuClosedAt < 700
    // Selecting an episode/movie cell on a library row: Play / Mark Watched / Search.
    var libraryCellTarget by remember { mutableStateOf<Pair<EnrichedChannel, IptvProgram>?>(null) }
    // Rule picker opened from a show's channel menu.
    var rulePickerShow by remember { mutableStateOf<com.arflix.tv.data.repository.ShowGuideEntry?>(null) }
    // Backing out of Details (opened from a library menu) reopens that channel's menu -- going
    // back means nothing was chosen (Joe, 2026-09-30). Saveable: this screen's composition is
    // torn down while Details is on top. Armed on ON_RESUME so it can't fire before navigating.
    var reopenMenuAfterDetails by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMenuReopen by remember { mutableStateOf<String?>(null) }
    var pendingMenuReopenAt by remember { mutableLongStateOf(0L) }
    // Short confirmation drawn inside the guide. Android Toasts don't render on Joe's
    // Shield/onn boxes (see project_remote_mode memory), so they'd be invisible there.
    var guideMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(guideMessage) {
        if (guideMessage != null) {
            delay(2500L)
            guideMessage = null
        }
    }
    var programInfoTarget by remember { mutableStateOf<Pair<EnrichedChannel, IptvProgram>?>(null) }
    var focusSelectedChannelSignal by remember { mutableIntStateOf(0) }
    // Entering Now Playing with a channel already playing (back from another screen -- the player
    // keeps running) used to leave the guide on its default Favorites list, highlighting nothing
    // you were watching (Joe, 2026-09-30). Show the playing channel's group -- or Recent, where it
    // is at the top -- and highlight it. Once per entry.
    var alignedOnEntry by remember { mutableStateOf(false) }
    // Set on the first key press. The startup focus jump below waits up to ~1s for the guide to
    // settle; if the user is already navigating by then (Joe, 2026-09-30: opened a show's menu
    // within that second), jumping to the sidebar stole focus from the open menu.
    var userPressedKey by remember { mutableStateOf(false) }
    // "Back returns to your search" (Joe, 2026-10-01): search is where you see all the
    // options; picking one closed it and the list was gone, so a wrong pick meant starting
    // over. The query of the last pick is kept until the next Back reopens it, or until the
    // user picks something else in the guide.
    var lastSearchQuery by remember { mutableStateOf("") }
    var searchReturnQuery by remember { mutableStateOf<String?>(null) }
    var reopenSearchQuery by remember { mutableStateOf<String?>(null) }
    // Channel number being typed on a remote's number pad ("" when idle).
    var channelDigits by remember { mutableStateOf("") }
    LaunchedEffect(playingChannelId, enrichedState.value.index, filteredChannels) {
        if (alignedOnEntry) return@LaunchedEffect
        val id = playingChannelId ?: return@LaunchedEffect
        if (isLibraryChannelId(id)) {
            // The remembered "playing" channel is a Shows/Movies row the user had highlighted
            // (no stream -- the preview sat black on its art while the guide showed Favorites,
            // Joe 2026-10-01). Go back to the last real channel instead.
            val real = LiveTvResumeMemory.current()?.first
                ?: state.tvSession.lastChannelId.takeIf { it.isNotBlank() && !isLibraryChannelId(it) }
            if (real != null && enrichedState.value.index.byId[real] != null) {
                playingChannelId = real // re-runs this effect for the real channel
            } else {
                alignedOnEntry = true
            }
            return@LaunchedEffect
        }
        if (enrichedState.value.index.byId[id] == null) return@LaunchedEffect // not loaded yet
        if (filteredChannels.none { it.id == id }) {
            val remembered = LiveTvResumeMemory.categoryId
                ?.takeIf { LiveTvResumeMemory.channelId == id && it != selectedCategoryId }
            when {
                remembered != null -> selectedCategoryId = remembered
                selectedCategoryId != "recent" -> selectedCategoryId = "recent"
                else -> alignedOnEntry = true // nowhere better to show it
            }
            return@LaunchedEffect // re-runs once filteredChannels reflects the new category
        }
        alignedOnEntry = true
        focusedChannelId = id
        focusSelectedChannelSignal += 1
    }
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

    fun showEntryFor(channelId: String?) =
        channelId?.removePrefix(ShowsChannelIdPrefix)?.toIntOrNull()
            ?.takeIf { channelId.startsWith(ShowsChannelIdPrefix) }
            ?.let { id -> showsGuideSchedule.find { it.seriesId == id } }
    fun libraryMovieFor(channelId: String?) =
        channelId?.takeIf { it.startsWith(MovieChannelIdPrefix) }?.removePrefix(MovieChannelIdPrefix)?.toIntOrNull()
            ?.let { id -> movieGuide.movies.find { it.tmdbId == id } }
    fun premiereFor(channelId: String?) =
        channelId?.takeIf { it.startsWith(PremiereChannelIdPrefix) }?.removePrefix(PremiereChannelIdPrefix)?.toIntOrNull()
            ?.let { id -> movieGuide.premiering.find { it.tmdbId == id } }

    // Backdrop for the preview box when a library row is highlighted.
    fun libraryArtFor(channel: EnrichedChannel?): String? =
        showEntryFor(channel?.id)?.fanart ?: libraryMovieFor(channel?.id)?.fanart ?: premiereFor(channel?.id)?.fanart
            ?: musicArtFor(musicPlaylists.musicItemFor(channel?.id), musicTracks, musicLineups, guideClockMillis)

    // Highlighting a Shows/Movies row swaps the top preview + info to that title (Joe,
    // 2026-09-30) -- no stream behind it, so this just points the hero at it. The live channel
    // it replaces is remembered as "previous" only if it was a real channel.
    fun previewLibraryChannel(channel: EnrichedChannel) {
        if (!isLibraryChannelGroup(channel.source.group) || playingChannelId == channel.id) return
        if (!isLibraryChannelId(playingChannelId)) previousChannelId = playingChannelId
        playingChannelId = channel.id
        playingCatchupProgram = null
    }

    fun playLibraryEpisode(entry: com.arflix.tv.data.repository.ShowGuideEntry, season: Int, episode: Int) {
        val tvdbId = entry.tvdbId ?: return
        fsScope.launch {
            viewModel.resolveShowTmdbRef(tvdbId)?.let { (mediaType, tmdbId) ->
                onNavigateToPlayer(mediaType, tmdbId, season, episode, null, null, null, null, null, false)
            }
        }
    }

    fun openShowDetails(entry: com.arflix.tv.data.repository.ShowGuideEntry) {
        val tvdbId = entry.tvdbId ?: return
        reopenMenuAfterDetails = "$ShowsChannelIdPrefix${entry.seriesId}"
        fsScope.launch {
            viewModel.resolveShowTmdbRef(tvdbId)?.let { (type, tmdbId) -> onNavigateToDetails(type, tmdbId) }
        }
    }

    fun selectChannel(channel: EnrichedChannel) {
        if (remoteTuneOrHandled(channel)) return
        if (channel.id != playingChannelId) searchReturnQuery = null
        // Two-step, back to the pre-TiviMate-redesign behavior at Joe's request: selecting a
        // channel that isn't already the mini-playing one just loads it into the mini preview
        // and keeps the guide open, so surfing channel-to-channel doesn't force fullscreen each
        // time. Selecting the SAME channel again (already mini-playing) commits to fullscreen —
        // that's the explicit "I want to actually watch this" signal.
        focusedChannelId = channel.id
        rememberedChannelByCategory[selectedCategoryId] = channel.id
        reopenMenuAfterDetails = null
        // Shows/Movies channels have no stream. Highlighting one already shows it in the hero
        // (previewLibraryChannel); selecting it opens its menu (Episodes & Info / Change Rule).
        // Episode/movie actions live on the cells -- see libraryCellTarget.
        if (isLibraryChannelGroup(channel.source.group)) {
            previewLibraryChannel(channel)
            if (!musicMenuJustClosed(channel.id)) libraryMenuChannel = channel
            return
        }
        if (channel.id == playingChannelId) {
            isFullScreen = true
            hudPokeSignal++
        } else {
            previousChannelId = playingChannelId
            playingChannelId = channel.id
            playingCatchupProgram = null
        }
    }

    fun playProgramInMini(channel: EnrichedChannel, program: IptvProgram?) {
        if (remoteTuneOrHandled(channel)) return
        focusedChannelId = channel.id
        rememberedChannelByCategory[selectedCategoryId] = channel.id
        playingChannelId = channel.id
        playingCatchupProgram = program
        focusChannelList(channel.id)
    }

    // Remote Mode, receiving side. Restored after logcat proved the "force a fresh screen"
    // theory in MainActivity wrong: debugTuneInfo showed enters=false with playingId already
    // set to whatever channel was already playing — popUpTo(Screen.Home.route){inclusive=true}
    // does NOT actually clear this composable's rememberSaveable state the way that fix
    // assumed, so chooseStartupChannelId's whole block was being skipped every time regardless
    // of category/enrichment. Back to direct assignment here (mirrors selectChannel's own
    // body), which doesn't depend on any recomposition happening at all — combined with
    // currentStreamUrl's raw-snapshot fallback (still in place, fixes the category-scoped
    // playingChannel lookup) this should cover the case fully now.
    var remoteSearchQuery by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.incomingRemoteCommands.collect { command ->
            when (command) {
                is com.arflix.tv.data.repository.RemoteCommand.TuneChannel -> {
                    val id = command.localChannelId
                    focusedChannelId = id
                    rememberedChannelByCategory[selectedCategoryId] = id
                    if (id != playingChannelId) previousChannelId = playingChannelId
                    playingChannelId = id
                    playingCatchupProgram = null
                    isFullScreen = true
                    hudPokeSignal++
                }
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
                    reopenMenuAfterDetails?.let {
                        pendingMenuReopen = it
                        pendingMenuReopenAt = android.os.SystemClock.uptimeMillis()
                        reopenMenuAfterDetails = null
                    }
                    guideClockMillis = System.currentTimeMillis()
                    epgScrollToNowSignal++
                    // Coming back from the player: the Shows channel's NOW slot has usually just
                    // moved on (Episeerr drops its cache when an episode crosses the threshold).
                    viewModel.refreshShowsGuide()
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
        // Library rows (Shows/Movies) have no stream: highlighting one points the hero at it,
        // which must not hand ExoPlayer an empty URL ("Malformed URL" playback error).
        val stream = currentStreamUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect

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
            LiveTvResumeMemory.remember(id, selectedCategoryId)
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
            // Favorites only when there's nothing to resume -- forcing "fav" here fought the
            // resume-last-channel logic (alignedOnEntry then had to move it back).
            if (playingChannelId == null && state.snapshot.favoriteChannels.isNotEmpty()) {
                selectedCategoryId = "fav"
            }
            // Let the guide settle on the playing channel's group, then open with the group
            // list showing and that group highlighted (Joe, 2026-09-30: start like "screen 2").
            repeat(20) {
                if (alignedOnEntry || userPressedKey) return@repeat
                delay(50L)
            }
            delay(80L)
            if (!userPressedKey) {
                focusSelectedChannelSignal += 1
                openSidebar()
            }
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
    // Declared after the guide's handler so it wins while a search pick is "returnable".
    BackHandler(enabled = !searchOpen && !isFullScreen && programInfoTarget == null && searchReturnQuery != null) {
        reopenSearchQuery = searchReturnQuery
        searchReturnQuery = null
        searchOpen = true
    }

    // Focus guard: if nothing in the guide holds focus (a focused cell got recomposed away when
    // the music rows refreshed), D-pad input went nowhere until Back (Joe, 2026-10-03: "sticky
    // ... unresponsive unless I hit back"). Put it back where the guide thinks it is.
    var guideHasFocus by remember { mutableStateOf(true) }
    val guideView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(guideHasFocus, isFullScreen, searchOpen) {
        if (guideHasFocus || isFullScreen || searchOpen || isTouchDevice) return@LaunchedEffect
        delay(500)
        // A focusable Popup/Dialog (sidebar group menu, remote pairing) owns another window.
        if (!guideView.hasWindowFocus()) return@LaunchedEffect
        val id = focusedChannelId ?: playingChannelId
        if (focusZone == LiveTvFocusZone.EPG && id != null) focusEpg(id)
        else if (focusZone == LiveTvFocusZone.CATEGORY_LIST) runCatching { sidebarFocus.requestFocus() }
        else focusChannelList(id)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveColors.Bg)
            .onFocusChanged { guideHasFocus = it.hasFocus }
            .then(
                if (!isTouchDevice) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) userPressedKey = true
                        // Number buttons (remotes/phone remote apps that have them): TiVo-style
                        // direct tune -- digits collect in channelDigits, tuned after a pause.
                        if (!searchOpen && event.type == KeyEventType.KeyDown) {
                            val digit = digitForKey(event.key)
                            if (digit != null) {
                                if (channelDigits.length < 5) channelDigits += digit
                                return@onPreviewKeyEvent true
                            }
                        }
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
                        artUrl = libraryArtFor(playingChannel),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TouchCategoryRail(
                        tree = enrichedState.value.tree,
                        selectedId = selectedCategoryId,
                        onSelect = { id -> selectedCategoryId = id },
                        onOpenSearch = { searchOpen = true },
                        remoteModeActive = remoteControl != null || remoteTarget != null,
                        onOpenRemoteMode = { showRemoteModeSheet = true },
                        onToggleRemoteMode = { viewModel.toggleRemoteLocal() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EpgGrid(
                        zoom = musicGridZoom(filteredChannels),
                        focusSuspended = searchOpen,
                        channels = filteredChannels,
                        clockTickMillis = guideClockMillis,
                        nowNext = effectiveSnapshotNowNext,
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
                            if (isLibraryChannelGroup(channel.source.group)) {
                                if (program != null && !musicMenuJustClosed(channel.id)) libraryCellTarget = channel to program
                            } else if (program != null) {
                                programInfoTarget = channel to program
                            } else {
                                playProgramInMini(channel, null)
                            }
                        },
                        onChannelFocused = { channel ->
                            focusedChannelId = channel.id
                            previewLibraryChannel(channel)
                            rememberedChannelByCategory[selectedCategoryId] = channel.id
                        },
                        onChannelLongPress = { channel -> if (isLibraryChannelGroup(channel.source.group)) libraryMenuChannel = channel else favoriteMenuChannel = channel },
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
                        artUrl = libraryArtFor(playingChannel),
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { coords ->
                            miniPlayerHeightPx = coords.size.height
                        },
                    )
                    EpgGrid(
                        zoom = musicGridZoom(filteredChannels),
                        focusSuspended = searchOpen,
                            channels = filteredChannels,
                            clockTickMillis = guideClockMillis,
                            nowNext = effectiveSnapshotNowNext,
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
                            if (isLibraryChannelGroup(channel.source.group)) {
                                if (program != null && !musicMenuJustClosed(channel.id)) libraryCellTarget = channel to program
                            } else if (program != null) {
                                programInfoTarget = channel to program
                            } else {
                                playProgramInMini(channel, null)
                            }
                        },
                            onChannelFocused = { channel ->
                                focusedChannelId = channel.id
                                previewLibraryChannel(channel)
                                rememberedChannelByCategory[selectedCategoryId] = channel.id
                            },
                            onChannelLongPress = { channel -> if (isLibraryChannelGroup(channel.source.group)) libraryMenuChannel = channel else favoriteMenuChannel = channel },
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
                        onRefreshPlaylist = { viewModel.refreshPlaylistAndEpg() },
                        isRefreshingPlaylist = state.isRefreshingPlaylist,
                        playlistRefreshResult = state.playlistRefreshResult,
                        onFocusEnter = { focusZone = LiveTvFocusZone.CATEGORY_LIST },
                        onMoveRight = {
                            val remembered = rememberedChannelByCategory[selectedCategoryId]
                                ?.takeIf { id -> filteredChannels.any { it.id == id } }
                            // Music: land on whatever a zone is playing (Joe, 2026-10-03).
                            val target = playingMusicChannelId(filteredChannels, musicPlaylists, musicLineups)
                                ?: remembered
                                ?: focusedChannelId?.takeIf { id -> filteredChannels.any { it.id == id } }
                                ?: playingChannelId?.takeIf { id -> filteredChannels.any { it.id == id } }
                                ?: filteredChannels.firstOrNull()?.id
                            focusChannelList(target)
                        },
                        // Up at the top of the sidebar is a dead end on purpose: it used to open the
                        // nav menu, so a stray Up (or two) from Search pulled it open (Joe,
                        // 2026-09-30). Left is the one way in.
                        onMoveUpFromSearch = {},
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
                        color = (LocalFocusBorderColorOverride.current ?: LiveColors.Accent),
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
            val remoteDevices by viewModel.remoteDevices.collectAsStateWithLifecycle()
            val remoteControlDevice by viewModel.controlDevice.collectAsStateWithLifecycle()
            var showTvRemotePairingDialogFor by remember { mutableStateOf<String?>(null) }
            var remoteSpeakers by remember { mutableStateOf<List<com.arflix.tv.ui.components.HaSpeaker>>(emptyList()) }
            // Explicitly-picked device, else the playback target. Selecting a TV here drives its
            // buttons without moving where channels actually play.
            val activeRemoteDevice = remoteControlDevice
                ?: remoteTarget?.let { com.arflix.tv.data.repository.tvremote.RemoteDevice.fromPeer(it) }
            var tvRemotePaired by remember(activeRemoteDevice?.id) { mutableStateOf(false) }
            var remoteVolumeEntity by remember(activeRemoteDevice?.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(activeRemoteDevice?.id) {
                val device = activeRemoteDevice
                tvRemotePaired = device?.peer?.host?.let { viewModel.isTvRemotePaired(it) } ?: false
                remoteVolumeEntity = device?.id?.let { viewModel.remoteProfileFor(it).volumeEntity }
                if (device != null && remoteSpeakers.isEmpty()) remoteSpeakers = viewModel.loadRemoteSpeakers()
                viewModel.loadRemoteDevices()
            }
            com.arflix.tv.ui.components.RemoteModeSheet(
                devices = remoteDevices,
                device = activeRemoteDevice,
                onSelectDevice = { viewModel.setControlDevice(it) },
                onSendDpad = { key ->
                    when (key) {
                        com.arflix.tv.data.repository.DPadKey.VOLUME_UP -> viewModel.sendRemoteVolumeUp()
                        com.arflix.tv.data.repository.DPadKey.VOLUME_DOWN -> viewModel.sendRemoteVolumeDown()
                        else -> viewModel.sendRemoteKey(key)
                    }
                },
                onSendText = { text -> viewModel.sendRemoteText(text) },
                onDismiss = { showRemoteModeSheet = false },
                isTvRemotePaired = tvRemotePaired,
                onPairTvRemote = { activeRemoteDevice?.peer?.host?.let { showTvRemotePairingDialogFor = it } },
                onSendPower = { viewModel.sendRemotePower() },
                speakers = remoteSpeakers,
                volumeEntityId = remoteVolumeEntity,
                onSelectVolumeEntity = { entityId ->
                    activeRemoteDevice?.id?.let { id ->
                        remoteVolumeEntity = entityId
                        fsScope.launch {
                            viewModel.setRemoteProfile(
                                id,
                                com.arflix.tv.data.repository.tvremote.RemoteVolumeRouter.DeviceProfile(entityId),
                            )
                        }
                    }
                },
                onSelectInput = { source -> fsScope.launch { viewModel.selectRemoteInput(source) } },
            )
            showTvRemotePairingDialogFor?.let { pairingHost ->
                com.arflix.tv.ui.components.TvRemotePairingDialog(
                    host = pairingHost,
                    onStart = { viewModel.startTvRemotePairing() },
                    onFinished = {
                        val deviceName = remoteTarget?.takeIf { it.host == pairingHost }?.displayName ?: pairingHost
                        fsScope.launch {
                            viewModel.onTvRemotePairingFinished(pairingHost, deviceName)
                            tvRemotePaired = true
                        }
                    },
                    onDismiss = { showTvRemotePairingDialogFor = null },
                )
            }
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
            searchReturnQuery = lastSearchQuery.takeIf { it.isNotBlank() }
            reopenSearchQuery = null
            focusChannelList(channel.id)
        }

        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            SearchOverlay(
                initialQuery = reopenSearchQuery ?: remoteSearchQuery ?: initialSearchQuery.orEmpty(),
                onQueryChange = { lastSearchQuery = it },
                channels = remember(enrichedState.value.all, state.snapshot.removedGroups) {
                    val removed = state.snapshot.removedGroups.toSet()
                    enrichedState.value.all.filterNot { it.source.group in removed }
                },
                nowNext = effectiveSnapshotNowNext,
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
                // Deliberately not wired: the guide's search is for live TV only — the channels
                // already in the lineup, what is on them, and anything in the providers' full
                // catalogue that can be added to it. Movies and shows belong to the Search entry
                // in the nav rail, which searches TMDB and clicks through to Details where the
                // watchlist and Episeerr rule paths live. Having both here duplicated that screen
                // and pushed programme results further down a list they were already losing.
                // (SearchOverlay's onMediaSearch defaults to empty, so the section stays hidden.)
                onPickMedia = { media ->
                    searchOpen = false
                    onNavigateToDetails(media.mediaType, media.id)
                },
                musicSearchAvailable = musicConfigured,
                onMusicSearch = { q -> viewModel.searchMusic(q) },
                onPickMusic = { item ->
                    searchOpen = false
                    musicSearchPick = item
                },
                favoriteIds = favSet,
                libraryItems = remember(showsGuideSchedule, movieGuide) {
                    showsGuideSchedule.mapNotNull { show ->
                        show.tmdbId?.let { id ->
                            com.arflix.tv.data.model.MediaItem(id = id, title = show.title, mediaType = MediaType.TV, backdrop = show.fanart)
                        }
                    } + movieGuide.movies.map { m ->
                        com.arflix.tv.data.model.MediaItem(id = m.tmdbId, title = m.title, year = m.year?.toString().orEmpty(), mediaType = MediaType.MOVIE, backdrop = m.fanart)
                    } + movieGuide.premiering.map { m ->
                        com.arflix.tv.data.model.MediaItem(id = m.tmdbId, title = m.title, year = m.year?.toString().orEmpty(), mediaType = MediaType.MOVIE, backdrop = m.fanart)
                    }
                },
                libraryMediaKeys = remember(showsGuideSchedule, movieGuide) {
                    (showsGuideSchedule.mapNotNull { it.tmdbId?.let { id -> "tv:$id" } } +
                        movieGuide.movies.map { "movie:${it.tmdbId}" } +
                        movieGuide.premiering.map { "movie:${it.tmdbId}" }).toSet()
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

        libraryMenuChannel?.takeIf { !isMusicChannelId(it.id) }?.let { menuCh ->
            val showEntry = showEntryFor(menuCh.id)
            val movie = libraryMovieFor(menuCh.id)
            val premiere = premiereFor(menuCh.id)
            val actions = buildList {
                add(com.arflix.tv.ui.components.ContextAction(
                    "info", if (showEntry != null) "Episodes & Info" else "Movie Info", Icons.Default.Info,
                ))
                if (showEntry != null) {
                    add(com.arflix.tv.ui.components.ContextAction("rule", "Change Rule", Icons.Default.Tune))
                }
            }
            // Popups take real focus to receive D-pad input and nothing hands it back when they
            // close, which strands the guide with no focused node. Always reclaim the row.
            val closeMenu = {
                libraryMenuChannel = null
                musicMenuClosedAt = android.os.SystemClock.uptimeMillis()
                focusChannelList(menuCh.id)
            }
            com.arflix.tv.ui.components.ContextMenu(
                isVisible = true,
                title = menuCh.name,
                subtitle = showEntry?.rule?.let { "Rule: ${it.replace('_', ' ')}" }.orEmpty(),
                actions = actions,
                onAction = { action ->
                    when (action.id) {
                        "info" -> {
                            when {
                                showEntry != null -> openShowDetails(showEntry)
                                movie != null -> {
                                    reopenMenuAfterDetails = menuCh.id
                                    onNavigateToDetails(MediaType.MOVIE, movie.tmdbId)
                                }
                                premiere != null -> {
                                    reopenMenuAfterDetails = menuCh.id
                                    onNavigateToDetails(MediaType.MOVIE, premiere.tmdbId)
                                }
                            }
                            closeMenu()
                        }
                        "rule" -> {
                            // Hand straight over to the picker; it restores focus when it closes.
                            libraryMenuChannel = null
                            rulePickerShow = showEntry
                        }
                        else -> closeMenu()
                    }
                },
                onDismiss = closeMenu,
            )
        }

        LaunchedEffect(pendingMenuReopen, enrichedState.value.index) {
            val id = pendingMenuReopen ?: return@LaunchedEffect
            // Only straight back from Details (selecting any channel also cancels it). It used to wait for the
            // channel to show up in the index and then fire whenever -- a movie's menu popped up
            // over the Music rows long after (Joe, 2026-10-04 photo, "Heart of the Beast").
            if (android.os.SystemClock.uptimeMillis() - pendingMenuReopenAt > 4_000L) {
                pendingMenuReopen = null
                return@LaunchedEffect
            }
            val ch = enrichedState.value.index.byId[id] ?: return@LaunchedEffect
            pendingMenuReopen = null
            previewLibraryChannel(ch)
            libraryMenuChannel = ch
        }

        // Music playlist rows (channel or cell): pick the Sonos zone to start it on.
        (libraryMenuChannel?.takeIf { isMusicChannelId(it.id) } ?: libraryCellTarget?.first?.takeIf { isMusicChannelId(it.id) })?.let { musicCh ->
            val fromCell = libraryCellTarget?.first?.id == musicCh.id
            MusicZoneMenu(
                viewModel = viewModel,
                channelId = musicCh.id,
                channelName = musicCh.name,
                playlist = musicPlaylists.musicItemFor(musicCh.id),
                cellProgram = if (fromCell) libraryCellTarget?.second else null,
                onPlayed = { ok, message ->
                    // Highlighting a music row only repoints the hero; the live channel
                    // kept playing underneath, so its audio ran over the music (Joe,
                    // 2026-10-03). Stop it outright rather than pause: a paused stream
                    // is restarted by ON_RESUME's isActive check on the way back from
                    // any other screen. Picking a real channel again starts it fresh.
                    if (ok) playerViewModel.dismiss()
                    guideMessage = message
                },
                onClose = {
                    musicMenuClosedAt = android.os.SystemClock.uptimeMillis()
                    libraryMenuChannel = null
                    libraryCellTarget = null
                    if (fromCell) focusEpg(musicCh.id) else focusChannelList(musicCh.id)
                },
            )
        }

        musicSearchPick?.let { item ->
            MusicZoneMenu(
                viewModel = viewModel,
                channelId = "search:${item.uri}",
                channelName = item.name,
                playlist = item,
                cellProgram = null,
                onPlayed = { ok, message ->
                    if (ok) playerViewModel.dismiss()
                    guideMessage = message
                },
                onClose = {
                    musicSearchPick = null
                    musicMenuClosedAt = android.os.SystemClock.uptimeMillis()
                    focusChannelList()
                },
            )
        }

        libraryCellTarget?.takeIf { !isMusicChannelId(it.first.id) }?.let { (cellChannel, cellProgram) ->
            val showEntry = showEntryFor(cellChannel.id)
            val movie = libraryMovieFor(cellChannel.id)
            val premiere = premiereFor(cellChannel.id)
            val se = parseShowEpisodeTitle(cellProgram.title)
            val closeMenu = {
                libraryCellTarget = null
                musicMenuClosedAt = android.os.SystemClock.uptimeMillis()
                focusEpg(cellChannel.id)
            }
            // Which episode this cell is, and whether it's on disk.
            val isNowEp = showEntry != null && se != null && showEntry.now?.season == se.first && showEntry.now.episode == se.second
            val nextEp = showEntry?.next?.takeIf { se != null && it.season == se.first && it.episode == se.second }
            val epDownloaded = isNowEp || nextEp?.downloaded == true
            val epAired = nextEp != null && runCatching { java.time.Instant.parse(nextEp.airDate).toEpochMilli() <= System.currentTimeMillis() }.getOrDefault(true)
            val actions = buildList {
                when {
                    showEntry != null && se != null -> {
                        if (epDownloaded) {
                            add(com.arflix.tv.ui.components.ContextActions.play)
                            add(com.arflix.tv.ui.components.ContextActions.markWatched)
                        } else if (nextEp != null && epAired) {
                            add(com.arflix.tv.ui.components.ContextActions.searchSonarr)
                        }
                    }
                    movie != null -> {
                        add(com.arflix.tv.ui.components.ContextActions.play)
                        if (!movie.watched) add(com.arflix.tv.ui.components.ContextActions.markWatched)
                    }
                    premiere != null -> add(com.arflix.tv.ui.components.ContextActions.searchSonarr)
                }
                // Nothing to do on this cell (future episode, or one already watched): offer the
                // show's page instead of an empty menu.
                if (isEmpty() && showEntry != null) add(com.arflix.tv.ui.components.ContextAction("info", "Episodes & Info", Icons.Default.Info))
            }
            com.arflix.tv.ui.components.ContextMenu(
                isVisible = true,
                title = cellChannel.name,
                subtitle = cellProgram.title,
                actions = actions,
                onAction = { action ->
                    when (action.id) {
                        "play" -> when {
                            showEntry != null && se != null -> playLibraryEpisode(showEntry, se.first, se.second)
                            movie != null -> onNavigateToPlayer(MediaType.MOVIE, movie.tmdbId, null, null, null, null, null, null, null, false)
                        }
                        "mark_watched" -> when {
                            showEntry != null && se != null -> {
                                viewModel.markShowEpisodeWatched(showEntry, se.first, se.second)
                                guideMessage = "Marked ${showEntry.title} S${se.first}E${se.second} watched"
                            }
                            movie != null -> {
                                viewModel.markMovieWatched(movie)
                                guideMessage = "Marked ${movie.title} watched"
                            }
                        }
                        "search_sonarr" -> when {
                            showEntry?.tvdbId != null && se != null ->
                                viewModel.searchShowEpisode(showEntry.tvdbId, se.first, se.second) { ok ->
                                    guideMessage = if (ok) "Looking for a download of ${showEntry.title} S${se.first}E${se.second}" else "Couldn't start the download search"
                                }
                            premiere != null -> viewModel.searchMovie(premiere.radarrId) { ok ->
                                guideMessage = if (ok) "Looking for a download of ${premiere.title}" else "Couldn't start the download search"
                            }
                        }
                        "info" -> showEntry?.let { openShowDetails(it) }
                    }
                    closeMenu()
                },
                onDismiss = closeMenu,
            )
        }

        rulePickerShow?.let { show ->
            val showChannelId = "$ShowsChannelIdPrefix${show.seriesId}"
            GuideRulePickerOverlay(
                show = show,
                // Back out of the picker without choosing -> back to the channel menu.
                onBackToMenu = {
                    rulePickerShow = null
                    libraryMenuChannel = enrichedState.value.index.byId[showChannelId]
                    if (libraryMenuChannel == null) focusChannelList(showChannelId)
                },
                onRuleAssigned = {
                    viewModel.refreshShowsGuide(forceRefresh = true)
                    rulePickerShow = null
                    focusChannelList(showChannelId)
                },
            )
        }

        // Loading screen (Joe, 2026-09-30: "instead of blank guide a nice hero poster like a
        // loading screen"): a random backdrop from his own library while the IPTV channels load
        // on a cold start. Returning to the guide reuses cached channels, so it doesn't show
        // then; capped at 20s so a setup with no IPTV can never leave it up.
        // A random title that's ready to watch -- a show with its next episode downloaded, or an
        // unwatched movie -- different each cold start. Was "latest download", which tended to
        // be things like UFC (Joe, 2026-09-30: "doesn't excite me media wise").
        var splash by remember { mutableStateOf<Pair<String, String>?>(null) }
        LaunchedEffect(showsGuideSchedule, movieGuide) {
            if (splash == null) {
                val shows = showsGuideSchedule.mapNotNull { s ->
                    val ep = s.now ?: return@mapNotNull null
                    s.fanart?.let { it to "UP NEXT · ${s.title} S${ep.season}E${ep.episode}" }
                }
                // Joe's a TV-show person: movies only make the cut when they're this year's
                // releases (2026-09-30: "unless it's a very recent movie").
                val thisYear = java.time.Year.now().value
                val movies = movieGuide.movies.filter { !it.watched && (it.year ?: 0) >= thisYear }.mapNotNull { m ->
                    m.fanart?.let { it to "READY TO WATCH · ${m.title}" }
                }
                splash = (shows + movies).randomOrNull()
            }
        }
        val splashArt = splash?.first
        var splashTimedOut by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(20_000L)
            splashTimedOut = true
        }
        val iptvLoaded = enrichedState.value.all.any { !isLibraryChannelGroup(it.source.group) }
        androidx.compose.animation.AnimatedVisibility(
            visible = !iptvLoaded && !splashTimedOut,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(600)),
            modifier = Modifier.fillMaxSize().zIndex(400f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(colors = listOf(LiveColors.Panel, LiveColors.Bg))),
            ) {
                splashArt?.let { art ->
                    coil.compose.AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.15f),
                                0.55f to Color.Black.copy(alpha = 0.45f),
                                1f to Color.Black.copy(alpha = 0.92f),
                            )
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 64.dp, bottom = 56.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    splash?.second?.let { caption ->
                        Text(
                            text = caption,
                            style = LiveType.SectionTag.copy(
                                color = LocalFocusBorderColorOverride.current ?: LiveColors.Accent,
                                fontSize = 14.sp,
                            ),
                        )
                    }
                    Text(
                        text = "Xadarr",
                        style = LiveType.CellTitle.copy(color = LiveColors.Fg, fontSize = 44.sp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = LocalFocusBorderColorOverride.current ?: LiveColors.Accent,
                        )
                        Text(
                            text = "Loading your guide…",
                            style = LiveType.CellTitle.copy(color = LiveColors.FgDim, fontSize = 16.sp),
                        )
                    }
                }
            }
        }

        // Direct channel-number entry: show the digits, tune 1.5s after the last one.
        LaunchedEffect(channelDigits) {
            if (channelDigits.isEmpty()) return@LaunchedEffect
            delay(1_500L)
            val number = channelDigits.toIntOrNull()
            val target = number?.let { n -> enrichedState.value.all.firstOrNull { it.number == n && !isLibraryChannelGroup(it.source.group) } }
            if (target != null) {
                previousChannelId = playingChannelId
                playingChannelId = target.id
                playingCatchupProgram = null
                focusedChannelId = target.id
                focusSelectedChannelSignal += 1
            } else {
                guideMessage = "No channel $channelDigits"
            }
            channelDigits = ""
        }
        if (channelDigits.isNotEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().zIndex(350f).padding(top = 40.dp, end = 48.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                Text(
                    text = "CH  " + channelDigits.padEnd(3, '_'),
                    style = LiveType.NumberMono.copy(color = LiveColors.Fg, fontSize = 34.sp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(LiveColors.PanelRaised)
                        .border(2.dp, LocalFocusBorderColorOverride.current ?: LiveColors.Accent, RoundedCornerShape(10.dp))
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                )
            }
        }

        guideMessage?.let { msg ->
            Box(
                modifier = Modifier.fillMaxSize().zIndex(300f).padding(bottom = 48.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = msg,
                    style = LiveType.CellTitle.copy(color = LiveColors.Fg, fontSize = 16.sp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(LiveColors.PanelRaised)
                        .border(1.dp, LiveColors.Divider, RoundedCornerShape(10.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }

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
                    val wasSet = reminders.any { it.key == reminderKey }
                    if (wasSet) {
                        viewModel.cancelProgramReminder(infoChannel.id, infoProgram)
                    } else {
                        viewModel.setProgramReminder(infoChannel.id, infoChannel.name, infoProgram)
                    }
                    // Close and hand focus back to the program, instead of leaving the popup up
                    // (Joe, 2026-09-30: popups "lose focus like the remind me").
                    guideMessage = if (wasSet) "Reminder cancelled" else "Reminder set"
                    programInfoTarget = null
                    focusEpg(infoChannel.id)
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
const val PinnedChannelsGroup = "Pinned"

/**
 * [groupOverride] exists for pinned channels. A raw provider stream carries the group it has in
 * the provider's catalogue, which is almost always one that is hidden here — that is *why* the
 * channel was not in the lineup. Pinning it while keeping that group therefore filed it straight
 * back into a hidden group, where it could not be found again, which rather defeats pinning.
 *
 * Pinned channels go to their own group instead. It never appears in the M3U, so the auto-hide
 * that catches genuinely new provider groups leaves it alone, and it stays visible until the
 * channel is unpinned.
 */
// Split out of LiveTvScreen: that composable is big enough that Android 11's ART verifier
// (the Shield) rejects it outright with "register has type Conflict" -- a VerifyError at
// launch. Keep self-contained overlays out here as their own functions.
private const val MusicRadioToggleId = "__music_radio__"

private const val MusicGroupId = "__music_group__"
private const val MusicDoneId = "__music_done__"

@Composable
private fun MusicZoneMenu(
    viewModel: TvViewModel,
    channelId: String,
    channelName: String,
    playlist: com.arflix.tv.music.MaMediaItem?,
    cellProgram: IptvProgram?,
    onPlayed: (ok: Boolean, message: String) -> Unit,
    onClose: () -> Unit,
) {
    val zones by viewModel.musicZones.collectAsStateWithLifecycle()
    val musicTracks by viewModel.musicTracks.collectAsStateWithLifecycle()
    val musicLineups by viewModel.musicLineups.collectAsStateWithLifecycle()
    // A song cell: play from that song (Joe, 2026-10-03: "go to any song in the grid and play
    // it"). Null for the channel row or the playlist filler cell -> the whole playlist.
    val songPick = remember(playlist, cellProgram) {
        musicSongFor(playlist, cellProgram, musicTracks, musicLineups)
    }
    val song = songPick?.first
    val speakers by viewModel.musicSpeakers.collectAsStateWithLifecycle()
    val lastZoneId by viewModel.lastMusicZoneId.collectAsStateWithLifecycle()
    LaunchedEffect(channelId) { viewModel.refreshMusicZones() }
    // Last-used zone first, so "select, select" replays where you last listened.
    val ordered = remember(zones, lastZoneId) { zones.sortedBy { if (it.id == lastZoneId) 0 else 1 } }
    // Radio: MA's endless mix seeded from the playlist (similar tracks, no repeats) instead of
    // the playlist itself. Toggled by the last row; the zones stay on top.
    var radio by remember(channelId) { mutableStateOf(false) }
    // Speaker grouping: "Group speakers…" -> pick the speaker to group around -> tick the
    // speakers that join it. Back steps out one level at a time.
    var picking by remember(channelId) { mutableStateOf(false) }
    var groupLeaderId by remember(channelId) { mutableStateOf<String?>(null) }
    val leader = groupLeaderId?.let { id -> speakers.firstOrNull { it.id == id } }
    // Ticks flip on the press; MA reports the regroup a second or two later. Reading only MA's
    // state made a quick second press resend the same add/remove (Joe: "a couple presses").
    var pendingGroup by remember(channelId) { mutableStateOf(mapOf<String, Boolean>()) }
    fun reportedInGroup(sp: com.arflix.tv.music.MaPlayer, lead: com.arflix.tv.music.MaPlayer) =
        sp.syncedTo == lead.id || sp.id in lead.groupMembers
    LaunchedEffect(speakers) {
        val lead = leader ?: return@LaunchedEffect
        pendingGroup = pendingGroup.filter { (id, want) ->
            speakers.firstOrNull { it.id == id }?.let { reportedInGroup(it, lead) != want } ?: false
        }
    }

    fun zoneLabel(zone: com.arflix.tv.music.MaPlayer): String {
        val status = when {
            zone.isTvAudio -> " · TV audio"
            zone.isPlaying && zone.nowTitle != null -> " · playing ${zone.nowTitle}"
            zone.isPlaying -> " · playing"
            else -> ""
        }
        val members = if (zone.isGroupLeader) " +${zone.groupMembers.size - 1}" else ""
        return zone.name + members + status
    }

    // Fresh focus (top row) on each step.
    key(picking, groupLeaderId) {
        when {
            leader != null -> com.arflix.tv.ui.components.ContextMenu(
                isVisible = true,
                title = "Group with ${leader.name}",
                subtitle = "Select speakers to add or remove",
                actions = speakers.filter { it.id != leader.id }.map { sp ->
                    val inGroup = pendingGroup[sp.id] ?: reportedInGroup(sp, leader)
                    val elsewhere = sp.syncedTo?.takeIf { it != leader.id }?.let { other -> speakers.firstOrNull { it.id == other }?.name }
                    com.arflix.tv.ui.components.ContextAction(
                        sp.id,
                        (if (inGroup) "✓  " else "     ") + sp.name + (elsewhere?.let { " · with $it" } ?: ""),
                        Icons.Default.Speaker,
                    )
                } + com.arflix.tv.ui.components.ContextAction(MusicDoneId, "Done", Icons.Default.Check),
                onAction = { action ->
                    if (action.id == MusicDoneId) {
                        groupLeaderId = null
                        return@ContextMenu
                    }
                    val sp = speakers.firstOrNull { it.id == action.id } ?: return@ContextMenu
                    val join = !(pendingGroup[sp.id] ?: reportedInGroup(sp, leader))
                    pendingGroup = pendingGroup + (sp.id to join)
                    viewModel.setSpeakerGrouped(leader.id, sp.id, join) { ok ->
                        if (!ok) {
                            pendingGroup = pendingGroup - sp.id
                            onPlayed(false, "Couldn't ${if (join) "add" else "remove"} ${sp.name}")
                        }
                    }
                },
                onDismiss = { groupLeaderId = null },
            )
            picking -> com.arflix.tv.ui.components.ContextMenu(
                isVisible = true,
                title = "Group speakers",
                subtitle = "Group around which speaker?",
                actions = ordered.map { com.arflix.tv.ui.components.ContextAction(it.id, zoneLabel(it), Icons.Default.Speaker) },
                onAction = { action ->
                    picking = false
                    groupLeaderId = action.id
                },
                onDismiss = { picking = false },
            )
            else -> com.arflix.tv.ui.components.ContextMenu(
                isVisible = true,
                title = song?.let { s -> listOfNotNull(s.name, s.artist).joinToString(" · ") } ?: channelName,
                subtitle = when {
                    zones.isEmpty() -> "Looking for zones…"
                    radio && song != null -> "Start radio from this song on"
                    radio -> "Start radio on"
                    song != null -> "Play from this song on"
                    else -> "Play on"
                },
                actions = ordered.map { com.arflix.tv.ui.components.ContextAction(it.id, zoneLabel(it), Icons.Default.Speaker) } +
                    com.arflix.tv.ui.components.ContextAction(MusicGroupId, "Group speakers…", Icons.Default.Speaker) +
                    com.arflix.tv.ui.components.ContextAction(
                        MusicRadioToggleId,
                        if (radio) "Radio: on · switch to playlist" else "Radio: off · play similar songs endlessly",
                        Icons.Default.Radio,
                    ),
                onAction = { action ->
                    when (action.id) {
                        MusicRadioToggleId -> radio = !radio
                        MusicGroupId -> picking = true
                        else -> {
                            val zone = zones.firstOrNull { it.id == action.id }
                            if (playlist != null && zone != null) {
                                val lineup = songPick?.second
                                val songUri = song?.uri
                                // Same zone the song's queue is on: jump within it. Radio: seed
                                // from the song. Otherwise: the playlist, starting at the song.
                                val jumpTo = song?.queueItemId?.takeIf { !radio && lineup?.queueId == zone.id }
                                viewModel.playMusicOn(
                                    zoneId = zone.id,
                                    uri = if (radio && songUri != null) songUri else playlist.uri,
                                    radio = radio,
                                    startItem = songUri?.takeIf { !radio && jumpTo == null },
                                    queueItemId = jumpTo,
                                    fallbackUris = (lineup?.tracks ?: musicTracks[playlist.uri]?.tracks.orEmpty())
                                        .dropWhile { it != song }.mapNotNull { it.uri },
                                ) { ok ->
                                    val what = when {
                                        radio && song != null -> "${song.name} radio"
                                        radio -> "${playlist.name} radio"
                                        song != null -> song.name
                                        else -> playlist.name
                                    }
                                    onPlayed(ok, if (ok) "Playing $what on ${zoneLabel(zone).substringBefore(" · ")}" else "Couldn't start $what")
                                }
                            }
                            onClose()
                        }
                    }
                },
                onDismiss = onClose,
            )
        }
    }
}

@Composable
private fun GuideRulePickerOverlay(
    show: com.arflix.tv.data.repository.ShowGuideEntry,
    onBackToMenu: () -> Unit,
    onRuleAssigned: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().zIndex(200f)) {
        val rulePickerVm: com.arflix.tv.ui.screens.episeerr.RulePickerViewModel =
            androidx.hilt.navigation.compose.hiltViewModel()
        val syncServerUrl by rulePickerVm.syncServerUrl.collectAsStateWithLifecycle()
        val episeerrUrl by rulePickerVm.episeerrUrl.collectAsStateWithLifecycle()
        com.arflix.tv.ui.screens.episeerr.RulePickerScreen(
            pendingItem = com.arflix.tv.data.repository.EpiseerrPendingItem(
                id = show.seriesId.toString(),
                seriesId = show.seriesId,
                title = show.title,
                tmdbId = null,
                tvdbId = show.tvdbId?.toString(),
                poster = show.fanart,
            ),
            episeerrRepository = rulePickerVm.episeerrRepository,
            syncServerUrl = syncServerUrl,
            episeerrUrl = episeerrUrl,
            currentRuleName = show.rule,
            onAssignRule = { ruleName -> rulePickerVm.episeerrRepository.assignRuleToSeries(show.seriesId, ruleName) },
            onDismiss = onBackToMenu,
            onRuleAssigned = onRuleAssigned,
        )
    }
}

fun RawProviderStream.toIptvChannel(groupOverride: String? = null): IptvChannel = IptvChannel(
    id = id,
    name = name,
    streamUrl = streamUrl,
    group = groupOverride ?: group,
    logo = logo,
    epgId = tvgId,
)

/**
 * Synthetic "Shows" guide channel — see project_nostalgex_style_media_channel_2026-09-29 memory
 * for the full design. Xadarr's own library (Sonarr-backed) presented as one row per show in the
 * live guide, same shape as [RawProviderStream.toIptvChannel] above: a plain [IptvChannel] +
 * [IptvNowNext] pair spliced into the real guide pipeline, not a new structural path.
 *
 * `streamUrl` is deliberately empty — this channel is never handed to ExoPlayer directly.
 * Season/episode is encoded in each [IptvProgram]'s title text (e.g. "S6E2 · Slow Horses"), the
 * same way [com.arflix.tv.ui.screens.tv.live.synthesizeNowNextFromChannelName] already parses
 * structured info back out of title text for PPV channels — not extending the shared model for
 * data only this one synthetic source needs. No poster/logo field: confirmed against Joe's own
 * screenshots 2026-09-29 that this channel type renders as plain EPG text cells, identical to a
 * real channel, never poster art.
 */
const val ShowsChannelGroup = "Shows"
private const val ShowsChannelIdPrefix = "show:"

/** Library-backed synthetic guide groups (no real stream behind the channel row itself). */
const val MoviesChannelGroup = "Movies"
fun isLibraryChannelGroup(group: String?): Boolean =
    group == ShowsChannelGroup || group == MoviesChannelGroup || group == MusicChannelGroup

/**
 * Synthetic "Music" guide channels: one per Music Assistant playlist (com.arflix.tv.music).
 * Like Shows/Movies there's no stream behind the row -- selecting one picks a Sonos zone and
 * starts the playlist there through Music Assistant; the TV itself never plays the audio.
 */
const val MusicChannelGroup = "Music"
private const val MusicChannelIdPrefix = "music:"
fun isMusicChannelId(id: String?): Boolean = id != null && id.startsWith(MusicChannelIdPrefix)

private fun musicChannelId(item: com.arflix.tv.music.MaMediaItem) =
    MusicChannelIdPrefix + item.uri.hashCode().toUInt().toString(16)

fun List<com.arflix.tv.music.MaMediaItem>.toMusicChannels(): List<IptvChannel> = map {
    IptvChannel(id = musicChannelId(it), name = it.name, streamUrl = "", group = MusicChannelGroup, logo = it.imageUrl)
}

// Music rows run like a real channel: each song is a program as long as the song. A playlist
// that's playing on a zone shows that zone's live queue, lined up to where it is; any other
// playlist shows its own tracks as if started now (Joe, 2026-10-03).
fun List<com.arflix.tv.music.MaMediaItem>.toMusicNowNext(
    clockMillis: Long,
    tracks: Map<String, com.arflix.tv.music.MaPlaylistTracks>,
    lineups: List<com.arflix.tv.music.MaLineup>,
): Map<String, IptvNowNext> {
    val hour = 60 * 60 * 1000L
    return associate { playlist ->
        val lineup = lineups.lineupFor(playlist.uri)
        val stats = tracks[playlist.uri]
        val songs = lineup?.tracks ?: stats?.tracks.orEmpty()
        val nowNext = if (songs.isEmpty()) {
            IptvNowNext(now = IptvProgram(
                title = playlist.subtitle?.takeIf { s -> s.isNotBlank() }?.let { s -> "Playlist · $s" } ?: "Playlist",
                description = "Select to play on a Sonos zone",
                startUtcMillis = clockMillis - hour,
                endUtcMillis = clockMillis + 3 * hour,
            ))
        } else {
            val footer = lineup?.let { "Playing on ${it.zoneName}" } ?: "Select to play on a Sonos zone"
            var start = lineup?.currentStartMillis ?: clockMillis
            val programs = ArrayList<IptvProgram>(songs.size)
            for (song in songs) {
                if (start > clockMillis + 4 * hour) break
                val end = start + (song.durationSec.takeIf { it > 0 } ?: 210) * 1000L
                programs += IptvProgram(
                    title = listOfNotNull(song.name, song.artist).joinToString(" · "),
                    description = listOfNotNull(song.album, footer).joinToString(" · "),
                    startUtcMillis = start,
                    endUtcMillis = end,
                )
                start = end
            }
            val nowIdx = programs.indexOfFirst { it.isLive(clockMillis) }.takeIf { it >= 0 }
                ?: programs.indexOfFirst { it.startUtcMillis > clockMillis }.coerceAtLeast(0)
            // Fill the grid before the first song with the playlist itself rather than the
            // generic "Not yet released" gap (Joe, 2026-10-03: "playlist length").
            programs.firstOrNull()?.let { first ->
                programs.add(0, IptvProgram(
                    title = musicPlaylistSummary(stats) ?: playlist.name,
                    description = footer,
                    startUtcMillis = first.startUtcMillis - 12 * hour,
                    endUtcMillis = first.startUtcMillis,
                ))
            }
            val idx = nowIdx + if (programs.size > 1) 1 else 0
            IptvNowNext(
                now = programs.getOrNull(idx),
                next = programs.getOrNull(idx + 1),
                later = programs.getOrNull(idx + 2),
                upcoming = programs.drop(idx + 3),
                recent = programs.take(idx),
            )
        }
        musicChannelId(playlist) to nowNext
    }
}

/** The song behind a Music grid cell, and the live lineup it came from (if any). */
private fun musicSongFor(
    playlist: com.arflix.tv.music.MaMediaItem?,
    program: IptvProgram?,
    tracks: Map<String, com.arflix.tv.music.MaPlaylistTracks>,
    lineups: List<com.arflix.tv.music.MaLineup>,
): Pair<com.arflix.tv.music.MaTrack, com.arflix.tv.music.MaLineup?>? {
    if (playlist == null || program == null) return null
    fun title(t: com.arflix.tv.music.MaTrack) = listOfNotNull(t.name, t.artist).joinToString(" · ")
    val lineup = lineups.lineupFor(playlist.uri)
    lineup?.tracks?.firstOrNull { title(it) == program.title }?.let { return it to lineup }
    return tracks[playlist.uri]?.tracks?.firstOrNull { title(it) == program.title }?.let { it to null }
}

/** "418 songs · 26 hr 10 min" */
private fun musicPlaylistSummary(stats: com.arflix.tv.music.MaPlaylistTracks?): String? {
    stats ?: return null
    val h = stats.totalSec / 3600
    val m = (stats.totalSec % 3600) / 60
    val length = if (h > 0) "$h hr $m min" else "$m min"
    return "${stats.count} songs · $length"
}

private fun List<com.arflix.tv.music.MaLineup>.lineupFor(uri: String) =
    firstOrNull { it.sourceUri == uri && it.tracks.isNotEmpty() }

/** Cover for the hero: the song playing now on that playlist, else its first song, else the playlist's own art. */
fun musicArtFor(
    playlist: com.arflix.tv.music.MaMediaItem?,
    tracks: Map<String, com.arflix.tv.music.MaPlaylistTracks>,
    lineups: List<com.arflix.tv.music.MaLineup>,
    clockMillis: Long,
): String? {
    playlist ?: return null
    lineups.lineupFor(playlist.uri)?.let { lineup ->
        var start = lineup.currentStartMillis
        for (song in lineup.tracks) {
            val end = start + (song.durationSec.takeIf { it > 0 } ?: 210) * 1000L
            if (clockMillis < end) return song.imageUrl ?: playlist.imageUrl
            start = end
        }
    }
    return tracks[playlist.uri]?.tracks?.firstOrNull()?.imageUrl ?: playlist.imageUrl
}

/** The Music channel a zone is playing right now, if it's among [channels]. */
fun playingMusicChannelId(
    channels: List<EnrichedChannel>,
    playlists: List<com.arflix.tv.music.MaMediaItem>,
    lineups: List<com.arflix.tv.music.MaLineup>,
): String? = lineups.firstNotNullOfOrNull { lineup ->
    val playlist = playlists.firstOrNull { it.uri == lineup.sourceUri } ?: return@firstNotNullOfOrNull null
    musicChannelId(playlist).takeIf { id -> channels.any { it.id == id } }
}

/** The Music group gets a zoomed-in time scale so songs read as cells, not slivers. */
fun musicGridZoom(channels: List<EnrichedChannel>): Float =
    if (channels.isNotEmpty() && channels.all { isMusicChannelId(it.id) }) 8f else 1f

/** Fetches playlist lineups once, and re-reads the playing zones' queues on every guide tick. */
@Composable
private fun MusicGuideFeeds(viewModel: TvViewModel, playlists: List<com.arflix.tv.music.MaMediaItem>, clockMillis: Long) {
    if (playlists.isEmpty()) return
    LaunchedEffect(playlists) { viewModel.loadMusicTracks(playlists) }
    LaunchedEffect(clockMillis) { viewModel.refreshMusicLineups() }
}

fun List<com.arflix.tv.music.MaMediaItem>.musicItemFor(channelId: String?): com.arflix.tv.music.MaMediaItem? =
    if (!isMusicChannelId(channelId)) null else firstOrNull { musicChannelId(it) == channelId }

private const val MovieChannelIdPrefix = "movie:"
private const val PremiereChannelIdPrefix = "movieprem:"

fun isLibraryChannelId(id: String?): Boolean =
    id != null && (id.startsWith(ShowsChannelIdPrefix) || id.startsWith(MovieChannelIdPrefix) ||
        id.startsWith(PremiereChannelIdPrefix) || id.startsWith(MusicChannelIdPrefix))

private fun movieTitle(title: String, year: Int?) = if (year != null) "$title ($year)" else title

// One channel per movie (Joe, 2026-09-30: "every movie is a channel is better easier"):
// downloaded ones first (unwatched before watched, as Episeerr orders them), then the ones
// Radarr is still waiting on.
fun com.arflix.tv.data.repository.MovieGuide.toIptvChannels(): List<IptvChannel> =
    movies.map { IptvChannel(id = "$MovieChannelIdPrefix${it.tmdbId}", name = movieTitle(it.title, it.year), streamUrl = "", group = MoviesChannelGroup) } +
        premiering.map { IptvChannel(id = "$PremiereChannelIdPrefix${it.tmdbId}", name = movieTitle(it.title, it.year), streamUrl = "", group = MoviesChannelGroup) }

// Each movie is a single cell spanning "now", so it reads as one block like a show's NOW episode.
fun com.arflix.tv.data.repository.MovieGuide.toIptvNowNext(clockMillis: Long): Map<String, IptvNowNext> = buildMap {
    val hour = 60 * 60 * 1000L
    movies.forEach { m ->
        val runtimeMs = (m.runtimeMinutes.takeIf { it > 0 } ?: 120) * 60_000L
        put("$MovieChannelIdPrefix${m.tmdbId}", IptvNowNext(now = IptvProgram(
            title = if (m.watched) "${movieTitle(m.title, m.year)} · Watched" else movieTitle(m.title, m.year),
            description = m.overview.ifBlank { null },
            startUtcMillis = clockMillis - hour,
            endUtcMillis = clockMillis + runtimeMs,
        )))
    }
    premiering.forEach { p ->
        val date = runCatching { java.time.LocalDate.parse(p.releaseDate) }.getOrNull()
        val label = when {
            date == null -> "Not downloaded"
            date.isAfter(java.time.LocalDate.now()) -> "Premieres ${date.format(java.time.format.DateTimeFormatter.ofPattern("M/d"))}"
            else -> "Released ${date.format(java.time.format.DateTimeFormatter.ofPattern("M/d"))} · Not downloaded"
        }
        put("$PremiereChannelIdPrefix${p.tmdbId}", IptvNowNext(now = IptvProgram(
            title = label,
            description = p.overview.ifBlank { null },
            startUtcMillis = clockMillis - hour,
            endUtcMillis = clockMillis + 3 * hour,
        )))
    }
}

fun com.arflix.tv.data.repository.ShowGuideEntry.toIptvChannel(): IptvChannel = IptvChannel(
    id = "$ShowsChannelIdPrefix$seriesId",
    name = title,
    streamUrl = "",
    group = ShowsChannelGroup,
)

private fun episodeProgramTitle(showTitle: String, season: Int, episode: Int, suffix: String? = null): String {
    val base = "S${season}E$episode"
    return if (suffix != null) "$base · $suffix" else "$base · $showTitle"
}

private val ShowEpisodeTitleRegex = Regex("""^S(\d+)E(\d+)""")

/** Inverse of [episodeProgramTitle]'s "S{season}E{episode} · ..." prefix. */
private fun parseShowEpisodeTitle(title: String): Pair<Int, Int>? =
    ShowEpisodeTitleRegex.find(title)?.let { m ->
        val season = m.groupValues[1].toIntOrNull() ?: return null
        val episode = m.groupValues[2].toIntOrNull() ?: return null
        season to episode
    }

/**
 * One synthetic [IptvNowNext] per show. `now` is the show's own title (real, ready episode) or
 * its most recently watched one (caught up — see the `lastPlayed` fallback in
 * ShowGuideEntry/xadarr.py's guide-schedule endpoint). `next` mirrors the real EPG's date-text
 * convention ("NEXT 18:00 KTLA 5 News at 6") for the not-yet-downloaded case, just with a date
 * instead of a time. Both start/end are placeholder windows — [EpgGrid]'s existing gap-filler
 * (see plan) covers everything between real entries, this only needs to anchor them in time.
 */
fun com.arflix.tv.data.repository.ShowGuideEntry.toIptvNowNext(clockMillis: Long): IptvNowNext {
    val hour = 60 * 60 * 1000L
    // Real per-episode title (Sonarr's own episode name, e.g. "Fishes"), not the literal word
    // "Last watched" -- Joe, 2026-09-29 screenshot: "i also see last watched on some nit the ep
    // title". Falls back to the show's own title only if Sonarr has no episode title on file.
    val nowProgram = now?.let {
        IptvProgram(
            title = episodeProgramTitle(title, it.season, it.episode, suffix = it.title.ifBlank { null }),
            // The episode's own synopsis (Joe: more useful than the show's), show's as fallback.
            description = it.overview.ifBlank { overview }.ifBlank { null },
            startUtcMillis = clockMillis - hour,
            endUtcMillis = clockMillis + hour,
        )
    } ?: lastPlayed?.let {
        IptvProgram(
            title = episodeProgramTitle(title, it.season, it.episode, suffix = listOfNotNull(it.title.ifBlank { null }, "Watched").joinToString(" · ")),
            description = it.overview.ifBlank { overview }.ifBlank { null },
            startUtcMillis = clockMillis - hour,
            endUtcMillis = clockMillis + hour,
        )
    }
    val nextProgram = next?.let { n ->
        // Downloaded: the episode title, like NOW. Not downloaded: say so, with the air date --
        // selecting it searches Sonarr.
        val suffix = if (n.downloaded) {
            n.title.ifBlank { null }
        } else {
            val aired = runCatching { java.time.Instant.parse(n.airDate) }.getOrNull()
            val date = aired?.let {
                java.time.format.DateTimeFormatter.ofPattern("M/d").withZone(java.time.ZoneId.systemDefault()).format(it)
            }
            when {
                aired == null -> "Not downloaded"
                aired.toEpochMilli() > clockMillis -> "Airs $date"
                else -> "Not downloaded"
            }
        }
        IptvProgram(
            title = episodeProgramTitle(title, n.season, n.episode, suffix = suffix),
            description = n.overview.ifBlank { null },
            startUtcMillis = clockMillis + hour,
            endUtcMillis = clockMillis + 2 * hour,
        )
    }
    return IptvNowNext(now = nowProgram, next = nextProgram)
}

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
