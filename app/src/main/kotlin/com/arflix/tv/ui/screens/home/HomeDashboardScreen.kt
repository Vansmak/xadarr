package com.arflix.tv.ui.screens.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.Bookmark
import com.arflix.tv.data.repository.BOOKMARKS_KEY
import com.arflix.tv.data.repository.EpiseerrRepository
import com.arflix.tv.data.repository.EpiseerrRecentlyWatched
import com.arflix.tv.data.repository.HIDDEN_QUICK_LINK_NAMES_KEY
import com.arflix.tv.data.repository.HomeServerRepository
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.PlexLibraryItem
import com.arflix.tv.data.repository.SonarrRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.data.repository.parseBookmarks
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.ui.screens.episeerr.EpiseerrWebviewScreen
import com.arflix.tv.ui.screens.tv.live.LiveColors
import com.arflix.tv.ui.screens.tv.live.LiveType
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.util.PlexDeepLink
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

data class ReadyToWatchItem(val plexItem: PlexLibraryItem, val subtitle: String)
data class UpcomingReleaseItem(val title: String, val year: Int?, val posterUrl: String?, val subtitle: String)

data class HomeDashboardUiState(
    val loading: Boolean = true,
    val readyToWatch: List<ReadyToWatchItem> = emptyList(),
    val watchlist: List<MediaItem> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val upcoming: List<UpcomingReleaseItem> = emptyList(),
)

private const val NEW_EPISODES_WINDOW_MS = 21L * 24 * 60 * 60 * 1000
private const val MATCH_POOL_LIMIT = 300
private const val FRESH_MATCH_POOL_LIMIT = 100

private fun normalizeTitle(s: String): String =
    s.lowercase().trim().replace(Regex("[^a-z0-9]+"), " ").trim()

