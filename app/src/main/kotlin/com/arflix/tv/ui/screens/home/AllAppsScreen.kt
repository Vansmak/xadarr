package com.arflix.tv.ui.screens.home

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.repository.EpiseerrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.repository.Bookmark
import com.arflix.tv.data.repository.BOOKMARKS_KEY
import com.arflix.tv.data.repository.HIDDEN_QUICK_LINK_NAMES_KEY
import com.arflix.tv.data.repository.MOBILE_APPS_ALLOWLIST_KEY
import com.arflix.tv.data.repository.parseBookmarks
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.util.LocalDeviceType
import com.arflix.tv.util.settingsDataStore
import androidx.compose.material3.MaterialTheme
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AppEntry(val packageName: String, val label: String)

private fun launchApp(context: android.content.Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        ?: if (packageName == "com.android.tv.settings") {
            Intent(android.provider.Settings.ACTION_SETTINGS)
        } else null
    intent?.let { context.startActivity(it) }
}

// Mobile phones/tablets tend to have dozens of unrelated installed apps (banking, social,
// utilities) — "All Apps" there should mean "media apps", not literally everything. TV boxes
// rarely have that clutter, so this filter is mobile-only (see caller). Only used as the
// DEFAULT before the user has ever opened the app picker (Tune icon) and made an explicit
// choice — once MOBILE_APPS_ALLOWLIST_KEY has any stored value, that wins outright, same as
// TV's PINNED_APPS_KEY picker overriding any default.
private val MEDIA_APP_KEYWORDS = listOf(
    "plex", "jellyfin", "emby", "netflix", "youtube", "disney", "hulu", "hbomax",
    "primevideo", "amazonvideo", "peacock", "paramountplus", "appletv", "spotify",
    "vlc", "kodi", "mxplayer", "twitch", "plutotv", "tubitv", "crunchyroll", "espn",
    "nflmobile", "sling", "philo", "fubotv", "smarttube", "soundcloud", "tidal",
    "deezer", "amazonmusic", "youtubemusic", "starz", "showtime", "discoveryplus",
    "curiositystream", "britbox", "acorntv", "mubi", "shudder", "dazn", "nbcsports",
    "foxsports", "cbssports", "pandora", "iheart", "siriusxm", "vudu", "redbox",
    "xfinitystream", "directvstream", "hbogo", "viki", "funimation", "hoopladigital",
    "kanopy",
)

private fun isMediaApp(context: android.content.Context, packageName: String): Boolean {
    val normalized = packageName.lowercase().replace(".", "").replace("_", "")
    if (MEDIA_APP_KEYWORDS.any { normalized.contains(it) }) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val category = runCatching { context.packageManager.getApplicationInfo(packageName, 0).category }.getOrNull()
        if (category == ApplicationInfo.CATEGORY_VIDEO || category == ApplicationInfo.CATEGORY_AUDIO) return true
    }
    return false
}

// One entry in the merged grid — a bookmark (web shortcut) or a real installed app, rendered
// with matching tile styling so bookmarks read as "just another app" rather than a separate
// class of thing.
private sealed class AppsGridEntry {
    data class BookmarkEntry(val bookmark: Bookmark) : AppsGridEntry()
    data class InstalledApp(val entry: AppEntry) : AppsGridEntry()

    val key: String get() = when (this) {
        is BookmarkEntry -> "bm:${bookmark.name}"
        is InstalledApp -> entry.packageName
    }
}

