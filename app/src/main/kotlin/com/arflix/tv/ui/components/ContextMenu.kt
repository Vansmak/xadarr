package com.arflix.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.util.LocalDeviceType

/**
 * Context menu action
 */
data class ContextAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val color: Color = TextPrimary
)

/**
 * Predefined context actions
 */
object ContextActions {
    val play = ContextAction("play", "Play", Icons.Default.PlayArrow, Pink)
    val selectSource = ContextAction("sources", "Select Source", Icons.Default.Info, TextPrimary)
    val markWatched = ContextAction("mark_watched", "Mark as Watched", Icons.Default.Visibility, Color(0xFF22C55E))
    val markUnwatched = ContextAction("mark_unwatched", "Mark as Unwatched", Icons.Default.VisibilityOff, TextSecondary)
    val addWatchlist = ContextAction("add_watchlist", "Add to Watchlist", Icons.Default.BookmarkBorder, Pink)
    val removeWatchlist = ContextAction("remove_watchlist", "Remove from Watchlist", Icons.Default.Bookmark, TextSecondary)
    val viewDetails = ContextAction("view_details", "View Details", Icons.Default.Info, TextPrimary)
    val markSeasonWatched = ContextAction("mark_season_watched", "Mark Season Watched", Icons.Default.Check, Color(0xFF22C55E))
    val markSeasonUnwatched = ContextAction("mark_season_unwatched", "Mark Season Unwatched", Icons.Default.Clear, TextSecondary)
    val searchSonarr = ContextAction("search_sonarr", "Find & Download", Icons.Default.Search, Color(0xFFEF5350))
    val deleteEpisodeFile = ContextAction("delete_episode_file", "Delete Episode", Icons.Default.Close, Color(0xFFDC2626))
}

/**
 * Context menu popup for media items and episodes
 */
