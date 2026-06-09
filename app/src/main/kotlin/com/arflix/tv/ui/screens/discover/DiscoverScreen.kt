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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.CollectionTileShape
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.components.AppTopBar
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.ui.components.MediaCard
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.topBarFocusedItem
import com.arflix.tv.ui.components.topBarMaxIndex
import com.arflix.tv.ui.components.topBarSelectedIndex
import com.arflix.tv.ui.theme.XadarrTheme
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.util.LocalDeviceType
import com.arflix.tv.util.LocalFrigateConfigured

private enum class DiscoverFocusZone { TOPBAR, ROWS }

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
    val frigateConfigured = LocalFrigateConfigured.current
    val hasProfile = currentProfile != null

    var focusZone by remember { mutableStateOf(DiscoverFocusZone.ROWS) }
    var topBarFocusIndex by remember {
        mutableIntStateOf(topBarSelectedIndex(SidebarItem.DISCOVER, hasProfile, frigateConfigured))
    }
    val maxTopBarIndex = remember(hasProfile, frigateConfigured) { topBarMaxIndex(hasProfile, frigateConfigured) }
    val rootFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    val firstRowFocusRequester = remember { FocusRequester() }
    var focusedRowIndex by remember { mutableIntStateOf(0) }
    var rulePickerItem by remember { mutableStateOf<com.arflix.tv.data.model.MediaItem?>(null) }
    val lazyColumnState = rememberLazyListState()

    LaunchedEffect(Unit) { runCatching { rootFocusRequester.requestFocus() } }

    // Drive focus directly into the first row once categories are ready.
    // Targeting firstRowFocusRequester (one level deep) instead of the LazyColumn
    // focusGroup (three levels deep) avoids the "bring into view" scroll cascade
    // that was hiding the first heading and left-shifting the first row.
    LaunchedEffect(uiState.categories.isNotEmpty()) {
        if (uiState.categories.isNotEmpty() && focusZone == DiscoverFocusZone.ROWS) {
            delay(150)
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
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back, Key.Escape -> { onBack(); true }
                    Key.DirectionUp -> {
                        if (focusZone == DiscoverFocusZone.ROWS &&
                            (focusedRowIndex == 0 || event.nativeKeyEvent.repeatCount >= 1)
                        ) {
                            focusZone = DiscoverFocusZone.TOPBAR
                            topBarFocusIndex = topBarSelectedIndex(SidebarItem.DISCOVER, hasProfile, frigateConfigured)
                            runCatching { rootFocusRequester.requestFocus() }
                            true
                        } else false
                    }
                    Key.DirectionDown -> {
                        if (focusZone == DiscoverFocusZone.TOPBAR) {
                            focusZone = DiscoverFocusZone.ROWS
                            runCatching { contentFocusRequester.requestFocus() }
                            true
                        } else false
                    }
                    Key.DirectionLeft -> {
                        if (focusZone == DiscoverFocusZone.TOPBAR && topBarFocusIndex > 0) {
                            topBarFocusIndex--; true
                        } else false
                    }
                    Key.DirectionRight -> {
                        if (focusZone == DiscoverFocusZone.TOPBAR && topBarFocusIndex < maxTopBarIndex) {
                            topBarFocusIndex++; true
                        } else false
                    }
                    Key.Enter, Key.DirectionCenter -> {
                        if (focusZone == DiscoverFocusZone.TOPBAR) {
                            when (topBarFocusedItem(topBarFocusIndex, hasProfile, frigateConfigured)) {
                                SidebarItem.HOME -> onNavigateToHome()
                                SidebarItem.SEARCH -> onNavigateToSearch()
                                SidebarItem.DISCOVER -> Unit
                                SidebarItem.TV -> onNavigateToTv()
                                SidebarItem.CAMERAS -> onNavigateToCameras()
                                SidebarItem.SETTINGS -> onNavigateToSettings()
                                null -> onSwitchProfile()
                            }
                            true
                        } else false
                    }
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
                                items(category.items, key = { it.id }) { item ->
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
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }

            }
        }

        if (!isTouchDevice) {
            AppTopBar(
                selectedItem = SidebarItem.DISCOVER,
                isFocused = focusZone == DiscoverFocusZone.TOPBAR,
                focusedIndex = if (focusZone == DiscoverFocusZone.TOPBAR) topBarFocusIndex else -1,
                profile = currentProfile,
            )
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
