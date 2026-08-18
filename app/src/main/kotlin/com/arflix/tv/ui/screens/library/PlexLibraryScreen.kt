package com.arflix.tv.ui.screens.library

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.DisposableEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.EpiseerrRepository
import com.arflix.tv.data.repository.HomeServerRepository
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.PlexLibraryItem
import com.arflix.tv.data.repository.SonarrRepository
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.ui.screens.tv.live.LiveColors
import com.arflix.tv.ui.screens.tv.live.LiveDims
import com.arflix.tv.ui.screens.tv.live.LiveType
import com.arflix.tv.ui.skin.XadarrSkin
import com.arflix.tv.util.LocalNeolinkConfigured
import com.arflix.tv.util.PlexDeepLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

data class PlexLibraryUiState(
    val items: List<PlexLibraryItem> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val continueWatching: List<ContinueWatchingCard> = emptyList(),
    val newEpisodes: List<NewEpisodeCard> = emptyList(),
    val premiering: List<PremieringCard> = emptyList(),
)

// Real cross-service watch position (Episeerr, fed by Tautulli/Jellyfin), matched to a live
// Plex item by title for poster art + the deep-link target. Deliberately just "where you left
// off" (S{season} E{episode}), not a computed "next episode" — that would need full per-show
// episode-list data this doesn't have.
data class ContinueWatchingCard(
    val plexItem: PlexLibraryItem,
    val season: Int?,
    val episode: Int?,
)

// Upcoming episode from Sonarr's calendar, matched to a live Plex item the same way.
data class PremieringCard(
    val plexItem: PlexLibraryItem,
    val season: Int,
    val episode: Int,
    val episodeTitle: String,
    val airDate: String,
)

// A series with an episode Sonarr imported recently — the gap between Continue Watching
// (needs watch activity) and Premiering Soon (future air dates only). An already-aired,
// already-downloaded episode you haven't watched yet has no other row that ever surfaces
// it, so it just silently sits in the alphabetical/lastViewedAt-sorted grid until you
// happen to scroll to it. See lastEpisodeAddedEpochMs on SonarrSeriesSummary.
data class NewEpisodeCard(
    val plexItem: PlexLibraryItem,
    val addedEpochMs: Long,
)

private const val PAGE_SIZE = 60

// How far back "New Episodes" looks for a Sonarr import — long enough to survive a few
// days of not opening the app, short enough that it clears out once you've caught up.
private const val NEW_EPISODES_WINDOW_MS = 21L * 24 * 60 * 60 * 1000

// Larger than PAGE_SIZE deliberately — Continue Watching/Premiering need to match against
// more of the library than what's currently scrolled into the grid (which loads PAGE_SIZE at
// a time sorted by addedAt; a show watched or airing soon may not be in the first page).
private const val MATCH_POOL_LIMIT = 300

// Kept small — this pool's whole point is "sorted so fresh imports are near the top",
// so it doesn't need to be large to be useful.
private const val FRESH_MATCH_POOL_LIMIT = 100

private fun normalizeTitle(s: String): String =
    s.lowercase().trim().replace(Regex("[^a-z0-9]+"), " ").trim()

