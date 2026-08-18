package com.arflix.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.NavSectionConfig
import com.arflix.tv.data.model.NavSectionKind
import com.arflix.tv.navigation.NavTargets
import com.arflix.tv.ui.screens.tv.live.LiveColors
import com.arflix.tv.ui.screens.tv.live.LiveDims

/**
 * Callbacks a screen already has from AppNavigation.kt — same set NavRail needs
 * to dispatch a selection, just bundled so call sites don't pass 7 separate
 * lambdas. Kept decoupled from NavController on purpose (matches how every
 * screen already receives these individually).
 */
data class NavRailActions(
    val onNavigateToHome: () -> Unit = {},
    val onNavigateToSearch: () -> Unit = {},
    val onNavigateToDiscover: () -> Unit = {},
    val onNavigateToTv: () -> Unit = {},
    val onNavigateToCameras: () -> Unit = {},
    val onNavigateToSettings: () -> Unit = {},
    val onNavigateToWatchlist: () -> Unit = {},
    val onNavigateToAllApps: () -> Unit = {},
    val onNavigateToMovies: () -> Unit = {},
    val onNavigateToShows: () -> Unit = {},
    // Optional — only the Guide (LiveTvScreen) wires this, so it can pause its own live audio
    // before handing off. Every other screen the rail opens from already paused on the way in
    // (see AppNavigation.kt's navigateTopLevel()), so the plain context-based launch below is
    // enough there and they can leave this null.
    val onNavigateToPlex: (() -> Unit)? = null,
)

// Plex-style grouping — mirrors the real Plex TV app's rail: an ungrouped top block
// (Home/Search/Discover-equivalent core nav), a "LIBRARIES" header over the
// browsable-content entries, an "ON PLEX" header over anything Plex-branded, and
// Settings pinned below a divider with no header at all. Returns null for the
// ungrouped top block.
private fun sectionLabelFor(entry: NavSectionConfig): String? = when {
    entry.kind == NavSectionKind.DISCOVER -> "ON PLEX"
    entry.kind == NavSectionKind.CUSTOM && entry.customId == "plex" -> "ON PLEX"
    entry.kind == NavSectionKind.CUSTOM && entry.customId in setOf("movies", "shows", "apps", "plex_library") -> "LIBRARIES"
    else -> null
}

private fun NavSectionKind.toRailLabel(): String = when (this) {
    NavSectionKind.SEARCH -> "Search"
    NavSectionKind.HOME -> "Home"
    NavSectionKind.DISCOVER -> "Discover"
    NavSectionKind.TV -> "Guide"
    NavSectionKind.CAMERAS -> "Cameras"
    NavSectionKind.SETTINGS -> "Settings"
    NavSectionKind.CUSTOM -> ""
}

/**
 * Icon for a nav entry — fixed kinds map directly; CUSTOM entries (Movies/
 * Shows/Apps by default, see NavSectionRepository.defaultSections())
 * resolve by customId with a generic fallback for anything unrecognized.
 * Shared by NavRail and Home's "Your library" tile row so both presentations
 * of the same config look consistent.
 */
fun navEntryIcon(entry: com.arflix.tv.data.model.NavSectionConfig) = when (entry.kind) {
    NavSectionKind.SEARCH -> SidebarItem.SEARCH.icon
    NavSectionKind.HOME -> SidebarItem.HOME.icon
    NavSectionKind.DISCOVER -> SidebarItem.DISCOVER.icon
    NavSectionKind.TV -> SidebarItem.TV.icon
    NavSectionKind.CAMERAS -> SidebarItem.CAMERAS.icon
    NavSectionKind.SETTINGS -> SidebarItem.SETTINGS.icon
    NavSectionKind.CUSTOM -> when (entry.customId) {
        "movies", "plex_library" -> Icons.Outlined.Movie
        "shows" -> Icons.Outlined.Tv
        "apps" -> Icons.Outlined.Apps
        "plex" -> Icons.Outlined.Explore
        "watchlist" -> Icons.Outlined.Bookmark
        else -> Icons.Outlined.Star
    }
}

/**
 * Pure computation of what the rail shows, extracted so a screen's own key
 * handler can compute bounds (entries.size) without composing the rail.
 *
 * TiviMate-clone redesign: Home no longer exists as a separate screen (it IS
 * the Live TV guide, kind == TV), so there's no synthetic "Home" entry to
 * prepend anymore — TV/"Guide" is just an ordinary sortable, filterable entry
 * like the rest, and the `it.kind != currentScreen` filter below naturally
 * hides it while already on the guide.
 */