@HiltViewModel
class HomeDashboardViewModel @Inject constructor(
    private val homeServerRepository: HomeServerRepository,
    private val episeerrRepository: EpiseerrRepository,
    private val sonarrRepository: SonarrRepository,
    private val mediaRepository: MediaRepository,
    private val watchlistRepository: WatchlistRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeDashboardUiState())
    val uiState: StateFlow<HomeDashboardUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = HomeDashboardUiState(loading = true)
            val readyDeferred = async { loadReadyToWatch() }
            val watchlistDeferred = async { loadWatchlist() }
            val bookmarksDeferred = async { loadBookmarks() }
            val upcomingDeferred = async { loadUpcoming() }
            _uiState.value = HomeDashboardUiState(
                loading = false,
                readyToWatch = readyDeferred.await(),
                watchlist = watchlistDeferred.await(),
                bookmarks = bookmarksDeferred.await(),
                upcoming = upcomingDeferred.await(),
            )
        }
    }

    private suspend fun loadReadyToWatch(): List<ReadyToWatchItem> {
        val pool = runCatching {
            homeServerRepository.loadPlexLibraryItems(MediaType.TV, offset = 0, limit = MATCH_POOL_LIMIT).items
        }.getOrDefault(emptyList())
        val freshPool = runCatching {
            homeServerRepository.loadPlexLibraryItems(
                MediaType.TV, offset = 0, limit = FRESH_MATCH_POOL_LIMIT, sortOverride = "episode.addedAt:desc",
            ).items
        }.getOrDefault(emptyList())
        if (pool.isEmpty() && freshPool.isEmpty()) return emptyList()
        val byNormalizedTitle = (pool + freshPool).distinctBy { it.ratingKey }.groupBy { normalizeTitle(it.title) }
        fun match(title: String): PlexLibraryItem? = byNormalizedTitle[normalizeTitle(title)]?.firstOrNull()

        val cutoff = System.currentTimeMillis() - NEW_EPISODES_WINDOW_MS
        val allSeries = runCatching { sonarrRepository.getAllSeries() }.getOrDefault(emptyList())
        val newEpisodes = allSeries
            .filter { (it.lastEpisodeAddedEpochMs ?: 0L) >= cutoff }
            .sortedByDescending { it.lastEpisodeAddedEpochMs }
            .mapNotNull { s -> match(s.title)?.let { ReadyToWatchItem(it, "New Episode") } }

        val newKeys = newEpisodes.mapTo(mutableSetOf()) { it.plexItem.ratingKey }
        val recentlyWatched: List<EpiseerrRecentlyWatched> =
            runCatching { episeerrRepository.getRecentlyWatched() }.getOrDefault(emptyList())
        val continueWatching = recentlyWatched.mapNotNull { w ->
            match(w.seriesTitle)?.takeIf { it.ratingKey !in newKeys }?.let { item ->
                val subtitle = listOfNotNull(w.season?.let { "S$it" }, w.episode?.let { "E$it" }).joinToString(" · ")
                ReadyToWatchItem(item, subtitle.ifBlank { "Continue Watching" })
            }
        }
        return (newEpisodes + continueWatching).take(12)
    }

    private suspend fun loadWatchlist(): List<MediaItem> =
        runCatching { watchlistRepository.getWatchlistItems() }.getOrDefault(emptyList()).take(12)

    private suspend fun loadBookmarks(): List<Bookmark> {
        val prefs = context.settingsDataStore.data.first()
        val manual = parseBookmarks(prefs[BOOKMARKS_KEY].orEmpty())
        val hidden = prefs[HIDDEN_QUICK_LINK_NAMES_KEY]
            .orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val episeerrLinks = runCatching { episeerrRepository.getQuickLinks() }.getOrDefault(emptyList())
        val manualNames = manual.mapTo(mutableSetOf()) { it.name.lowercase() }
        val visible = episeerrLinks.filterNot { it.name.lowercase() in manualNames || it.name.trim().lowercase() in hidden }
        return manual + visible
    }

    private suspend fun loadUpcoming(): List<UpcomingReleaseItem> {
        val calendar = runCatching { sonarrRepository.getCalendar(daysAhead = 30) }.getOrDefault(emptyList())
        return calendar
            .groupBy { it.seriesId }
            .mapNotNull { (_, entries) -> entries.minByOrNull { it.airDate } }
            .sortedBy { it.airDate }
            .take(12)
            .map { e ->
                UpcomingReleaseItem(
                    title = e.title,
                    year = null,
                    posterUrl = e.poster,
                    subtitle = "S${e.season} · E${e.episode} · ${formatAirDate(e.airDate)}",
                )
            }
    }

    suspend fun resolveTmdbId(title: String, year: Int?, mediaType: MediaType): Int? =
        mediaRepository.resolveTmdbId(title, year, mediaType)
}

private fun formatAirDate(iso: String): String = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val date = parser.parse(iso.take(10)) ?: return iso
    val today = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val target = java.util.Calendar.getInstance().apply { time = date }
    val diffDays = ((target.timeInMillis - today.timeInMillis) / (24 * 60 * 60 * 1000)).toInt()
    when (diffDays) {
        0 -> "Today"
        1 -> "Tomorrow"
        in 2..6 -> SimpleDateFormat("EEEE", Locale.US).format(date)
        else -> SimpleDateFormat("MMM d", Locale.US).format(date)
    }
}.getOrDefault(iso)

// ── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun HomeDashboardScreen(
    viewModel: HomeDashboardViewModel = hiltViewModel(),
    onNavigateToDetails: (MediaType, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    var webviewUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    if (webviewUrl != null) {
        EpiseerrWebviewScreen(url = webviewUrl!!, onBack = { webviewUrl = null })
        return
    }

    fun openTitle(title: String, year: Int?, plexItem: PlexLibraryItem?) {
        coroutineScope.launch {
            val tmdbId = viewModel.resolveTmdbId(title, year, MediaType.TV)
            if (tmdbId != null) {
                onNavigateToDetails(MediaType.TV, tmdbId)
            } else if (plexItem != null) {
                val intent = PlexDeepLink.launchIntent(context, plexItem.plexServerId, plexItem.ratingKey)
                if (intent != null) context.startActivity(intent)
                else Toast.makeText(context, "Plex isn't installed on this device", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Couldn't find \"$title\"", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler { /* Home is the mobile landing screen — nothing above it to pop to. */ }

    Box(modifier = Modifier.fillMaxSize().background(LiveColors.Bg)) {
        if (uiState.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator(color = LiveColors.Accent)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                androidx.compose.foundation.lazy.LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item {
                        androidx.tv.material3.Text(
                            text = "Home",
                            fontSize = 22.sp,
                            color = TextPrimary,
                            modifier = Modifier.padding(start = 24.dp, top = 24.dp),
                        )
                    }
                    if (uiState.readyToWatch.isNotEmpty()) {
                        item {
                            DashboardRow(title = "Ready to Watch") {
                                items(uiState.readyToWatch, key = { "ready:${it.plexItem.ratingKey}" }) { ready ->
                                    PosterTile(
                                        posterUrl = ready.plexItem.posterUrl,
                                        title = ready.plexItem.title,
                                        subtitle = ready.subtitle,
                                        onClick = { openTitle(ready.plexItem.title, ready.plexItem.year, ready.plexItem) },
                                    )
                                }
                            }
                        }
                    }
                    if (uiState.watchlist.isNotEmpty()) {
                        item {
                            DashboardRow(title = "Watchlist") {
                                items(uiState.watchlist, key = { "wl:${it.mediaType}:${it.id}" }) { watchlistItem ->
                                    PosterTile(
                                        posterUrl = watchlistItem.image,
                                        title = watchlistItem.title,
                                        subtitle = watchlistItem.year.ifBlank { "Watchlist" },
                                        onClick = { onNavigateToDetails(watchlistItem.mediaType, watchlistItem.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (uiState.bookmarks.isNotEmpty()) {
                        item {
                            DashboardRow(title = "Bookmarks") {
                                items(uiState.bookmarks, key = { "bm:${it.name}" }) { bookmark ->
                                    BookmarkTileCard(
                                        iconUrl = bookmark.icon,
                                        label = bookmark.name,
                                        isFocused = false,
                                        onFocused = {},
                                        onClick = { webviewUrl = bookmark.url },
                                    )
                                }
                            }
                        }
                    }
                    if (uiState.upcoming.isNotEmpty()) {
                        item {
                            DashboardRow(title = "Upcoming Releases") {
                                items(uiState.upcoming, key = { "up:${it.title}:${it.subtitle}" }) { upcoming ->
                                    PosterTile(
                                        posterUrl = upcoming.posterUrl,
                                        title = upcoming.title,
                                        subtitle = upcoming.subtitle,
                                        onClick = { openTitle(upcoming.title, upcoming.year, null) },
                                    )
                                }
                            }
                        }
                    }
                    if (uiState.readyToWatch.isEmpty() && uiState.watchlist.isEmpty() && uiState.bookmarks.isEmpty() && uiState.upcoming.isEmpty()) {
                        item {
                            androidx.tv.material3.Text(
                                text = "Nothing to show yet — check your Plex/Sonarr/Episeerr connections in Settings.",
                                color = TextSecondary,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        androidx.tv.material3.Text(
            text = title,
            color = TextPrimary,
            style = LiveType.SectionTag,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun PosterTile(
    posterUrl: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(LiveColors.Panel)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(LiveColors.PanelDeep)) {
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.Movie, null, tint = LiveColors.FgMute,
                    modifier = Modifier.padding(30.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            androidx.tv.material3.Text(
                text = title,
                color = LiveColors.Fg,
                style = LiveType.CatLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.tv.material3.Text(
                text = subtitle,
                color = LiveColors.Accent,
                style = LiveType.Badge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
