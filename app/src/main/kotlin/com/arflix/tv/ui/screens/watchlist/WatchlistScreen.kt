package com.arflix.tv.ui.screens.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.res.Configuration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.itemsIndexed
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.ui.components.CardLayoutMode
import com.arflix.tv.util.LocalDeviceType
import com.arflix.tv.ui.components.LoadingIndicator
import com.arflix.tv.ui.components.MediaCard
import com.arflix.tv.ui.components.Toast
import com.arflix.tv.ui.components.ToastType as ComponentToastType
import com.arflix.tv.ui.components.rememberCatalogueRowLayoutMode
import com.arflix.tv.util.LocalNeolinkConfigured
import com.arflix.tv.ui.focus.xadarrDpadFocusGroup
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.util.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Watchlist screen - matches webapp design with grid layout
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WatchlistScreen(
    viewModel: WatchlistViewModel = hiltViewModel(),
    currentProfile: com.arflix.tv.data.model.Profile? = null,
    onNavigateToDetails: (MediaType, Int) -> Unit = { _, _ -> },
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToDiscover: () -> Unit = {},
    onNavigateToTv: () -> Unit = {},
    onNavigateToCameras: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logoUrls by viewModel.logoUrls.collectAsStateWithLifecycle()
    val rowKey = "watchlist"
    val usePosterCards = rememberCatalogueRowLayoutMode(rowKey) == CardLayoutMode.POSTER
    val configuration = LocalConfiguration.current
    val isMobile = LocalDeviceType.current.isTouchDevice()
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gridColumns = if (isMobile) {
        if (isLandscape) { if (usePosterCards) 3 else 2 } else 2
    } else if (usePosterCards) {
        when {
            configuration.screenWidthDp >= 2200 -> 8
            configuration.screenWidthDp >= 1600 -> 7
            else -> 6
        }
    } else when {
        configuration.screenWidthDp >= 2200 -> 5
        configuration.screenWidthDp >= 1600 -> 4
        else -> 3
    }
    val cardWidth = if (usePosterCards) {
        if (isMobile) 124.dp else 125.dp
    } else if (isMobile) 160.dp else when (gridColumns) {
        5 -> 240.dp
        4 -> 250.dp
        else -> 230.dp
    }
    
    var isSidebarFocused by remember { mutableStateOf(false) }
    val episeerrPendingIds = com.arflix.tv.util.LocalEpiseerrPendingIds.current
    var rulePickerItem by remember { mutableStateOf<com.arflix.tv.data.model.MediaItem?>(null) }
    val neolinkConfigured = LocalNeolinkConfigured.current
    val navSections = com.arflix.tv.util.LocalNavSections.current
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
    // card's own click (this grid's MediaCards use real system focus; Joe,
    // 2026-07-11: activating a NavRail entry was landing on whatever card had
    // focus before the rail opened, e.g. a random movie's Details screen).
    var pendingRailKeyUp by remember { mutableStateOf<Key?>(null) }
    val rootFocusRequester = remember { FocusRequester() }
    val gridFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val gridState = rememberTvLazyGridState()
    var focusedGridIndex by remember { mutableIntStateOf(0) }
    var gridHasFocus by remember { mutableStateOf(false) }

    // Keep the focused card in view with smooth animated scrolling.
    LaunchedEffect(focusedGridIndex, uiState.items.size) {
        if (uiState.items.isEmpty()) return@LaunchedEffect
        val safe = focusedGridIndex.coerceIn(0, uiState.items.lastIndex)
        val firstVisible = gridState.firstVisibleItemIndex
        val distance = abs(firstVisible - safe)
        if (distance > gridColumns * 4) {
            gridState.scrollToItem(safe)
        } else {
            gridState.animateScrollToItem(safe)
        }
    }

    // Refresh watchlist when screen resumes (e.g. after removing from detail screen)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        rootFocusRequester.requestFocus()
    }

    LaunchedEffect(uiState.isLoading, uiState.items.isEmpty()) {
        if (!uiState.isLoading && uiState.items.isEmpty()) {
            // Empty screen must always have a deterministic focus target.
            isSidebarFocused = true
        } else if (!uiState.isLoading && uiState.items.isNotEmpty() && !isSidebarFocused) {
            // Ensure first card can receive focus when content becomes available.
            delay(80)
            runCatching { gridFocusRequester.requestFocus() }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark())
            .focusRequester(rootFocusRequester)
            .onFocusChanged {
                if (it.isFocused) {
                    isSidebarFocused = true
                }
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == pendingRailKeyUp) {
                    pendingRailKeyUp = null
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (isNavRailOpen.value) {
                    pendingRailKeyUp = event.key
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
                        actions = com.arflix.tv.ui.components.NavRailActions(
                            onNavigateToHome = onNavigateToHome,
                            onNavigateToSearch = onNavigateToSearch,
                            onNavigateToDiscover = onNavigateToDiscover,
                            onNavigateToTv = onNavigateToTv,
                            onNavigateToCameras = onNavigateToCameras,
                            onNavigateToSettings = onNavigateToSettings,
                        ),
                    )
                    return@onPreviewKeyEvent true
                }
                // Helper: transition focus from grid to chrome (root)
                fun moveToSidebar() {
                    isSidebarFocused = true
                    // Immediately steal focus from grid card to prevent card click on next Enter
                    runCatching { rootFocusRequester.requestFocus() }
                }

                when (event.key) {
                    Key.Back, Key.Escape -> {
                        if (isSidebarFocused) {
                            onBack()
                        } else {
                            moveToSidebar()
                        }
                        true
                    }
                    Key.DirectionLeft -> {
                        if (!isSidebarFocused) {
                            if (focusedGridIndex % gridColumns == 0) {
                                moveToSidebar()
                                isNavRailOpen.value = true
                                pendingRailKeyUp = Key.DirectionLeft
                                true
                            } else {
                                false
                            }
                        } else {
                            // Leftmost/chrome, one more Left opens the rail — same
                            // gesture every other screen uses.
                            isNavRailOpen.value = true
                            pendingRailKeyUp = Key.DirectionLeft
                            true
                        }
                    }
                    Key.DirectionRight -> {
                        // No more top bar items to cycle through here (all moved to
                        // NavRail) — just consume so it doesn't fall through.
                        isSidebarFocused
                    }
                    Key.DirectionUp -> {
                        if (isSidebarFocused) {
                            true
                        } else {
                            val firstVisibleIndex = gridState.firstVisibleItemIndex
                            if (firstVisibleIndex == 0 && focusedGridIndex < gridColumns) {
                                moveToSidebar()
                                true
                            } else {
                                false
                            }
                        }
                    }
                    Key.DirectionDown -> {
                        if (isSidebarFocused) {
                            if (uiState.items.isNotEmpty()) {
                                isSidebarFocused = false
                                runCatching { gridFocusRequester.requestFocus() }
                            }
                            true
                        } else {
                            if (focusedGridIndex >= uiState.items.size - 1) {
                                true
                            } else {
                                false
                            }
                        }
                    }
                    Key.Enter, Key.DirectionCenter -> {
                        // Chrome no longer has any dispatchable item of its own
                        // (Search/Discover/TV/Cameras/Settings/Watchlist all moved to
                        // NavRail) — just consume so it doesn't fall through to a card click.
                        isSidebarFocused
                    }
                    else -> false
                }
            }
    ) {
        if (!LocalDeviceType.current.isTouchDevice()) {
            com.arflix.tv.ui.components.MinimalTopChrome(profile = currentProfile)

            // zIndex forces this above the grid content regardless of composition
            // order — see HomeScreen.kt/SettingsScreen.kt's identical fix.
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
                    actions = com.arflix.tv.ui.components.NavRailActions(
                        onNavigateToHome = onNavigateToHome,
                        onNavigateToSearch = onNavigateToSearch,
                        onNavigateToDiscover = onNavigateToDiscover,
                        onNavigateToTv = onNavigateToTv,
                        onNavigateToCameras = onNavigateToCameras,
                        onNavigateToSettings = onNavigateToSettings,
                    ),
                    focusedIndex = navRailFocusedIndex.value,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (isMobile) 0.dp else AppTopBarContentTopInset)
                .padding(start = 24.dp, top = if (isMobile) 16.dp else 4.dp, end = 48.dp)
        ) {
                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator(color = Pink, size = 64.dp)
                        }
                    }
                    uiState.items.isEmpty() -> {
                        // fillMaxSize ensures proper centering within the available space
                        // for both mobile and TV layouts (Bug 12 & 24).
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Bookmark,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.2f),
                                    modifier = Modifier.size(80.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = tr("Your watchlist is empty"),
                                    style = ArflixTypography.body,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = tr("Add movies and shows for later"),
                                    style = ArflixTypography.caption,
                                    color = Color.White.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                    else -> {
                        // Grid of items - 4 columns like screenshot
                        TvLazyVerticalGrid(
                            columns = TvGridCells.Fixed(gridColumns),
                            state = gridState,
                            contentPadding = PaddingValues(top = 18.dp, bottom = 56.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(gridFocusRequester)
                                .xadarrDpadFocusGroup()
                                .onFocusChanged { 
                                    gridHasFocus = it.hasFocus
                                    if (it.hasFocus) {
                                        isSidebarFocused = false
                                    }
                                }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.Back, Key.Escape -> {
                                                isSidebarFocused = true
                                                scope.launch {
                                                    delay(40)
                                                    runCatching { rootFocusRequester.requestFocus() }
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                        ) {
                            itemsIndexed(uiState.items) { index, item ->
                                val logoUrl = logoUrls["${item.mediaType}_${item.id}"]
                                val itemIsPending = episeerrPendingIds.contains(item.id.toString())
                                MediaCard(
                                    item = item,
                                    width = cardWidth,
                                    isLandscape = !usePosterCards,
                                    logoImageUrl = logoUrl,
                                    focusedScale = 1f,
                                    isPending = itemIsPending,
                                    onFocused = { focusedGridIndex = index },
                                    onClick = {
                                        if (itemIsPending) rulePickerItem = item
                                        else onNavigateToDetails(item.mediaType, item.id)
                                    },
                                    onLongClick = { viewModel.removeFromWatchlist(item) }
                                )
                            }
                        }
                    }
                }
            }

        // Toast notification
        uiState.toastMessage?.let { message ->
            Toast(
                message = message,
                type = when (uiState.toastType) {
                    ToastType.SUCCESS -> ComponentToastType.SUCCESS
                    ToastType.ERROR -> ComponentToastType.ERROR
                    ToastType.INFO -> ComponentToastType.INFO
                },
                isVisible = true,
                onDismiss = { viewModel.dismissToast() }
            )
        }

        // Rule picker rendered last so it covers the topbar
        rulePickerItem?.let { mediaItem ->
            val rulePickerVm: com.arflix.tv.ui.screens.episeerr.RulePickerViewModel =
                hiltViewModel()
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