fun computeNavRailEntries(
    currentScreen: NavSectionKind?,
    navSections: List<NavSectionConfig>,
    neolinkConfigured: Boolean,
    restrictToKinds: Set<NavSectionKind>? = null,
): List<NavSectionConfig> {
    // Settings sorts last regardless of its configured order — it's the "everything
    // else" destination, not something users reorder ahead of real nav targets.
    val rest = navSections
        .filter { it.visible && it.kind != currentScreen }
        .filter { it.kind != NavSectionKind.CAMERAS || neolinkConfigured }
        .filter { restrictToKinds == null || it.kind in restrictToKinds }
        .sortedWith(compareBy({ it.kind == NavSectionKind.SETTINGS }, { it.order }))
    return rest
}

/**
 * Pure dispatch, extracted so a screen's own key handler can invoke it directly.
 * `context` is currently unused by any branch here (kept nullable/optional so existing
 * call sites don't need changes) — Movies/Shows used to do a `context`-based generic
 * Plex app-launch directly from this dispatch; that moved into PlexLibraryScreen
 * per-item instead (see below), leaving this a pure navigation dispatch.
 */
fun activateNavRailEntry(entry: NavSectionConfig, actions: NavRailActions, context: android.content.Context? = null) {
    // Movies/Shows open a live Plex poster grid in-app (PlexLibraryScreen) — browsing
    // happens in Xadarr, only selecting a specific title deep-links out to Plex (see
    // PlexDeepLink.kt usage inside that screen). No generic app-launch at the menu level.
    if (entry.kind == NavSectionKind.CUSTOM && entry.customId == "movies") {
        actions.onNavigateToMovies()
        return
    }
    if (entry.kind == NavSectionKind.CUSTOM && entry.customId == "shows") {
        actions.onNavigateToShows()
        return
    }
    if (entry.kind == NavSectionKind.CUSTOM && entry.customId == "apps") {
        actions.onNavigateToAllApps()
        return
    }
    // Direct, generic launch into the native Plex app — not tied to any title. Prefers the
    // caller's own onNavigateToPlex (Guide/LiveTvScreen wires one that pauses its live audio
    // first); every other screen falls back to the plain context-based launch since they've
    // already paused on the way in via navigateTopLevel().
    if (entry.kind == NavSectionKind.CUSTOM && entry.customId == "plex") {
        val custom = actions.onNavigateToPlex
        if (custom != null) {
            custom()
        } else {
            context?.packageManager?.getLaunchIntentForPackage("com.plexapp.android")?.let {
                context.startActivity(it)
            }
        }
        return
    }
    if (entry.kind == NavSectionKind.CUSTOM) {
        NavTargets.activate(
            entry.target,
            onNavigateToHome = actions.onNavigateToHome,
            onNavigateToSearch = actions.onNavigateToSearch,
            onNavigateToTv = actions.onNavigateToTv,
            onNavigateToCameras = actions.onNavigateToCameras,
            onNavigateToWatchlist = actions.onNavigateToWatchlist,
        )
        return
    }
    when (entry.kind) {
        NavSectionKind.HOME -> actions.onNavigateToHome()
        NavSectionKind.SEARCH -> actions.onNavigateToSearch()
        NavSectionKind.DISCOVER -> actions.onNavigateToDiscover()
        NavSectionKind.TV -> actions.onNavigateToTv()
        NavSectionKind.CAMERAS -> actions.onNavigateToCameras()
        NavSectionKind.SETTINGS -> actions.onNavigateToSettings()
        NavSectionKind.CUSTOM -> Unit
    }
}