@HiltViewModel
class AllAppsViewModel @Inject constructor(
    private val episeerrRepository: EpiseerrRepository,
) : ViewModel() {
    suspend fun getEpiseerrQuickLinks(): List<Bookmark> = episeerrRepository.getQuickLinks()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AllAppsScreen(onBack: () -> Unit = {}, viewModel: AllAppsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isTouchDevice = LocalDeviceType.current.isTouchDevice()
    var allInstalledApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var episeerrLinks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var hiddenQuickLinkNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    // null = never customized (fall back to the media-keyword heuristic below); non-null =
    // the user has explicitly picked apps via the Tune icon, even if that set is empty.
    var appsAllowlist by remember { mutableStateOf<Set<String>?>(null) }
    var webviewUrl by remember { mutableStateOf<String?>(null) }
    var showAppsPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val appList = withContext(Dispatchers.IO) {
            val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            (pm.queryIntentActivities(leanback, 0) + pm.queryIntentActivities(launcher, 0))
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != context.packageName }
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .sortedBy { it.label.lowercase() }
        }
        allInstalledApps = appList
        // Pre-load all icons in parallel so the grid renders with icons on first visit.
        withContext(Dispatchers.IO) {
            coroutineScope {
                appList.map { app ->
                    async {
                        if (!appIconCache.containsKey(app.packageName)) {
                            val bmp = runCatching { pm.getApplicationIcon(app.packageName) }
                                .getOrNull()
                                ?.toBitmap()
                                ?.asImageBitmap()
                            if (bmp != null) appIconCache[app.packageName] = bmp
                        }
                    }
                }.awaitAll()
            }
        }
        loading = false
    }

    // Bookmarks are either entered manually in Settings → Bookmarks, or pulled from
    // Episeerr's own Quick Links (Joe already maintains Sonarr/Radarr/Prowlarr/Dispatcharr
    // shortcuts there for its dashboard) — no need to re-type the same URLs in Xadarr too.
    LaunchedEffect(isTouchDevice) {
        if (isTouchDevice) {
            val prefs = context.settingsDataStore.data.first()
            bookmarks = parseBookmarks(prefs[BOOKMARKS_KEY].orEmpty())
            hiddenQuickLinkNames = prefs[HIDDEN_QUICK_LINK_NAMES_KEY]
                .orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            episeerrLinks = runCatching { viewModel.getEpiseerrQuickLinks() }.getOrDefault(emptyList())
            appsAllowlist = prefs[MOBILE_APPS_ALLOWLIST_KEY]?.let { stored ->
                stored.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            }
        }
    }

    fun setAppsAllowlist(updated: Set<String>) {
        appsAllowlist = updated
        coroutineScope.launch {
            context.settingsDataStore.edit { it[MOBILE_APPS_ALLOWLIST_KEY] = updated.joinToString(",") }
        }
    }

    // Manual entries win on a name clash — a user editing a bookmark locally shouldn't have
    // it silently overridden back by whatever Episeerr still has under the same name. Names
    // hidden in Settings → Bookmarks → From Episeerr are dropped entirely (e.g. Termix, which
    // Episeerr's dashboard needs but Xadarr's Apps tab doesn't).
    val displayedBookmarks = remember(bookmarks, episeerrLinks, hiddenQuickLinkNames) {
        val manualNames = bookmarks.mapTo(mutableSetOf()) { it.name.lowercase() }
        val visibleEpiseerrLinks = episeerrLinks.filterNot {
            it.name.lowercase() in manualNames || it.name.trim().lowercase() in hiddenQuickLinkNames
        }
        bookmarks + visibleEpiseerrLinks
    }

    val displayedApps = remember(allInstalledApps, isTouchDevice, appsAllowlist) {
        when {
            !isTouchDevice -> allInstalledApps
            appsAllowlist != null -> allInstalledApps.filter { it.packageName in appsAllowlist!! }
            else -> allInstalledApps.filter { isMediaApp(context, it.packageName) }
        }
    }

    val gridEntries = remember(displayedBookmarks, displayedApps, isTouchDevice) {
        if (isTouchDevice) {
            displayedBookmarks.map { AppsGridEntry.BookmarkEntry(it) } + displayedApps.map { AppsGridEntry.InstalledApp(it) }
        } else {
            displayedApps.map { AppsGridEntry.InstalledApp(it) }
        }
    }

    val gridFocus = remember { FocusRequester() }
    LaunchedEffect(loading) {
        if (!loading) runCatching { gridFocus.requestFocus() }
    }

    if (webviewUrl != null) {
        com.arflix.tv.ui.screens.episeerr.EpiseerrWebviewScreen(url = webviewUrl!!, onBack = { webviewUrl = null })
        return
    }

    if (showAppsPicker) {
        AppsPickerScreen(
            installedApps = allInstalledApps,
            selected = appsAllowlist ?: displayedApps.mapTo(mutableSetOf()) { it.packageName },
            onToggle = { pkg ->
                val current = appsAllowlist ?: displayedApps.mapTo(mutableSetOf()) { it.packageName }
                setAppsAllowlist(if (pkg in current) current - pkg else current + pkg)
            },
            onClose = { showAppsPicker = false },
        )
        return
    }

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark())
            .onKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.Escape) {
                    onBack(); true
                } else false
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
            ) {
                Text(
                    text = if (isTouchDevice) "Apps" else "All Apps",
                    fontSize = 22.sp,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (isTouchDevice) {
                    IconButton(onClick = { showAppsPicker = true }) {
                        Icon(Icons.Filled.Tune, "Choose which apps show here", tint = TextSecondary)
                    }
                }
            }
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Loading…",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().focusRequester(gridFocus)
                ) {
                    items(gridEntries, key = { it.key }) { gridEntry ->
                        when (gridEntry) {
                            is AppsGridEntry.BookmarkEntry -> BookmarkTileCard(
                                iconUrl = gridEntry.bookmark.icon,
                                label = gridEntry.bookmark.name,
                                isFocused = false,
                                onFocused = {},
                                enableSystemFocus = true,
                                onClick = { webviewUrl = gridEntry.bookmark.url },
                            )
                            is AppsGridEntry.InstalledApp -> AppLauncherCard(
                                packageName = gridEntry.entry.packageName,
                                label = gridEntry.entry.label,
                                isFocused = false,
                                onFocused = {},
                                enableSystemFocus = true,
                                onClick = { launchApp(context, gridEntry.entry.packageName) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// Mobile-only full-screen checklist — explicit "which installed apps show in the Apps tab"
// control, same idea as TV's Manage Apps (PINNED_APPS_KEY) picker but touch-friendly (real
// checkboxes, tappable rows) since ManageAppsModal is D-pad-only. Selecting anything here
// switches the Apps tab from the automatic media-keyword guess to this exact list permanently.
@Composable
private fun AppsPickerScreen(
    installedApps: List<AppEntry>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
) {
    // This screen is mobile-only (reached via the Tune icon, itself touch-only), and
    // MainActivity's persistent bottom bar stays visible the whole time it's open (it only
    // hides for player/profile/login routes — this is just internal AllAppsScreen state, not
    // a route change) — so a dedicated back arrow here is redundant with either that or the
    // system back gesture BackHandler already handles.
    BackHandler { onClose() }
    Box(modifier = Modifier.fillMaxSize().background(appBackgroundDark())) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Choose Apps",
                fontSize = 20.sp,
                color = TextPrimary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            Text(
                text = "Pick exactly which installed apps show in the Apps tab.",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(installedApps, key = { it.packageName }) { app ->
                    val isChecked = app.packageName in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(app.packageName) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = app.label,
                            fontSize = 15.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { onToggle(app.packageName) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = com.arflix.tv.ui.theme.Pink,
                                uncheckedColor = TextSecondary,
                            ),
                        )
                    }
                }
            }
        }
    }
}