@HiltViewModel
class PlexLibraryViewModel @Inject constructor(
    private val homeServerRepository: HomeServerRepository,
    private val episeerrRepository: EpiseerrRepository,
    private val sonarrRepository: SonarrRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlexLibraryUiState())
    val uiState: StateFlow<PlexLibraryUiState> = _uiState.asStateFlow()

    private var loadedMediaType: MediaType? = null

    fun load(mediaType: MediaType) {
        if (loadedMediaType == mediaType && _uiState.value.items.isNotEmpty()) return
        loadedMediaType = mediaType
        viewModelScope.launch {
            _uiState.value = PlexLibraryUiState(isLoading = true)
            val page = homeServerRepository.loadPlexLibraryItems(mediaType, offset = 0, limit = PAGE_SIZE)
            _uiState.value = if (page.items.isEmpty()) {
                PlexLibraryUiState(
                    isLoading = false,
                    error = "No Plex movies/shows found. Check your Plex connection in Settings.",
                )
            } else {
                PlexLibraryUiState(items = page.items, isLoading = false, hasMore = page.hasMore)
            }
            if (mediaType == MediaType.TV && page.items.isNotEmpty()) {
                loadContinueWatchingAndPremiering()
            }
        }
    }

    private suspend fun loadContinueWatchingAndPremiering() {
        val pool = runCatching {
            homeServerRepository.loadPlexLibraryItems(MediaType.TV, offset = 0, limit = MATCH_POOL_LIMIT).items
        }.getOrDefault(emptyList())
        // Second pool sorted purely by episode-add date, not the default's lastViewedAt-first
        // order — a show with a brand new unwatched episode (exactly what New Episodes/
        // Premiering exist to find) can rank far outside `pool`'s cap under that watch-biased
        // sort on a large library, so `match()` would silently drop it. This pool guarantees
        // recently-imported shows are near the top regardless of watch history.
        val freshPool = runCatching {
            homeServerRepository.loadPlexLibraryItems(
                MediaType.TV, offset = 0, limit = FRESH_MATCH_POOL_LIMIT, sortOverride = "episode.addedAt:desc",
            ).items
        }.getOrDefault(emptyList())
        if (pool.isEmpty() && freshPool.isEmpty()) return
        val byNormalizedTitle = (pool + freshPool).distinctBy { it.ratingKey }.groupBy { normalizeTitle(it.title) }
        fun match(title: String): PlexLibraryItem? = byNormalizedTitle[normalizeTitle(title)]?.firstOrNull()

        val recentlyWatched = runCatching { episeerrRepository.getRecentlyWatched() }.getOrDefault(emptyList())
        val continueWatching = recentlyWatched.mapNotNull { w ->
            match(w.seriesTitle)?.let { ContinueWatchingCard(it, w.season, w.episode) }
        }
        val continueWatchingTitles = continueWatching.mapTo(mutableSetOf()) { normalizeTitle(it.plexItem.title) }

        val cutoff = System.currentTimeMillis() - NEW_EPISODES_WINDOW_MS
        val allSeries = runCatching { sonarrRepository.getAllSeries() }.getOrDefault(emptyList())
        val newEpisodes = allSeries
            .filter { (it.lastEpisodeAddedEpochMs ?: 0L) >= cutoff }
            .sortedByDescending { it.lastEpisodeAddedEpochMs }
            .mapNotNull { s ->
                match(s.title)
                    ?.takeIf { normalizeTitle(it.title) !in continueWatchingTitles }
                    ?.let { NewEpisodeCard(it, s.lastEpisodeAddedEpochMs!!) }
            }

        val calendar = runCatching { sonarrRepository.getCalendar(daysAhead = 30) }.getOrDefault(emptyList())
        val premiering = calendar
            .groupBy { it.seriesId }
            .mapNotNull { (_, entries) -> entries.minByOrNull { it.airDate } }
            .sortedBy { it.airDate }
            .mapNotNull { e -> match(e.title)?.let { PremieringCard(it, e.season, e.episode, e.episodeTitle, e.airDate) } }

        _uiState.value = _uiState.value.copy(
            continueWatching = continueWatching,
            newEpisodes = newEpisodes,
            premiering = premiering,
        )
    }

    // Plex library items only carry a ratingKey (Plex-native identity), no TMDB id — resolve
    // one by title/year so a tap can route into the shared TMDB-backed Details screen instead
    // of jumping straight to Plex. Details' own eager Plex-handoff matching (DetailsViewModel.
    // applyPlexHandoffIfMatched) then re-matches this same item by normalized title and shows
    // the simplified "Open in Plex" view — no separate Plex-aware Details variant needed.
    suspend fun resolveTmdbId(item: PlexLibraryItem, mediaType: MediaType): Int? =
        mediaRepository.resolveTmdbId(item.title, item.year, mediaType)

    fun loadMore() {
        val mediaType = loadedMediaType ?: return
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        viewModelScope.launch {
            _uiState.value = state.copy(isLoadingMore = true)
            val page = homeServerRepository.loadPlexLibraryItems(mediaType, offset = state.items.size, limit = PAGE_SIZE)
            _uiState.value = _uiState.value.copy(
                items = _uiState.value.items + page.items,
                isLoadingMore = false,
                hasMore = page.hasMore,
            )
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

private enum class LibraryFocusZone { CONTINUE_WATCHING, NEW_EPISODES, PREMIERING, GRID }

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlexLibraryScreen(
    mediaType: MediaType,
    viewModel: PlexLibraryViewModel = hiltViewModel(),
    liveTvPlayerViewModel: com.arflix.tv.ui.screens.tv.live.LiveTvPlayerViewModel? = null,
    currentProfile: Profile? = null,
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToDiscover: () -> Unit = {},
    onNavigateToTv: () -> Unit = {},
    onNavigateToCameras: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAllApps: () -> Unit = {},
    onNavigateToMovies: () -> Unit = {},
    onNavigateToShows: () -> Unit = {},
    onNavigateToDetails: (MediaType, Int) -> Unit = { _, _ -> },
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    LaunchedEffect(mediaType) { viewModel.load(mediaType) }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val items = uiState.items
    val continueWatching = uiState.continueWatching
    val newEpisodes = uiState.newEpisodes
    val premiering = uiState.premiering

    // Fixed 6 columns reads fine on a ~1920px-wide TV but crushes posters to ~40dp on a
    // ~400dp-wide phone — size to device type instead, same LocalDeviceType/isTouchDevice
    // pattern CamerasScreen already uses.
    val deviceType = com.arflix.tv.util.LocalDeviceType.current
    val isTouchDevice = deviceType.isTouchDevice()
    val colCount = when {
        !isTouchDevice -> 6
        deviceType == com.arflix.tv.util.DeviceType.TABLET -> 4
        else -> 3
    }
    val gridHPad = if (isTouchDevice) 16.dp else 32.dp
    val gridGap = if (isTouchDevice) 10.dp else 14.dp
    // Continue Watching/New Episodes/Premiering cards are deliberately smaller than the main
    // grid's — the outer Column (rows + grid) isn't scrollable, grid gets whatever's left via
    // weight(1f), and at full poster size two rows alone could eat the entire remaining screen
    // height on a TV, squeezing the grid to nothing ("the grid flashes in then vanishes" — not a
    // focus/scroll bug). Shrunk further (110→88dp) 2026-08-18 when a third row (New Episodes)
    // joined Continue Watching/Premiering and a hero banner landed above all of them the same
    // day — the previous size was tuned for two rows with no hero eating space first, and on a
    // real device it clipped mid-card rather than fitting even one full row. See
    // ROW_BLOCK_HEIGHT below — the container height is now sized off this constant instead of
    // an arbitrary screen-height fraction, specifically so it can't clip a row's content.
    val rowCardWidth = if (isTouchDevice) 68.dp else 88.dp
    // Real, derived-from-geometry row height — poster (2:3 aspect) + caption strip + this row's
    // own label/top-padding (see PlexPosterCard/LibraryRow below) — instead of a screen-height
    // fraction that doesn't actually guarantee a whole row fits. The +84dp buffer covers the
    // taller 2-line caption a focused card grows to, so the currently-focused row is never
    // itself clipped either.
    val rowBlockHeight = rowCardWidth * 1.5f + 84.dp

    val neolinkConfigured = LocalNeolinkConfigured.current
    val navSections = com.arflix.tv.util.LocalNavSections.current
    val isNavRailOpen = com.arflix.tv.ui.components.rememberNavRailOpen()
    val navRailFocusedIndex = remember { mutableStateOf(0) }
    LaunchedEffect(isNavRailOpen.value) {
        if (isNavRailOpen.value) navRailFocusedIndex.value = 0
    }

    // The grid is the primary view — Continue Watching/Premiering load in shortly after and used
    // to steal focus (and the scroll position with it) up to themselves the moment they arrived,
    // which looked like the full grid flashing in then immediately vanishing behind those two
    // rows. Focus now stays on the grid unless the user explicitly navigates up to them.
    var focusZone by remember { mutableStateOf(LibraryFocusZone.GRID) }
    var gridFocusedIndex by remember { mutableIntStateOf(0) }
    var cwFocusedIndex by remember { mutableIntStateOf(0) }
    var newEpFocusedIndex by remember { mutableIntStateOf(0) }
    var premFocusedIndex by remember { mutableIntStateOf(0) }

    val gridState = rememberLazyGridState()
    val cwListState = rememberLazyListState()
    val newEpListState = rememberLazyListState()
    val premListState = rememberLazyListState()
    val rootFocusRequester = remember { FocusRequester() }

    // The CW/New Episodes/Premiering block is capped to a fraction of screen height and
    // scrollable, but this is a TV D-pad screen — there's no touch gesture to scroll it, and
    // nothing was driving that scroll at all, so a row pushed below the cap (e.g. Premiering
    // when Continue Watching + New Episodes already fill the visible area) was focusable but
    // permanently invisible with no way to reach it. Each row asks to be scrolled into view
    // when its zone gains focus, same idea as gridFocusedIndex's own animateScrollToItem below.
    val cwBringIntoView = remember { BringIntoViewRequester() }
    val newEpBringIntoView = remember { BringIntoViewRequester() }
    val premBringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focusZone) {
        when (focusZone) {
            LibraryFocusZone.CONTINUE_WATCHING -> runCatching { cwBringIntoView.bringIntoView() }
            LibraryFocusZone.NEW_EPISODES -> runCatching { newEpBringIntoView.bringIntoView() }
            LibraryFocusZone.PREMIERING -> runCatching { premBringIntoView.bringIntoView() }
            LibraryFocusZone.GRID -> Unit
        }
    }

    // Plex-style hero banner tracks whichever card currently has focus, falling back to the
    // grid's first item — the same "backdrop follows selection" behavior Plex's own TV app uses.
    val heroItem = when (focusZone) {
        LibraryFocusZone.CONTINUE_WATCHING -> continueWatching.getOrNull(cwFocusedIndex)?.plexItem
        LibraryFocusZone.NEW_EPISODES -> newEpisodes.getOrNull(newEpFocusedIndex)?.plexItem
        LibraryFocusZone.PREMIERING -> premiering.getOrNull(premFocusedIndex)?.plexItem
        LibraryFocusZone.GRID -> items.getOrNull(gridFocusedIndex)
    } ?: items.firstOrNull()

    LaunchedEffect(Unit) {
        runCatching { rootFocusRequester.requestFocus() }
    }
    // Launching Plex (activateItem()) hands off the window without leaving this composable —
    // Compose focus is dropped on the way out and never restored on return, so the D-pad goes
    // dead even though the last-focused card still shows its highlight (styling, not real
    // focus). Same root cause and fix as LiveTvScreen.kt's ON_RESUME handler.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                runCatching { rootFocusRequester.requestFocus() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(gridFocusedIndex) {
        runCatching { gridState.animateScrollToItem(gridFocusedIndex) }
        // Prefetch the next page once focus gets within two rows of the end.
        if (items.isNotEmpty() && gridFocusedIndex >= items.size - colCount * 2) {
            viewModel.loadMore()
        }
    }
    LaunchedEffect(cwFocusedIndex) { runCatching { cwListState.animateScrollToItem(cwFocusedIndex) } }
    LaunchedEffect(newEpFocusedIndex) { runCatching { newEpListState.animateScrollToItem(newEpFocusedIndex) } }
    LaunchedEffect(premFocusedIndex) { runCatching { premListState.animateScrollToItem(premFocusedIndex) } }

    fun activateItem(item: PlexLibraryItem) {
        val intent = PlexDeepLink.launchIntent(context, item.plexServerId, item.ratingKey)
        if (intent != null) {
            // Pause Xadarr's own live TV player before handing off — otherwise it keeps playing
            // audio in the background while Plex is in the foreground. Same pattern as the
            // TiviMate handoff in AppNavigation.kt (pauseForVod() before startActivity()).
            liveTvPlayerViewModel?.pauseForVod()
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "Plex isn't installed on this device", Toast.LENGTH_SHORT).show()
        }
    }

    // Selecting a title now lands on Details (info, progress, season count) with an "Open in
    // Plex" button, instead of jumping straight into Plex — gives a chance to see what it is
    // before committing to the handoff. Falls back to the old direct-launch if no TMDB match is
    // found (rare — better than a dead tap).
    fun openItem(item: PlexLibraryItem) {
        coroutineScope.launch {
            val tmdbId = viewModel.resolveTmdbId(item, mediaType)
            if (tmdbId != null) {
                onNavigateToDetails(mediaType, tmdbId)
            } else {
                activateItem(item)
            }
        }
    }

    val actions = com.arflix.tv.ui.components.NavRailActions(
        onNavigateToHome = onNavigateToHome,
        onNavigateToSearch = onNavigateToSearch,
        onNavigateToDiscover = onNavigateToDiscover,
        onNavigateToTv = onNavigateToTv,
        onNavigateToCameras = onNavigateToCameras,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToAllApps = onNavigateToAllApps,
        onNavigateToMovies = onNavigateToMovies,
        onNavigateToShows = onNavigateToShows,
    )

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveColors.Bg)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (isNavRailOpen.value) {
                    val railEntries = com.arflix.tv.ui.components.computeNavRailEntries(
                        currentScreen = null,
                        navSections = navSections,
                        neolinkConfigured = neolinkConfigured,
                    )
                    com.arflix.tv.ui.components.navRailHandleKey(
                        event = event,
                        entries = railEntries,
                        focusedIndex = navRailFocusedIndex,
                        onClose = { isNavRailOpen.value = false },
                        context = context,
                        actions = actions,
                    )
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.Back, Key.Escape -> { onBack(); true }
                    Key.DirectionUp -> {
                        when (focusZone) {
                            LibraryFocusZone.CONTINUE_WATCHING -> Unit
                            LibraryFocusZone.NEW_EPISODES -> {
                                if (continueWatching.isNotEmpty()) focusZone = LibraryFocusZone.CONTINUE_WATCHING
                            }
                            LibraryFocusZone.PREMIERING -> {
                                focusZone = when {
                                    newEpisodes.isNotEmpty() -> LibraryFocusZone.NEW_EPISODES
                                    continueWatching.isNotEmpty() -> LibraryFocusZone.CONTINUE_WATCHING
                                    else -> LibraryFocusZone.PREMIERING
                                }
                            }
                            LibraryFocusZone.GRID -> {
                                if (gridFocusedIndex < colCount) {
                                    focusZone = when {
                                        premiering.isNotEmpty() -> LibraryFocusZone.PREMIERING
                                        newEpisodes.isNotEmpty() -> LibraryFocusZone.NEW_EPISODES
                                        continueWatching.isNotEmpty() -> LibraryFocusZone.CONTINUE_WATCHING
                                        else -> LibraryFocusZone.GRID
                                    }
                                } else {
                                    gridFocusedIndex -= colCount
                                }
                            }
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        when (focusZone) {
                            LibraryFocusZone.CONTINUE_WATCHING -> {
                                focusZone = when {
                                    newEpisodes.isNotEmpty() -> LibraryFocusZone.NEW_EPISODES
                                    premiering.isNotEmpty() -> LibraryFocusZone.PREMIERING
                                    else -> LibraryFocusZone.GRID
                                }
                                true
                            }
                            LibraryFocusZone.NEW_EPISODES -> {
                                focusZone = if (premiering.isNotEmpty()) LibraryFocusZone.PREMIERING else LibraryFocusZone.GRID
                                true
                            }
                            LibraryFocusZone.PREMIERING -> { focusZone = LibraryFocusZone.GRID; true }
                            LibraryFocusZone.GRID -> {
                                if (gridFocusedIndex + colCount < items.size) { gridFocusedIndex += colCount; true } else false
                            }
                        }
                    }
                    Key.DirectionLeft -> when (focusZone) {
                        LibraryFocusZone.CONTINUE_WATCHING -> {
                            if (cwFocusedIndex > 0) cwFocusedIndex-- else isNavRailOpen.value = true
                            true
                        }
                        LibraryFocusZone.NEW_EPISODES -> {
                            if (newEpFocusedIndex > 0) newEpFocusedIndex-- else isNavRailOpen.value = true
                            true
                        }
                        LibraryFocusZone.PREMIERING -> {
                            if (premFocusedIndex > 0) premFocusedIndex-- else isNavRailOpen.value = true
                            true
                        }
                        LibraryFocusZone.GRID -> when {
                            gridFocusedIndex % colCount > 0 -> { gridFocusedIndex--; true }
                            else -> { isNavRailOpen.value = true; true }
                        }
                    }
                    Key.DirectionRight -> when (focusZone) {
                        LibraryFocusZone.CONTINUE_WATCHING -> {
                            if (cwFocusedIndex < continueWatching.size - 1) { cwFocusedIndex++; true } else false
                        }
                        LibraryFocusZone.NEW_EPISODES -> {
                            if (newEpFocusedIndex < newEpisodes.size - 1) { newEpFocusedIndex++; true } else false
                        }
                        LibraryFocusZone.PREMIERING -> {
                            if (premFocusedIndex < premiering.size - 1) { premFocusedIndex++; true } else false
                        }
                        LibraryFocusZone.GRID -> {
                            if (gridFocusedIndex % colCount < colCount - 1 && gridFocusedIndex + 1 < items.size) {
                                gridFocusedIndex++; true
                            } else false
                        }
                    }
                    Key.Enter, Key.DirectionCenter -> {
                        when (focusZone) {
                            LibraryFocusZone.CONTINUE_WATCHING -> continueWatching.getOrNull(cwFocusedIndex)?.let { openItem(it.plexItem) }
                            LibraryFocusZone.NEW_EPISODES -> newEpisodes.getOrNull(newEpFocusedIndex)?.let { openItem(it.plexItem) }
                            LibraryFocusZone.PREMIERING -> premiering.getOrNull(premFocusedIndex)?.let { openItem(it.plexItem) }
                            LibraryFocusZone.GRID -> items.getOrNull(gridFocusedIndex)?.let { openItem(it) }
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(top = AppTopBarContentTopInset)) {
            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        CircularProgressIndicator(color = LiveColors.Accent)
                    }
                }
                uiState.error != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(top = 32.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Outlined.Movie, null, tint = LiveColors.FgMute, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(uiState.error!!, color = LiveColors.FgDim, style = LiveType.BodySynopsis)
                    }
                }
                else -> {
                    val activeRowCount = listOf(continueWatching, newEpisodes, premiering).count { it.isNotEmpty() }
                    // Round 8's fix (rowBlockHeight * activeRowCount.coerceAtMost(2)) assumed the
                    // screen always had enough leftover height for 2 full rows above the grid. On
                    // this device (1080p @ 320dpi -> 540dp tall), topInset(98) + hero(160) alone
                    // already consume 258dp, leaving well under 2 rows' worth (432dp) once cards
                    // load in — the header block still rendered at its full natural/capped size
                    // regardless (Column doesn't shrink non-weighted siblings to make room), so the
                    // grid's weight(1f) share was squeezed to 0dp: invisible, along with whatever
                    // card had focus in it. Measuring real available space via BoxWithConstraints
                    // and reserving a hard floor for the grid fixes this generally instead of
                    // re-tuning constants for one more device.
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val cellWidth = (maxWidth - gridHPad * 2 - gridGap * (colCount - 1).coerceAtLeast(0)) / colCount
                        val minGridHeight = (cellWidth * 1.05f).coerceIn(140.dp, 220.dp)
                        val compactHero = activeRowCount > 0
                        val heroHeight = if (isTouchDevice) 110.dp else if (compactHero) 130.dp else 160.dp
                        val headerBudget = (maxHeight - heroHeight - minGridHeight).coerceAtLeast(0.dp)
                        val headerMaxHeight = minOf(headerBudget, rowBlockHeight * activeRowCount.coerceAtMost(3))
                        Column(modifier = Modifier.fillMaxSize()) {
                            PlexHeroBanner(item = heroItem, isTouchDevice = isTouchDevice, heroHeight = heroHeight)
                            if (activeRowCount > 0 && headerMaxHeight > 0.dp) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = headerMaxHeight)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    if (continueWatching.isNotEmpty()) {
                                        LibraryRow(
                                            title = "Continue Watching",
                                            hPad = gridHPad,
                                            listState = cwListState,
                                            modifier = Modifier.bringIntoViewRequester(cwBringIntoView),
                                        ) {
                                            itemsIndexed(continueWatching, key = { _, c -> "cw:${c.plexItem.ratingKey}" }) { index, card ->
                                                PlexPosterCard(
                                                    item = card.plexItem,
                                                    isFocused = focusZone == LibraryFocusZone.CONTINUE_WATCHING && cwFocusedIndex == index,
                                                    subtitleOverride = listOfNotNull(
                                                        card.season?.let { "S$it" },
                                                        card.episode?.let { "E$it" },
                                                    ).joinToString(" · ").ifBlank { null },
                                                    modifier = Modifier.width(rowCardWidth),
                                                    onClick = {
                                                        if (focusZone == LibraryFocusZone.CONTINUE_WATCHING && cwFocusedIndex == index) {
                                                            openItem(card.plexItem)
                                                        } else {
                                                            focusZone = LibraryFocusZone.CONTINUE_WATCHING
                                                            cwFocusedIndex = index
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    if (newEpisodes.isNotEmpty()) {
                                        LibraryRow(
                                            title = "New Episodes",
                                            hPad = gridHPad,
                                            listState = newEpListState,
                                            modifier = Modifier.bringIntoViewRequester(newEpBringIntoView),
                                        ) {
                                            itemsIndexed(newEpisodes, key = { _, c -> "newep:${c.plexItem.ratingKey}" }) { index, card ->
                                                PlexPosterCard(
                                                    item = card.plexItem,
                                                    isFocused = focusZone == LibraryFocusZone.NEW_EPISODES && newEpFocusedIndex == index,
                                                    modifier = Modifier.width(rowCardWidth),
                                                    onClick = {
                                                        if (focusZone == LibraryFocusZone.NEW_EPISODES && newEpFocusedIndex == index) {
                                                            openItem(card.plexItem)
                                                        } else {
                                                            focusZone = LibraryFocusZone.NEW_EPISODES
                                                            newEpFocusedIndex = index
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    if (premiering.isNotEmpty()) {
                                        LibraryRow(
                                            title = "Premiering Soon",
                                            hPad = gridHPad,
                                            listState = premListState,
                                            modifier = Modifier.bringIntoViewRequester(premBringIntoView),
                                        ) {
                                            itemsIndexed(premiering, key = { _, p -> "prem:${p.plexItem.ratingKey}:${p.season}:${p.episode}" }) { index, card ->
                                                PlexPosterCard(
                                                    item = card.plexItem,
                                                    isFocused = focusZone == LibraryFocusZone.PREMIERING && premFocusedIndex == index,
                                                    subtitleOverride = "S${card.season} · E${card.episode}",
                                                    modifier = Modifier.width(rowCardWidth),
                                                    onClick = {
                                                        if (focusZone == LibraryFocusZone.PREMIERING && premFocusedIndex == index) {
                                                            openItem(card.plexItem)
                                                        } else {
                                                            focusZone = LibraryFocusZone.PREMIERING
                                                            premFocusedIndex = index
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(colCount),
                            state = gridState,
                            contentPadding = PaddingValues(horizontal = gridHPad, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(gridGap),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        ) {
                            itemsIndexed(items, key = { _, item -> item.ratingKey }) { index, item ->
                                PlexPosterCard(
                                    item = item,
                                    isFocused = focusZone == LibraryFocusZone.GRID && gridFocusedIndex == index,
                                    onClick = {
                                        if (focusZone == LibraryFocusZone.GRID && gridFocusedIndex == index) {
                                            openItem(item)
                                        } else {
                                            focusZone = LibraryFocusZone.GRID
                                            gridFocusedIndex = index
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        com.arflix.tv.ui.components.MinimalTopChrome(profile = currentProfile)

        Box(modifier = Modifier.zIndex(10f)) {
            com.arflix.tv.ui.components.NavRail(
                isOpen = isNavRailOpen.value,
                onClose = {
                    isNavRailOpen.value = false
                    runCatching { rootFocusRequester.requestFocus() }
                },
                currentScreen = null,
                navSections = navSections,
                neolinkConfigured = neolinkConfigured,
                currentProfile = currentProfile,
                actions = actions,
                focusedIndex = navRailFocusedIndex.value,
            )
        }
    }
}

// ── Hero banner (Plex-style: backdrop follows focus, fades into the page bg) ──

@Composable
private fun PlexHeroBanner(item: PlexLibraryItem?, isTouchDevice: Boolean, heroHeight: Dp) {
    // heroHeight is now computed by the caller from real available space (BoxWithConstraints in
    // PlexLibraryScreen) instead of a static constant — see the comment there for why.
    Box(modifier = Modifier.fillMaxWidth().height(heroHeight)) {
        val art = item?.artUrl ?: item?.posterUrl
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(LiveColors.PanelDeep))
        }
        // Left-to-right + bottom fades, both landing on the exact page background color so the
        // banner blends seamlessly into the rows/grid below rather than reading as a hard-edged
        // photo tile — matches how Plex's own hero never looks like a pasted-in image.
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(LiveColors.Bg, LiveColors.Bg.copy(alpha = 0.55f), Color.Transparent))
            )
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, LiveColors.Bg), startY = 0f)
            )
        )
        if (item != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 32.dp, vertical = 24.dp)
                    .fillMaxWidth(0.6f),
            ) {
                Text(
                    text = item.title,
                    style = XadarrSkin.typography.heroTitle.copy(fontSize = if (isTouchDevice) 22.sp else 28.sp),
                    color = LiveColors.Fg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.year?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(text = it.toString(), style = LiveType.BodySynopsis, color = LiveColors.FgDim)
                }
            }
        }
    }
}

// ── Shared horizontal row shell (Continue Watching / Premiering) ─────────────

@Composable
private fun LibraryRow(
    title: String,
    hPad: Dp,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier = modifier.padding(top = 10.dp)) {
        Text(
            text = title,
            color = LiveColors.Fg,
            style = LiveType.SectionTag,
            modifier = Modifier.padding(horizontal = hPad, vertical = 4.dp),
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = hPad),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

// ── Poster card ────────────────────────────────────────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlexPosterCard(
    item: PlexLibraryItem,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    subtitleOverride: String? = null,
    onClick: () -> Unit,
) {
    val borderColor = if (isFocused) LiveColors.FocusRing else LiveColors.Divider

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(LiveDims.CardRadius))
            .border(if (isFocused) LiveDims.FocusBorder else 1.dp, borderColor, RoundedCornerShape(LiveDims.CardRadius))
            .background(LiveColors.Panel)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(LiveColors.PanelDeep),
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.Movie, null, tint = LiveColors.FgMute,
                    modifier = Modifier.size(40.dp).align(androidx.compose.ui.Alignment.Center),
                )
            }
        }

        // Focused card gets a taller info panel (full title, subtitle line) — this IS the
        // "preview" step: focusing/tapping once shows this, selecting again opens Plex.
        // No Plex-native watch-progress badge here — Plex's own per-item counters
        // (viewCount/leafCount) proved unreliable for a library split across Plex+Jellyfin
        // playback with only a partial/sample Plex library; real progress now lives in the
        // Continue Watching / Premiering rows above, backed by Episeerr/Sonarr instead.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LiveColors.RowStripe)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = item.title,
                color = if (isFocused) LiveColors.Fg else LiveColors.FgDim,
                style = LiveType.CatLabel,
                maxLines = if (isFocused) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isFocused && subtitleOverride != null) {
                Text(
                    text = subtitleOverride,
                    color = LiveColors.Accent,
                    style = LiveType.Badge,
                    maxLines = 1,
                )
            }
        }
    }
}