/**
 * Vertical overlay nav rail used by every screen except Home (Home renders
 * navSectionsByProfile as its own horizontal hub row instead — see AppTopBar's
 * navItems()/customNavEntries()). Opened when the screen's leftmost focusable
 * element is focused and LEFT is pressed again (see NavRailTrigger). Single
 * dispatch site for CUSTOM targets and fixed destinations alike, rather than
 * each screen having its own copy of the SidebarItem when-block.
 *
 * Pure render — no focus/key handling of its own. Every other screen in this
 * app drives "focus" with plain state + the screen's own onPreviewKeyEvent
 * rather than real Compose focus (see XadarrFocusableSurface's
 * enableSystemFocus=false pattern used throughout); NavRail used to be the
 * one place fighting for genuine focus via FocusRequester, and it reliably
 * lost that fight — Home's own focus-recovery effect (and likely each other
 * screen's equivalent) kept reclaiming focus every time NavRail grabbed it,
 * so the rail looked open but never responded to anything but Back/Escape
 * (those were special-cased directly in the host screen). `focusedIndex` is
 * now hosted by the caller and driven by the caller's own key handler, same
 * as every row/card elsewhere in the app.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NavRail(
    isOpen: Boolean,
    onClose: () -> Unit,
    currentScreen: NavSectionKind?,
    navSections: List<NavSectionConfig>,
    neolinkConfigured: Boolean,
    actions: NavRailActions,
    // Home already renders every other destination as its own landscape "Your
    // library" row (see HomeViewModel.buildLibraryTilesCategory) — the rail
    // there only needs to cover what that row can't: Search and Settings.
    // Null (every other screen) shows the full list plus a Home entry.
    restrictToKinds: Set<NavSectionKind>? = null,
    // Which entry is selected, 0..entries.lastIndex. Owned and mutated by the
    // caller's own key handler; see navRailHandleKey().
    focusedIndex: Int = 0,
    // Plex-style avatar+name row at the top of the rail. Null (e.g. a screen that hasn't
    // resolved a profile yet) just omits the row — every other screen already has this in
    // scope for its own top-chrome avatar, so passing it through is a no-op for them.
    currentProfile: com.arflix.tv.data.model.Profile? = null,
) {
    if (!isOpen) return

    // Robust close on the system back gesture/button, not just the D-pad Back
    // key the caller's key handler processes — composed after (nested inside)
    // the screen's own BackHandler, so it takes priority while the rail is open.
    BackHandler(enabled = isOpen) { onClose() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val entries = computeNavRailEntries(currentScreen, navSections, neolinkConfigured, restrictToKinds)

    val railWidth by animateDpAsState(
        targetValue = if (isOpen) 260.dp else 0.dp,
        animationSpec = tween(220),
        label = "nav_rail_width"
    )

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(railWidth)
            .background(Color.Black.copy(alpha = 0.92f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(top = 64.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (currentProfile != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileAvatarVisual(
                        profile = currentProfile,
                        modifier = Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape),
                        letterFontSize = 14.sp,
                    )
                    Text(
                        text = currentProfile.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
            }
            entries.forEachIndexed { index, entry ->
                if (entry.kind == NavSectionKind.SETTINGS) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.10f))
                    )
                } else {
                    val label = sectionLabelFor(entry)
                    val prevLabel = entries.getOrNull(index - 1)?.let { if (it.kind == NavSectionKind.SETTINGS) null else sectionLabelFor(it) }
                    if (label != null && label != prevLabel) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                            color = Color.White.copy(alpha = 0.38f),
                            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 2.dp),
                        )
                    }
                }
                RailRow(
                    entry = entry,
                    isFocused = focusedIndex == index,
                    onClick = { onClose(); activateNavRailEntry(entry, actions, context) },
                )
            }
        }
    }
}

/**
 * Key handling extracted so the host screen's own onPreviewKeyEvent (which
 * reliably holds real focus, unlike NavRail itself — see NavRail's doc
 * comment) can drive rail navigation directly. Returns true if the key was
 * consumed. `focusedIndex` is a caller-owned mutable slot (0..entries.lastIndex).
 */
fun navRailHandleKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    entries: List<NavSectionConfig>,
    focusedIndex: androidx.compose.runtime.MutableState<Int>,
    onClose: () -> Unit,
    actions: NavRailActions,
    context: android.content.Context? = null,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val maxIndex = entries.lastIndex
    return when (event.key) {
        Key.Back, Key.Escape, Key.DirectionRight -> { onClose(); true }
        Key.DirectionUp -> {
            if (focusedIndex.value > 0) focusedIndex.value--
            true
        }
        Key.DirectionDown -> {
            if (focusedIndex.value < maxIndex) focusedIndex.value++
            true
        }
        Key.Enter, Key.DirectionCenter -> {
            entries.getOrNull(focusedIndex.value)?.let { onClose(); activateNavRailEntry(it, actions, context) }
            true
        }
        else -> false
    }
}

@Composable
private fun RailRow(entry: NavSectionConfig, isFocused: Boolean, onClick: () -> Unit) {
    val label = entry.label ?: entry.kind.toRailLabel().ifBlank { entry.customId.orEmpty() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(LiveDims.FocusBorder, if (isFocused) LiveColors.Accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = navEntryIcon(entry),
            contentDescription = null,
            tint = if (isFocused) Color.White else Color.White.copy(alpha = 0.65f),
            modifier = Modifier.width(20.dp)
        )
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isFocused) Color.White else Color.White.copy(alpha = 0.72f),
        )
    }
}
