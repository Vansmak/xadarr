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
import androidx.compose.foundation.focusable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.XadarrTheme
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.util.LocalDeviceType
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.arflix.tv.R
import kotlinx.coroutines.delay

/**
 * Context menu for media cards on home screen
 * Triggered by long press or Menu button
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MediaContextMenu(
    isVisible: Boolean,
    title: String,
    isInWatchlist: Boolean,
    isWatched: Boolean,
    isContinueWatching: Boolean = false,
    onPlay: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onRemoveFromContinueWatching: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val isMobile = LocalDeviceType.current.isTouchDevice()
    var focusedIndex by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val cardBg = XadarrTheme.colors.backgroundCard
    val elevatedBg = XadarrTheme.colors.backgroundElevated
    val borderColor = XadarrTheme.colors.borderLight

    val menuItems = buildList {
        add(MenuItem(
            icon = Icons.Default.PlayArrow,
            labelRes = R.string.play,
            action = onPlay
        ))
        add(MenuItem(
            icon = if (isInWatchlist) Icons.Default.Remove else Icons.Default.Add,
            labelRes = if (isInWatchlist) R.string.remove_from_watchlist else R.string.add_to_watchlist,
            action = onToggleWatchlist
        ))
        add(MenuItem(
            icon = if (isWatched) Icons.Default.Visibility else Icons.Default.Check,
            labelRes = if (isWatched) R.string.unwatched else R.string.watched,
            action = onToggleWatched
        ))
        // Add "Remove from Continue Watching" only when applicable
        if (isContinueWatching && onRemoveFromContinueWatching != null) {
            add(MenuItem(
                icon = Icons.Default.Close,
                labelRes = R.string.delete,
                action = onRemoveFromContinueWatching
            ))
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            focusedIndex = 0
            if (!isMobile) {
                focusRequester.requestFocus()
            }
        }
    }

    if (!isMobile) {
        // --- TV layout: centered card with D-pad navigation ---
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(50f)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionUp -> {
                                    if (focusedIndex > 0) focusedIndex--
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (focusedIndex < menuItems.size - 1) focusedIndex++
                                    true
                                }
                                Key.Enter, Key.DirectionCenter -> {
                                    menuItems[focusedIndex].action()
                                    onDismiss()
                                    true
                                }
                                Key.Back, Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .padding(top = 110.dp)
                        .width(320.dp)
                        .background(cardBg, RoundedCornerShape(14.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Title
                    Text(
                        text = title,
                        style = ArflixTypography.sectionTitle,
                        color = TextPrimary,
                        maxLines = 2
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Menu items
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        menuItems.forEachIndexed { index, item ->
                            ContextMenuItem(
                                icon = item.icon,
                                label = stringResource(item.labelRes),
                                isFocused = index == focusedIndex,
                                onClick = {
                                    item.action()
                                    onDismiss()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Hint
                    Text(
                        text = stringResource(R.string.press_back_to_close),
                        style = ArflixTypography.caption,
                        color = TextSecondary.copy(alpha = 0.5f)
                    )
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
                    .zIndex(50f)
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
                                elevatedBg,
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
                            maxLines = 2,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Divider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.08f))
                        )

                        // Menu items
                        menuItems.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        item.action()
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = stringResource(item.labelRes),
                                    style = ArflixTypography.body,
                                    color = TextPrimary
                                )
                            }
                            // Subtle divider between items (not after last)
                            if (index < menuItems.size - 1) {
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
    icon: ImageVector,
    label: String,
    isFocused: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isFocused) Pink else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 0.dp else 1.dp,
                color = if (isFocused) Color.Transparent else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) Color.Black else TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                style = ArflixTypography.body,
                color = if (isFocused) Color.Black else TextPrimary
            )
        }
    }
}

private data class MenuItem(
    val icon: ImageVector,
    @StringRes val labelRes: Int,
    val action: () -> Unit
)

/**
 * Context menu for "All Shows"/"All Movies" library-browser cards (Sonarr/Radarr
 * sourced). Same minimal fixed-list D-pad pattern as [LiveTvContextMenu].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryItemContextMenu(
    isVisible: Boolean,
    title: String,
    // e.g. "Continuing • 12/24 episodes • Rule: gracewatched" — replaces a
    // whole-series "Search" action, which force-searches every missing episode
    // at once and isn't something you'd want to trigger by accident from a
    // library browse list; per-episode search already exists in Details.
    statusDetail: String,
    hasAssignedRule: Boolean,
    onAssignRule: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(0) }
    val cardBg = XadarrTheme.colors.backgroundCard
    val borderColor = XadarrTheme.colors.borderLight

    val menuItems = listOf(
        MenuItem(
            icon = Icons.Default.Check,
            labelRes = if (hasAssignedRule) R.string.change_rule else R.string.assign_rule,
            action = onAssignRule
        ),
        MenuItem(icon = Icons.Default.Close, labelRes = R.string.delete_from_library, action = onDelete),
    )

    LaunchedEffect(isVisible) {
        if (isVisible) {
            focusedIndex = 0
            focusRequester.requestFocus()
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(160f)
                .background(Color.Black.copy(alpha = 0.5f))
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> { if (focusedIndex > 0) focusedIndex--; true }
                            Key.DirectionDown -> { if (focusedIndex < menuItems.size - 1) focusedIndex++; true }
                            Key.Enter, Key.DirectionCenter -> { menuItems[focusedIndex].action(); onDismiss(); true }
                            Key.Back, Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    } else false
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 110.dp)
                    .width(320.dp)
                    .background(cardBg, RoundedCornerShape(14.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = title, style = ArflixTypography.sectionTitle, color = TextPrimary, maxLines = 2)
                if (statusDetail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = statusDetail, style = ArflixTypography.caption, color = TextSecondary, maxLines = 2)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    menuItems.forEachIndexed { index, item ->
                        ContextMenuItem(
                            icon = item.icon,
                            label = stringResource(item.labelRes),
                            isFocused = index == focusedIndex,
                            onClick = { item.action(); onDismiss() }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.press_back_to_close),
                    style = ArflixTypography.caption,
                    color = TextSecondary.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Minimal 2-item context menu for live TV cards in the On Now home row.
 * Shows "Play Full Screen" and "TV Guide" options.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvContextMenu(
    isVisible: Boolean,
    channelName: String,
    onPlayFullScreen: () -> Unit,
    onGuide: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(0) }
    val cardBg = XadarrTheme.colors.backgroundCard
    val borderColor = XadarrTheme.colors.borderLight

    val menuItems = listOf(
        MenuItem(icon = Icons.Default.PlayArrow, labelRes = R.string.play_full_screen, action = onPlayFullScreen),
        MenuItem(icon = Icons.Default.LiveTv, labelRes = R.string.tv_guide, action = onGuide),
    )

    LaunchedEffect(isVisible) {
        if (isVisible) {
            focusedIndex = 0
            focusRequester.requestFocus()
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(50f)
                .background(Color.Black.copy(alpha = 0.5f))
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> { if (focusedIndex > 0) focusedIndex--; true }
                            Key.DirectionDown -> { if (focusedIndex < menuItems.size - 1) focusedIndex++; true }
                            Key.Enter, Key.DirectionCenter -> { menuItems[focusedIndex].action(); onDismiss(); true }
                            Key.Back, Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    } else false
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 110.dp)
                    .width(320.dp)
                    .background(cardBg, RoundedCornerShape(14.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = channelName,
                    style = ArflixTypography.sectionTitle,
                    color = TextPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    menuItems.forEachIndexed { index, item ->
                        ContextMenuItem(
                            icon = item.icon,
                            label = stringResource(item.labelRes),
                            isFocused = index == focusedIndex,
                            onClick = { item.action(); onDismiss() }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.press_back_to_close),
                    style = ArflixTypography.caption,
                    color = TextSecondary.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Hold-to-open confirmation popup for toggling a live TV channel's favorite
 * status. Replaces instant long-press toggling — holding OK/Enter on a
 * channel row opens this instead of immediately adding/removing it, so a
 * too-long tap can no longer silently change favorites.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelContextMenu(
    isVisible: Boolean,
    channelName: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val cardBg = XadarrTheme.colors.backgroundCard
    val borderColor = XadarrTheme.colors.borderLight
    val label = stringResource(
        if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites
    )
    val icon = if (isFavorite) Icons.Default.Remove else Icons.Default.Add

    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        // A single requestFocus() call loses the race against this popup's
        // own AnimatedVisibility enter transition placing its content — same
        // fix as EpgGrid's channel-focus restoration (see LiveTvScreen.kt
        // history). Retry across a few frames instead of guessing one delay.
        repeat(6) { attempt ->
            delay(if (attempt == 0) 32L else 24L)
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(50f)
                .background(Color.Black.copy(alpha = 0.5f))
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            // repeatCount == 0 only — the hold that opened this
                            // popup keeps generating repeat KeyDowns on the
                            // remote if the user is still pressing OK when
                            // focus lands here. Reacting to those would
                            // "confirm" the toggle the instant the popup
                            // appears, i.e. the exact same false-positive this
                            // popup exists to prevent. A genuinely new press
                            // always starts at repeatCount 0.
                            Key.Enter, Key.DirectionCenter -> {
                                if (event.nativeKeyEvent.repeatCount == 0) {
                                    onToggleFavorite()
                                    onDismiss()
                                }
                                true
                            }
                            Key.Back, Key.Escape -> { onDismiss(); true }
                            else -> true
                        }
                    } else false
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 110.dp)
                    .width(320.dp)
                    .background(cardBg, RoundedCornerShape(14.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = channelName,
                    style = ArflixTypography.sectionTitle,
                    color = TextPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(14.dp))
                ContextMenuItem(
                    icon = icon,
                    label = label,
                    isFocused = true,
                    onClick = { onToggleFavorite(); onDismiss() }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.press_back_to_close),
                    style = ArflixTypography.caption,
                    color = TextSecondary.copy(alpha = 0.5f)
                )
            }
        }
    }
}
