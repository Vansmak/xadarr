@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.delay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.CollectionTileShape
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.ui.components.MediaCard
import com.arflix.tv.ui.theme.XadarrTheme
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.util.LocalDeviceType
import com.arflix.tv.util.LocalNeolinkConfigured

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel = hiltViewModel(),
    currentProfile: Profile? = null,
    onNavigateToDetails: (MediaType, Int) -> Unit = { _, _ -> },
    onNavigateToCollection: (String) -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToTv: () -> Unit = {},
    onNavigateToCameras: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val isTouchDevice = LocalDeviceType.current.isTouchDevice()
    val neolinkConfigured = LocalNeolinkConfigured.current
    val navSections = com.arflix.tv.util.LocalNavSections.current
    val hasProfile = currentProfile != null

    val context = androidx.compose.ui.platform.LocalContext.current
    val isNavRailOpen = com.arflix.tv.ui.components.rememberNavRailOpen()
    // Driven directly by this screen's own key handler below rather than NavRail's
    // internal FocusRequester — see NavRail.kt's doc comment / HomeScreen.kt's
    // identical fix (real Compose focus never reliably lands inside NavRail).
    val navRailFocusedIndex = remember { mutableStateOf(0) }
    LaunchedEffect(isNavRailOpen.value) {
        if (isNavRailOpen.value) navRailFocusedIndex.value = 0
    }
    // A KeyDown consumed by the rail block below (activating an entry, or the
    // Left press that opens the rail) still has a matching KeyUp on the way —
    // that KeyUp arrives after isNavRailOpen.value has already flipped, so it
    // isn't caught by the same guard and falls through to whatever card still
    // holds real Compose focus underneath (this screen's cards use
    // enableSystemFocus, unlike Home's manual scheme), firing that card's own
    // onClick. Tracking the exact key lets us swallow just its KeyUp partner
    // (Joe, 2026-07-11: selecting a NavRail entry was landing on whatever
    // background card — e.g. "Obsession" — had focus before the rail opened).
    var pendingRailKeyUp by remember { mutableStateOf<Key?>(null) }
    val rootFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    val firstRowFocusRequester = remember { FocusRequester() }
    var focusedRowIndex by remember { mutableIntStateOf(0) }
    // Index of the focused card within its row — item 0 is the row's (and thus
    // the screen's) leftmost column. LEFT while here opens the NavRail instead
    // of doing nothing, as it did before (see navRailPreviewKey below).
    var focusedItemIndexInRow by remember { mutableIntStateOf(0) }
    var rulePickerItem by remember { mutableStateOf<com.arflix.tv.data.model.MediaItem?>(null) }
    val lazyColumnState = rememberLazyListState()

    LaunchedEffect(Unit) { runCatching { rootFocusRequester.requestFocus() } }

    // Drive focus directly into the first row once categories are ready.
    // Delay must exceed the NavHost fade-out animation (250ms) so the exiting
    // screen's cards are no longer composing and competing when we request focus.
    LaunchedEffect(uiState.categories.isNotEmpty()) {
        if (uiState.categories.isNotEmpty()) {
            delay(300)
            runCatching { firstRowFocusRequester.requestFocus() }
        }
    }

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(XadarrTheme.colors.backgroundDark)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == pendingRailKeyUp) {
                    pendingRailKeyUp = null
                    return@onPreviewKeyEvent true
                }
                if (isNavRailOpen.value) {
                    if (event.type == KeyEventType.KeyDown) pendingRailKeyUp = event.key
                    val railEntries = com.arflix.tv.ui.components.computeNavRailEntries(
                        currentScreen = com.arflix.tv.data.model.NavSectionKind.DISCOVER,
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
                            onNavigateToTv = onNavigateToTv,
                            onNavigateToCameras = onNavigateToCameras,
                            onNavigateToSettings = onNavigateToSettings,
                        ),
                    )
                    return@onPreviewKeyEvent true
                }
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && focusedItemIndexInRow == 0) {
                    isNavRailOpen.value = true
                    pendingRailKeyUp = Key.DirectionLeft
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back, Key.Escape -> { onBack(); true }
                    else -> false
                }
            }
    ) {
        when {
            uiState.isLoading -> {
                Box(
                    Modifier.fillMaxSize().padding(top = AppTopBarContentTopInset),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = Pink) }
            }
            uiState.error != null && uiState.categories.isEmpty() -> {
                Box(
                    Modifier.fillMaxSize().padding(top = AppTopBarContentTopInset + 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        uiState.error!!,
                        color = Color.White.copy(0.5f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 48.dp)
                    )
                }
            }
            else -> {
                val episeerrPendingIds = com.arflix.tv.util.LocalEpiseerrPendingIds.current

                LazyColumn(
                    state = lazyColumnState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = AppTopBarContentTopInset)
                        .focusRequester(contentFocusRequester)
                        .focusGroup(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 200.dp),
                ) {
                    itemsIndexed(uiState.categories, key = { _, c -> c.id }) { rowIdx, category ->
                        val isWatchlistRow = category.id == "my_watchlist"
                        val isCollectionRow = category.id.startsWith("collection_row_")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .onFocusChanged { if (it.hasFocus) focusedRowIndex = rowIdx }
                        ) {
                            Text(
                                text = category.title,
                                color = Color.White.copy(0.9f),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 48.dp, bottom = 10.dp, top = 16.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 48.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (rowIdx == 0) Modifier.focusRequester(firstRowFocusRequester).focusGroup()
                                        else Modifier
                                    ),
                            ) {
                                itemsIndexed(category.items, key = { _, it -> it.id }) { itemIdx, item ->
                                    val itemIsPending = isWatchlistRow &&
                                        episeerrPendingIds.contains(item.id.toString())
                                    val isCollectionTile = isCollectionRow &&
                                        item.status?.startsWith("collection:") == true
                                    val collectionIsLandscape = item.collectionTileShape != CollectionTileShape.POSTER
                                    val cardIsLandscape = if (isCollectionRow) collectionIsLandscape else true
                                    val cardWidth = when {
                                        isCollectionRow && !collectionIsLandscape -> 119.dp
                                        isCollectionRow -> 210.dp
                                        else -> 200.dp
                                    }
                                    MediaCard(
                                        item = item,
                                        width = cardWidth,
                                        isLandscape = cardIsLandscape,
                                        isPending = itemIsPending,
                                        showTitle = if (isCollectionRow) !item.collectionHideTitle else true,
                                        onClick = {
                                            when {
                                                itemIsPending -> rulePickerItem = item
                                                isCollectionTile -> {
                                                    val catalogId = item.status!!.removePrefix("collection:")
                                                    onNavigateToCollection(catalogId)
                                                }
                                                else -> onNavigateToDetails(item.mediaType, item.id)
                                            }
                                        },
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .onFocusChanged { if (it.hasFocus) focusedItemIndexInRow = itemIdx },
                                    )
                                }
                            }
                        }
                    }
                }

            }
        }

        if (!isTouchDevice) {
            com.arflix.tv.ui.components.MinimalTopChrome(profile = currentProfile)

            // zIndex forces this above the category content above regardless of
            // composition order — see HomeScreen.kt/SettingsScreen.kt's identical fix.
            Box(modifier = Modifier.zIndex(10f)) {
            com.arflix.tv.ui.components.NavRail(
                isOpen = isNavRailOpen.value,
                onClose = {
                    isNavRailOpen.value = false
                    runCatching { rootFocusRequester.requestFocus() }
                },
                currentScreen = com.arflix.tv.data.model.NavSectionKind.DISCOVER,
                navSections = navSections,
                neolinkConfigured = neolinkConfigured,
                currentProfile = currentProfile,
                actions = com.arflix.tv.ui.components.NavRailActions(
                    onNavigateToHome = onNavigateToHome,
                    onNavigateToSearch = onNavigateToSearch,
                    onNavigateToTv = onNavigateToTv,
                    onNavigateToCameras = onNavigateToCameras,
                    onNavigateToSettings = onNavigateToSettings,
                ),
                focusedIndex = navRailFocusedIndex.value,
            )
            }
        }

        // Rule picker rendered last so it covers the topbar
        rulePickerItem?.let { mediaItem ->
            val rulePickerVm: com.arflix.tv.ui.screens.episeerr.RulePickerViewModel =
                androidx.hilt.navigation.compose.hiltViewModel()
            val syncServerUrl by rulePickerVm.syncServerUrl.collectAsState()
            val episeerrUrl by rulePickerVm.episeerrUrl.collectAsState()
            val pendingItem = com.arflix.tv.data.repository.EpiseerrPendingItem(
                id       = mediaItem.id.toString(),
                seriesId = null,
                title    = mediaItem.title,
                tmdbId   = mediaItem.id.toString(),
                tvdbId   = null,
                poster   = mediaItem.image.takeIf { it.isNotBlank() },
            )
            com.arflix.tv.ui.screens.episeerr.RulePickerScreen(
                pendingItem        = pendingItem,
                episeerrRepository = rulePickerVm.episeerrRepository,
                syncServerUrl      = syncServerUrl,
                episeerrUrl        = episeerrUrl,
                onDismiss          = { rulePickerItem = null },
                onRuleAssigned     = { rulePickerItem = null },
            )
        }
    }
}