private val CloseMenuAction = ContextAction("__close", "Close", Icons.Default.Close, TextSecondary)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContextMenu(
    isVisible: Boolean,
    title: String,
    subtitle: String? = null,
    actions: List<ContextAction>,
    onAction: (ContextAction) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val isMobile = LocalDeviceType.current.isTouchDevice()
    var focusedIndex by remember { mutableIntStateOf(0) }
    // TV: a real, selectable Close as the last item (Joe, 2026-09-30), replacing the
    // "Press Back to cancel" hint that looked like a button but couldn't be selected.
    val tvActions = remember(actions) { actions + CloseMenuAction }
    // Self-heal: if another component grabs focus while the menu is open (e.g. a screen's
    // delayed focus restoration), take it back -- otherwise D-pad input silently goes to the
    // screen behind and the menu looks frozen (Joe, 2026-09-30).
    var menuHasFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Request focus when menu becomes visible
    LaunchedEffect(isVisible) {
        if (isVisible) {
            focusedIndex = 0 // Reset to first item
            if (!isMobile) {
                // A single requestFocus() can lose the race with AnimatedVisibility placing the
                // content, leaving D-pad input on whatever screen is behind. Retry for a few frames.
                repeat(6) { attempt ->
                    kotlinx.coroutines.delay(if (attempt == 0) 16L else 32L)
                    if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                }
            }
        }
    }

    // Keep re-taking it for as long as the menu is open. One retry per focus change wasn't enough:
    // after a pick in guide search, the closing search handed focus to the sidebar's Search row
    // a beat later and kept it -- the room picker sat on screen while the remote drove the
    // sidebar behind it (Joe, 2026-10-04, "Shinedown", stuck for minutes).
    LaunchedEffect(isVisible) {
        if (!isVisible || isMobile) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(150L)
            if (!menuHasFocus) runCatching { focusRequester.requestFocus() }
        }
    }

    if (!isMobile) {
        // --- TV layout: centered card with D-pad navigation ---
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.56f))
                    .focusRequester(focusRequester)
                    .onFocusChanged { menuHasFocus = it.hasFocus }
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                // Left backs out like Back (Joe, 2026-09-30: it used to fall
                                // through and move the guide behind the menu).
                                Key.Back, Key.Escape, Key.DirectionLeft -> {
                                    onDismiss()
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (focusedIndex > 0) focusedIndex--
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (focusedIndex < tvActions.size - 1) focusedIndex++
                                    true
                                }
                                Key.Enter, Key.DirectionCenter -> {
                                    tvActions.getOrNull(focusedIndex)?.let { action ->
                                        if (action.id == CloseMenuAction.id) onDismiss() else onAction(action)
                                    }
                                    true
                                }
                                // Swallow the other navigation keys so they don't move the screen
                                // behind the popup. Volume/media keys still pass through.
                                Key.DirectionRight, Key.ChannelUp, Key.ChannelDown,
                                Key.PageUp, Key.PageDown, Key.Tab -> true
                                else -> false
                            }
                        } else false
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                // Fit the screen: TVs are only ~540dp tall, and a 110dp top offset plus a long
                // list ran the card off the bottom (Joe, 2026-10-03, screenshots).
                val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                Column(
                    modifier = Modifier
                        .padding(top = if (screenHeight < 700) 48.dp else 110.dp)
                        .width(360.dp)
                        .background(BackgroundElevated, RoundedCornerShape(18.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Title
                    Text(
                        text = title,
                        style = ArflixTypography.sectionTitle,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    
                    // Subtitle
                    if (subtitle != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = subtitle,
                            style = ArflixTypography.body,
                            color = TextSecondary
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.1f))
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Actions. Scrolls with focus: a long list (every Sonos zone, say) used to
                    // run off the bottom of the screen with focus moving onto hidden rows
                    // (Joe, 2026-10-03).
                    val actionsListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LaunchedEffect(focusedIndex) {
                        val visible = actionsListState.layoutInfo.visibleItemsInfo
                        val first = visible.firstOrNull()?.index ?: 0
                        val last = visible.lastOrNull()?.let { if (it.offset + it.size > actionsListState.layoutInfo.viewportEndOffset) it.index - 1 else it.index } ?: 0
                        if (focusedIndex < first || focusedIndex > last) {
                            actionsListState.animateScrollToItem((focusedIndex - (last - first)).coerceAtLeast(0).takeIf { focusedIndex > last } ?: focusedIndex)
                        }
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = actionsListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = (screenHeight - (if (screenHeight < 700) 48 else 110) - 190).coerceIn(160, 340).dp),
                    ) {
                        items(tvActions.size) { index ->
                            ContextMenuItem(
                                action = tvActions[index],
                                isFocused = index == focusedIndex
                            )
                        }
                    }
                }
            }
        }
    } else {
        // --- Mobile layout: bottom-sheet style menu ---
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { onDismiss() }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                            onDismiss()
                            true
                        } else false
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it })
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                BackgroundElevated,
                                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { /* consume click so backdrop handler doesn't fire */ }
                            .padding(top = 16.dp, bottom = 24.dp)
                    ) {
                        // Drag handle indicator
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(36.dp)
                                .height(4.dp)
                                .background(
                                    Color.White.copy(alpha = 0.2f),
                                    RoundedCornerShape(2.dp)
                                )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Title
                        Text(
                            text = title,
                            style = ArflixTypography.sectionTitle,
                            color = TextPrimary,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        // Subtitle
                        if (subtitle != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = subtitle,
                                style = ArflixTypography.body,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Divider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.08f))
                        )

                        // Action items
                        actions.forEachIndexed { index, action ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { onAction(action) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = null,
                                    tint = action.color,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = action.label,
                                    style = ArflixTypography.body,
                                    color = TextPrimary
                                )
                            }
                            // Subtle divider between items (not after last)
                            if (index < actions.size - 1) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .height(1.dp)
                                        .background(Color.White.copy(alpha = 0.05f))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContextMenuItem(
    action: ContextAction,
    isFocused: Boolean
) {
    val bgColor = if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent
    val borderColor = if (isFocused) Pink else Color.Transparent
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = if (isFocused) Pink else action.color,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = action.label,
            style = ArflixTypography.body,
            color = if (isFocused) TextPrimary else action.color
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        if (isFocused) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Pink, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Episode context menu with standard actions
 */
@Composable
fun EpisodeContextMenu(
    isVisible: Boolean,
    episodeName: String,
    seasonEpisode: String,
    isWatched: Boolean,
    showSonarrSearch: Boolean = false,
    showDeleteEpisode: Boolean = false,
    onPlay: () -> Unit,
    onSelectSource: () -> Unit,
    onToggleWatched: () -> Unit,
    onSearchSonarr: () -> Unit = {},
    onDeleteEpisode: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val actions = listOfNotNull(
        ContextActions.play,
        ContextActions.selectSource,
        if (isWatched) ContextActions.markUnwatched else ContextActions.markWatched,
        if (showSonarrSearch) ContextActions.searchSonarr else null,
        if (showDeleteEpisode) ContextActions.deleteEpisodeFile else null
    )

    ContextMenu(
        isVisible = isVisible,
        title = episodeName,
        subtitle = seasonEpisode,
        actions = actions,
        onAction = { action ->
            when (action.id) {
                "play" -> onPlay()
                "sources" -> onSelectSource()
                "mark_watched", "mark_unwatched" -> onToggleWatched()
                "search_sonarr" -> onSearchSonarr()
                "delete_episode_file" -> onDeleteEpisode()
            }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

@Composable
fun SeasonContextMenu(
    isVisible: Boolean,
    seasonNumber: Int,
    onMarkSeasonWatched: () -> Unit,
    onMarkSeasonUnwatched: () -> Unit,
    onDismiss: () -> Unit
) {
    ContextMenu(
        isVisible = isVisible,
        title = "Season $seasonNumber",
        subtitle = "Quick Actions",
        actions = listOf(
            ContextActions.markSeasonWatched,
            ContextActions.markSeasonUnwatched
        ),
        onAction = { action ->
            when (action.id) {
                "mark_season_watched" -> onMarkSeasonWatched()
                "mark_season_unwatched" -> onMarkSeasonUnwatched()
            }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

/**
 * Media item context menu with standard actions
 */
@Composable
fun MediaContextMenu(
    isVisible: Boolean,
    title: String,
    year: String? = null,
    isWatched: Boolean,
    isInWatchlist: Boolean,
    onPlay: () -> Unit,
    onSelectSource: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onViewDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    val actions = listOf(
        ContextActions.play,
        ContextActions.selectSource,
        if (isWatched) ContextActions.markUnwatched else ContextActions.markWatched,
        if (isInWatchlist) ContextActions.removeWatchlist else ContextActions.addWatchlist,
        ContextActions.viewDetails
    )
    
    ContextMenu(
        isVisible = isVisible,
        title = title,
        subtitle = year,
        actions = actions,
        onAction = { action ->
            when (action.id) {
                "play" -> onPlay()
                "sources" -> onSelectSource()
                "mark_watched", "mark_unwatched" -> onToggleWatched()
                "add_watchlist", "remove_watchlist" -> onToggleWatchlist()
                "view_details" -> onViewDetails()
            }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}
