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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
    // "New Episode" (unwatched Sonarr import) or "S# · E#" (Continue Watching position),
    // shown on the card itself instead of a separate row — see relevance sort below.
    val subtitleByRatingKey: Map<String, String> = emptyMap(),
)

private const val PAGE_SIZE = 60

// How far back "has a new episode" looks for a Sonarr import — long enough to survive a few
// days of not opening the app, short enough that it clears out once you've caught up.
private const val NEW_EPISODES_WINDOW_MS = 21L * 24 * 60 * 60 * 1000

// Larger than PAGE_SIZE deliberately — relevance matching needs more of the library than
// what's currently scrolled into the grid (which loads PAGE_SIZE at a time); a show watched
// or with a new episode may not be in the first page.
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

    // Fetch order (Plex's own lastViewedAt-first sort), kept separate from the displayed,
    // relevance-sorted `uiState.items` so relevance data arriving later (or loadMore
    // appending more pages) can just re-sort this instead of re-fetching.
    private var rawItems: List<PlexLibraryItem> = emptyList()
    private var newEpisodeAddedMsByKey: Map<String, Long> = emptyMap()
    private var watchedRankByKey: Map<String, Int> = emptyMap()
    private var subtitleByRatingKey: Map<String, String> = emptyMap()

    fun load(mediaType: MediaType) {
        if (loadedMediaType == mediaType && _uiState.value.items.isNotEmpty()) return
        loadedMediaType = mediaType
        rawItems = emptyList()
        newEpisodeAddedMsByKey = emptyMap()
        watchedRankByKey = emptyMap()
        subtitleByRatingKey = emptyMap()
        viewModelScope.launch {
            _uiState.value = PlexLibraryUiState(isLoading = true)
            val page = homeServerRepository.loadPlexLibraryItems(mediaType, offset = 0, limit = PAGE_SIZE)
            rawItems = page.items
            _uiState.value = if (page.items.isEmpty()) {
                PlexLibraryUiState(
                    isLoading = false,
                    error = "No Plex movies/shows found. Check your Plex connection in Settings.",
                )
            } else {
                PlexLibraryUiState(items = page.items, isLoading = false, hasMore = page.hasMore)
            }
            if (mediaType == MediaType.TV && page.items.isNotEmpty()) {
                loadRelevanceData()
            }
        }
    }

    // Sorts shows with a ready-to-watch new episode to the top (newest import first), then
    // shows with recent watch history (most-recently-watched first), then leaves everything
    // else in whatever order it already had — a single sorted grid instead of separate
    // Continue Watching / New Episodes / Premiering rows.
    private fun sortForRelevance(list: List<PlexLibraryItem>): List<PlexLibraryItem> =
        list.sortedWith(
            compareByDescending<PlexLibraryItem> { newEpisodeAddedMsByKey[it.ratingKey] ?: -1L }
                .thenBy { watchedRankByKey[it.ratingKey] ?: Int.MAX_VALUE }
        )

    private fun applyRelevance() {
        _uiState.value = _uiState.value.copy(
            items = sortForRelevance(rawItems),
            subtitleByRatingKey = subtitleByRatingKey,
        )
    }

    private suspend fun loadRelevanceData() {
        val pool = runCatching {
            homeServerRepository.loadPlexLibraryItems(MediaType.TV, offset = 0, limit = MATCH_POOL_LIMIT).items
        }.getOrDefault(emptyList())
        // Second pool sorted purely by episode-add date, not the default's lastViewedAt-first
        // order — a show with a brand new unwatched episode can rank far outside `pool`'s cap
        // under that watch-biased sort on a large library, so `match()` would silently drop it.
        val freshPool = runCatching {
            homeServerRepository.loadPlexLibraryItems(
                MediaType.TV, offset = 0, limit = FRESH_MATCH_POOL_LIMIT, sortOverride = "episode.addedAt:desc",
            ).items
        }.getOrDefault(emptyList())
        if (pool.isEmpty() && freshPool.isEmpty()) return
        val byNormalizedTitle = (pool + freshPool).distinctBy { it.ratingKey }.groupBy { normalizeTitle(it.title) }
        fun match(title: String): PlexLibraryItem? = byNormalizedTitle[normalizeTitle(title)]?.firstOrNull()

        val recentlyWatched = runCatching { episeerrRepository.getRecentlyWatched() }.getOrDefault(emptyList())
        val watchedRank = mutableMapOf<String, Int>()
        val watchedSubtitle = mutableMapOf<String, String>()
        recentlyWatched.forEachIndexed { index, w ->
            val item = match(w.seriesTitle) ?: return@forEachIndexed
            watchedRank.putIfAbsent(item.ratingKey, index)
            val subtitle = listOfNotNull(w.season?.let { "S$it" }, w.episode?.let { "E$it" }).joinToString(" · ")
            if (subtitle.isNotBlank()) watchedSubtitle.putIfAbsent(item.ratingKey, subtitle)
        }
        watchedRankByKey = watchedRank

        val cutoff = System.currentTimeMillis() - NEW_EPISODES_WINDOW_MS
        val allSeries = runCatching { sonarrRepository.getAllSeries() }.getOrDefault(emptyList())
        val newEpisodeMs = mutableMapOf<String, Long>()
        val newEpisodeSubtitle = mutableMapOf<String, String>()
        allSeries
            .filter { (it.lastEpisodeAddedEpochMs ?: 0L) >= cutoff }
            .forEach { s ->
                val item = match(s.title) ?: return@forEach
                newEpisodeMs[item.ratingKey] = s.lastEpisodeAddedEpochMs!!
                newEpisodeSubtitle[item.ratingKey] = "New Episode"
            }
        newEpisodeAddedMsByKey = newEpisodeMs

        // New-episode subtitle wins over a stale "where you left off" position for the same show.
        subtitleByRatingKey = watchedSubtitle + newEpisodeSubtitle

        applyRelevance()
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
            val page = homeServerRepository.loadPlexLibraryItems(mediaType, offset = rawItems.size, limit = PAGE_SIZE)
            rawItems = rawItems + page.items
            _uiState.value = _uiState.value.copy(
                items = sortForRelevance(rawItems),
                isLoadingMore = false,
                hasMore = page.hasMore,
            )
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

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

    val neolinkConfigured = LocalNeolinkConfigured.current
    val navSections = com.arflix.tv.util.LocalNavSections.current
    val isNavRailOpen = com.arflix.tv.ui.components.rememberNavRailOpen()
    val navRailFocusedIndex = remember { mutableStateOf(0) }
    LaunchedEffect(isNavRailOpen.value) {
        if (isNavRailOpen.value) navRailFocusedIndex.value = 0
    }

    var gridFocusedIndex by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()
    val rootFocusRequester = remember { FocusRequester() }

    // Plex-style hero banner tracks whichever card currently has focus, falling back to the
    // grid's first item — the same "backdrop follows selection" behavior Plex's own TV app uses.
    val heroItem = items.getOrNull(gridFocusedIndex) ?: items.firstOrNull()

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
                        if (gridFocusedIndex >= colCount) { gridFocusedIndex -= colCount }
                        true
                    }
                    Key.DirectionDown -> {
                        if (gridFocusedIndex + colCount < items.size) { gridFocusedIndex += colCount; true } else false
                    }
                    Key.DirectionLeft -> when {
                        gridFocusedIndex % colCount > 0 -> { gridFocusedIndex--; true }
                        else -> { isNavRailOpen.value = true; true }
                    }
                    Key.DirectionRight -> {
                        if (gridFocusedIndex % colCount < colCount - 1 && gridFocusedIndex + 1 < items.size) {
                            gridFocusedIndex++; true
                        } else false
                    }
                    Key.Enter, Key.DirectionCenter -> {
                        items.getOrNull(gridFocusedIndex)?.let { openItem(it) }
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
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val cellWidth = (maxWidth - gridHPad * 2 - gridGap * (colCount - 1).coerceAtLeast(0)) / colCount
                        val minGridHeight = (cellWidth * 1.05f).coerceIn(140.dp, 220.dp)
                        val heroHeight = if (isTouchDevice) 110.dp else 160.dp
                        Column(modifier = Modifier.fillMaxSize()) {
                            PlexHeroBanner(item = heroItem, isTouchDevice = isTouchDevice, heroHeight = heroHeight)
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
                                        isFocused = gridFocusedIndex == index,
                                        subtitleOverride = uiState.subtitleByRatingKey[item.ratingKey],
                                        onClick = {
                                            if (gridFocusedIndex == index) {
                                                openItem(item)
                                            } else {
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

        // Movies/Shows are always reached via navigateTopLevel (bottom nav / NavRail swap,
        // never a stack push — every call site is navigateTopLevel(Screen.PlexLibrary...)),
        // exactly like Home. The "Back" hint doesn't apply to a top-level destination any more
        // here than it does on Home (which already carves out the same exemption) — it was
        // just reading as a stray label over empty space.
        com.arflix.tv.ui.components.MinimalTopChrome(profile = currentProfile, showBackHint = false)

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
        // banner blends seamlessly into the grid below rather than reading as a hard-edged
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
