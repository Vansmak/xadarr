package com.arflix.tv.ui.screens.home

import android.app.ActivityManager
import android.content.Context
import com.arflix.tv.util.settingsDataStore
import androidx.compose.runtime.mutableStateMapOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogKind
import com.arflix.tv.data.model.CatalogPlacement
import com.arflix.tv.data.model.CollectionGroupKind
import com.arflix.tv.data.model.LibraryBrowseEntry
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.TraktSyncService
import com.arflix.tv.data.repository.ContinueWatchingItem
import com.arflix.tv.data.repository.CatalogRepository
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.LauncherContinueWatchingRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.data.repository.NeolinkRepository
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.HomeServerRepository
import com.arflix.tv.data.repository.CloudSyncStatus
import com.arflix.tv.data.repository.CollectionTemplateManifest
import com.arflix.tv.data.repository.WatchHistoryRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.util.AppLogger
import com.arflix.tv.util.Constants
import com.arflix.tv.util.DeviceType
import com.arflix.tv.util.LAST_APP_LANGUAGE_KEY
import com.arflix.tv.util.detectDeviceType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val isInitialLoad: Boolean = true,
    val categories: List<Category> = emptyList(),
    val collectionRows: List<HomeCollectionRow> = emptyList(),
    val error: String? = null,
    // Current hero (may update during transitions)
    val heroItem: MediaItem? = null,
    val heroLogoUrl: String? = null,
    val heroTrailerKey: String? = null,
    val trailerAutoPlay: Boolean = false,
    val trailerSoundEnabled: Boolean = false,
    val trailerDelaySeconds: Int = 2,
    // Home hero metadata visibility toggles (issue #72)
    val showBudget: Boolean = true,
    val heroOverviewOverride: String? = null,
    val cardLogoUrls: Map<String, String> = emptyMap(),
    // Previous hero for crossfade (Phase 2.1)
    val previousHeroItem: MediaItem? = null,
    val previousHeroLogoUrl: String? = null,
    // Transition state for animations
    val isHeroTransitioning: Boolean = false,
    val isAuthenticated: Boolean = false,
    val clockFormat: String = "24h",
    // Cloud sync status for the indicator on the home top bar
    val syncStatus: com.arflix.tv.data.repository.CloudSyncStatus = com.arflix.tv.data.repository.CloudSyncStatus.NOT_SIGNED_IN,
    // Toast
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.INFO,
    // App Updates
    val updateStatus: com.arflix.tv.updater.UpdateStatus = com.arflix.tv.updater.UpdateStatus.Idle,
    val showAppUpdateDialog: Boolean = false,
    val hasUpdateBadge: Boolean = false,
    // EPG data for the Live TV / On Now home row, keyed by MediaItem stable ID.
    // Updated by refreshFavoriteTvEpg() whenever now/next data changes.
    val liveChannelEpg: Map<Int, com.arflix.tv.data.model.IptvNowNext> = emptyMap(),
    val launcherModeEnabled: Boolean = false,
)

data class HomeCollectionRow(
    val id: String,
    val title: String,
    val items: List<CatalogConfig>
)

enum class ToastType {
    SUCCESS, ERROR, INFO
}

private val ALPHANUMERIC_REGEX = Regex("[^A-Za-z0-9_.-]")
private val FILE_NAME_REGEX = Regex("[^a-zA-Z0-9._-]")

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val catalogRepository: CatalogRepository,
    private val streamRepository: StreamRepository,
    private val traktRepository: TraktRepository,
    private val traktSyncService: TraktSyncService,
    private val neolinkRepository: NeolinkRepository,
    private val iptvRepository: IptvRepository,
    private val homeServerRepository: HomeServerRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val watchlistRepository: WatchlistRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val launcherContinueWatchingRepository: LauncherContinueWatchingRepository,
    private val realtimeSyncManager: com.arflix.tv.data.repository.RealtimeSyncManager,
    private val profileManager: ProfileManager,
    private val appUpdateRepository: com.arflix.tv.updater.AppUpdateRepository,
    private val apkDownloader: com.arflix.tv.updater.ApkDownloader,
    private val updatePreferences: com.arflix.tv.updater.UpdatePreferences,
    private val updateStatusManager: com.arflix.tv.updater.UpdateStatusManager,
    private val youTubeExtractor: com.arflix.tv.data.api.InAppYouTubeExtractor,
    private val cwHolder: com.arflix.tv.data.repository.ContinueWatchingHolder,
    private val sonarrRepository: com.arflix.tv.data.repository.SonarrRepository,
    private val radarrRepository: com.arflix.tv.data.repository.RadarrRepository,
    private val homeLayoutRepository: com.arflix.tv.data.repository.HomeLayoutRepository,
    private val navSectionRepository: com.arflix.tv.data.repository.NavSectionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val imageLoader: ImageLoader by lazy(LazyThreadSafetyMode.NONE) {
        context.imageLoader
    }

    private data class HeroDetailsSnapshot(
        val duration: String,
        val releaseDate: String?,
        val imdbRating: String,
        val tmdbRating: String,
        val budget: Long?,
        val overview: String,
        val primaryNetworkLogo: String? = null
    )

    private data class CategoryPaginationState(
        var loadedCount: Int = 0,
        var hasMore: Boolean = true,
        var isLoading: Boolean = false
    )

    // IPTV favorite channels — maps MediaItem.id (Int hash) to channel / EPG data
    private val iptvChannelMap = mutableMapOf<Int, com.arflix.tv.data.model.IptvChannel>()
    private val iptvNowNextMap = mutableMapOf<Int, com.arflix.tv.data.model.IptvNowNext>()
    // Neolink cameras — maps MediaItem.id (camera name hash) to NeolinkCamera
    private val cameraMap = mutableMapOf<Int, NeolinkRepository.NeolinkCamera>()
    // Neolink events — maps MediaItem.id to clip URL
    private val neolinkEventClipMap = mutableMapOf<Int, String>()
    // Last successfully built On Now category — used as fallback when loadHomeData()
    // races with a snapshot refresh and buildFavoriteTvCategory() transiently returns null.
    @Volatile private var lastFavoriteTvCategory: Category? = null
    // home_layout.hero/footer, cached from HomeLayoutRepository's reactive flow so
    // chooseInitialHero() (a plain function called from several sync code paths) can
    // read it without becoming suspend. Defaults match HomeLayoutConfig()'s defaults.
    @Volatile private var cachedHomeLayout: com.arflix.tv.data.model.HomeLayoutConfig =
        com.arflix.tv.data.model.HomeLayoutConfig()

    companion object {
        const val FAVORITE_TV_CATEGORY_ID = "favorite_tv"
        const val WATCHLIST_CATEGORY_ID = "my_watchlist"
        const val APPS_CATEGORY_ID = "installed_apps"
        const val CAMERAS_CATEGORY_ID = "cameras"
        const val NEOLINK_EVENTS_CATEGORY_ID = "frigate_events"
        const val LIBRARY_TILES_CATEGORY_ID = "your_library"
        const val UP_NEXT_CATEGORY_ID = "up_next"
        /** Design v4 §3 — Home's one configurable row, default matches pre-v4 behavior. */
        const val DEFAULT_HOME_ROW_SELECTION = "continue_watching"
        /** Target Continue Watching row length - Sonarr-calendar filler tops it up to this when real CW items fall short. */
        const val CW_TARGET_ROW_SIZE = 15
        /** Chunk size for progressively resolving the Sonarr/Radarr "All Shows"/"All Movies" library browse list. */
        const val LIBRARY_BROWSE_CHUNK_SIZE = 16
        /** Prefix used in MediaItem.status to identify IPTV items. */
        const val IPTV_STATUS_PREFIX = "iptv:"
        const val CAMERA_STATUS_PREFIX = "camera:"
        const val NEOLINK_EVENT_STATUS_PREFIX = "frigate_event:"
        private const val TOP_10_ITEM_LIMIT = 10
        private const val HOME_ROW_ITEM_CAP = 15
        private val HARD_CAPPED_TOP_10_CATALOG_IDS = setOf(
            "top10_movies_today",
            "top10_shows_today"
        )
    }

    /** Check if a MediaItem represents an IPTV channel. */
    fun isIptvItem(item: MediaItem): Boolean = item.status?.startsWith(IPTV_STATUS_PREFIX) == true

    fun isCollectionItem(item: MediaItem): Boolean = item.status?.startsWith("collection:") == true

    fun isCameraItem(item: MediaItem): Boolean = item.status?.startsWith(CAMERA_STATUS_PREFIX) == true
    fun isNeolinkEventItem(item: MediaItem): Boolean = item.status?.startsWith(NEOLINK_EVENT_STATUS_PREFIX) == true

    fun getCameraStreamUrl(itemId: Int): String? = cameraMap[itemId]?.streamUrl
    fun getNeolinkEventClipUrl(itemId: Int): String? = neolinkEventClipMap[itemId]
    fun getCameraName(item: MediaItem): String? =
        item.status?.removePrefix(CAMERA_STATUS_PREFIX)
            ?.takeIf { item.status?.startsWith(CAMERA_STATUS_PREFIX) == true && it.isNotBlank() }

    /** Returns the service / franchise hero-video URL for a focused collection tile, or null. */
    fun getCollectionHeroVideoUrl(item: MediaItem): String? {
        if (!isCollectionItem(item)) return null
        return collectionCatalogByMediaId[item.id]?.collectionHeroVideoUrl
            ?.takeIf { it.isNotBlank() }
    }

    fun getCollectionHeroImageUrl(item: MediaItem): String? {
        if (!isCollectionItem(item)) return null
        return collectionCatalogByMediaId[item.id]?.collectionHeroImageUrl
            ?.takeIf { it.isNotBlank() }
            ?: collectionCatalogByMediaId[item.id]?.collectionCoverImageUrl?.takeIf { it.isNotBlank() }
    }

    private fun isActionableMediaItem(item: MediaItem): Boolean {
        // Non-actionable items are expected during filtering: invalid IDs cannot be opened,
        // placeholders are synthetic UI entries, and collection tiles use their own handling.
        return item.id > 0 && !item.isPlaceholder && !isCollectionItem(item)
    }

    private fun continueWatchingKey(mediaType: MediaType, id: Int): String {
        return "${mediaType.name}:$id"
    }

    private fun isHardCappedTop10Catalog(categoryId: String): Boolean {
        return categoryId in HARD_CAPPED_TOP_10_CATALOG_IDS
    }

    private fun Category.withTop10CapIfNeeded(): Category {
        return if (isHardCappedTop10Catalog(id) && items.size > TOP_10_ITEM_LIMIT) {
            copy(items = items.take(TOP_10_ITEM_LIMIT))
        } else {
            this
        }
    }

    private fun catalogInitialLimit(catalog: CatalogConfig): Int {
        return if (isHardCappedTop10Catalog(catalog.id)) TOP_10_ITEM_LIMIT else initialCategoryItemCap
    }

    private fun continueWatchingShowKey(item: ContinueWatchingItem): String {
        return continueWatchingKey(item.mediaType, item.id)
    }

    private fun parseContinueWatchingUpdatedAt(primary: String?, fallback: String? = null): Long {
        fun parse(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            return try {
                java.time.Instant.parse(value).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
        return maxOf(parse(primary), parse(fallback))
    }

    private fun mergeContinueWatchingVisuals(
        preferred: ContinueWatchingItem,
        fallback: ContinueWatchingItem
    ): ContinueWatchingItem {
        return preferred.copy(
            title = preferred.title.ifBlank { fallback.title },
            episodeTitle = preferred.episodeTitle ?: fallback.episodeTitle,
            backdropPath = preferred.backdropPath ?: fallback.backdropPath,
            posterPath = preferred.posterPath ?: fallback.posterPath,
            streamKey = preferred.streamKey ?: fallback.streamKey,
            streamAddonId = preferred.streamAddonId ?: fallback.streamAddonId,
            streamTitle = preferred.streamTitle ?: fallback.streamTitle,
            year = preferred.year.ifBlank { fallback.year },
            releaseDate = preferred.releaseDate.ifBlank { fallback.releaseDate },
            overview = preferred.overview.ifBlank { fallback.overview },
            imdbRating = preferred.imdbRating.ifBlank { fallback.imdbRating },
            duration = preferred.duration.ifBlank { fallback.duration },
            durationSeconds = maxOf(preferred.durationSeconds, fallback.durationSeconds),
            budget = preferred.budget ?: fallback.budget,
            totalEpisodes = if (preferred.totalEpisodes > 0) preferred.totalEpisodes else fallback.totalEpisodes,
            watchedEpisodes = if (preferred.watchedEpisodes > 0) preferred.watchedEpisodes else fallback.watchedEpisodes,
            updatedAtMs = maxOf(preferred.updatedAtMs, fallback.updatedAtMs)
        )
    }

    private fun needsContinueWatchingArtworkRepair(item: ContinueWatchingItem): Boolean {
        return item.posterPath.isNullOrBlank() ||
            item.backdropPath.isNullOrBlank() ||
            item.overview.isBlank() ||
            item.durationSeconds <= 0L
    }

    private suspend fun repairContinueWatchingMetadataIfNeeded(
        items: List<ContinueWatchingItem>
    ): List<ContinueWatchingItem> {
        if (items.none(::needsContinueWatchingArtworkRepair)) return items
        return runCatching {
            traktRepository.enrichContinueWatchingItems(items)
                .zip(items) { enriched, original ->
                    mergeContinueWatchingVisuals(enriched, original)
                }
                .ifEmpty { items }
        }.onFailure { error ->
            AppLogger.recordException(
                throwable = error,
                context = mapOf(
                    "error_area" to "ContinueWatching",
                    "cw_phase" to "metadata_repair"
                )
            )
        }.getOrDefault(items)
    }

    private fun mergeTraktAndRecentLocalContinueWatching(
        traktItems: List<ContinueWatchingItem>,
        localItems: List<ContinueWatchingItem>,
        historyItems: List<ContinueWatchingItem>
    ): List<ContinueWatchingItem> {
        val freshestLocalByExactEpisode = (localItems + historyItems)
            .groupBy { item ->
                "${item.mediaType}:${item.id}:${item.season ?: -1}:${item.episode ?: -1}"
            }
            .mapValues { (_, candidates) ->
                candidates.maxWithOrNull(
                    compareBy<ContinueWatchingItem> { it.updatedAtMs }
                        .thenBy { it.resumePositionSeconds }
                        .thenBy { it.progress }
                )
            }

        return traktItems.map { traktItem ->
            val exactKey = "${traktItem.mediaType}:${traktItem.id}:${traktItem.season ?: -1}:${traktItem.episode ?: -1}"
            val local = freshestLocalByExactEpisode[exactKey]
            if (local == null) {
                traktItem
            } else {
                mergeContinueWatchingVisuals(
                    preferred = traktItem.copy(
                        resumePositionSeconds = maxOf(traktItem.resumePositionSeconds, local.resumePositionSeconds),
                        durationSeconds = maxOf(traktItem.durationSeconds, local.durationSeconds),
                        progress = maxOf(traktItem.progress, local.progress)
                    ),
                    fallback = local
                )
            }
        }
    }

    private fun overviewLooksTruncated(overview: String): Boolean {
        val value = overview.trim()
        return value.isBlank() || value.endsWith("...") || value.length < 140
    }

    private suspend fun resolveBestOverview(item: MediaItem, candidateOverview: String): String {
        val base = candidateOverview.trim()
        if (!overviewLooksTruncated(base) || item.title.isBlank()) {
            return base
        }

        val searchMatches = runCatching { mediaRepository.search(item.title) }.getOrDefault(emptyList())
        val bestMatch = searchMatches
            .asSequence()
            .filter { match -> match.overview.isNotBlank() }
            .maxByOrNull { match ->
                val titleBonus = if (match.title.equals(item.title, ignoreCase = true)) 1_000 else 0
                val typeBonus = if (match.mediaType == item.mediaType) 120 else 0
                val qualityBonus = if (!overviewLooksTruncated(match.overview)) 80 else 0
                titleBonus + typeBonus + qualityBonus + match.overview.length
            }

        val improved = bestMatch?.overview?.trim().orEmpty()
        return if (
            improved.isNotBlank() &&
            (improved.length > base.length || (!improved.endsWith("...") && base.endsWith("...")))
        ) {
            improved
        } else {
            base
        }
    }

    private suspend fun resolvePrimaryNetworkLogo(mediaType: MediaType, mediaId: Int): String? {
        return runCatching {
            mediaRepository.getStreamingServices(
                mediaType = mediaType,
                mediaId = mediaId,
                preferredRegion = Locale.getDefault().country
            )
        }.getOrNull()
            ?.services
            ?.firstOrNull()
            ?.logoUrl
            ?.takeIf { it.isNotBlank() }
    }

    private fun isEpisodeAlreadyAired(rawAirDate: String): Boolean {
        val value = rawAirDate.trim()
        if (value.isEmpty()) return true
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            parser.isLenient = false
            val parsed = parser.parse(value) ?: return true
            parsed.time <= System.currentTimeMillis()
        } catch (_: Exception) {
            true
        }
    }

    private suspend fun sanitizeContinueWatchingItems(items: List<ContinueWatchingItem>): List<ContinueWatchingItem> {
        if (items.isEmpty()) return emptyList()

        val seasonEpisodesCache = HashMap<Pair<Int, Int>, List<com.arflix.tv.data.model.Episode>?>()

        return items.mapNotNull { item ->
            if (item.mediaType != MediaType.TV) {
                return@mapNotNull item
            }

            val season = item.season
            val episode = item.episode
            if (season == null || episode == null) {
                return@mapNotNull item
            }

            val cacheKey = item.id to season
            val seasonEpisodes = if (seasonEpisodesCache.containsKey(cacheKey)) {
                seasonEpisodesCache[cacheKey]
            } else {
                val fetched = runCatching {
                    mediaRepository.getSeasonEpisodes(item.id, season)
                }.getOrNull()
                seasonEpisodesCache[cacheKey] = fetched
                fetched
            }

            // If TMDB fetch failed, keep the item rather than silently dropping it.
            if (seasonEpisodes == null) {
                return@mapNotNull item
            }

            // If TMDB doesn't have episodes for this season yet (e.g., a brand-new
            // season premiere that Trakt knows about but TMDB hasn't indexed), keep
            // the item. Trakt's progress API is authoritative for "what to watch next."
            // The previous code dropped these items, causing shows with new season
            // premieres to silently disappear from Continue Watching.
            if (seasonEpisodes.isEmpty()) {
                return@mapNotNull item // Keep — TMDB may not have the season data yet
            }

            val matchedEpisode = seasonEpisodes.firstOrNull { it.episodeNumber == episode }
            if (matchedEpisode == null) {
                return@mapNotNull item // Keep — episode may not be on TMDB yet
            }

            if (!isEpisodeAlreadyAired(matchedEpisode.airDate)) {
                return@mapNotNull null
            }

            item.copy(
                episodeTitle = item.episodeTitle ?: matchedEpisode.name
            )
        }
    }

    /** Extract the IPTV channel ID from a MediaItem's status field. */
    fun getIptvChannelId(item: MediaItem): String? =
        item.status?.removePrefix(IPTV_STATUS_PREFIX)?.takeIf { it.isNotBlank() }

    /** Get the stream URL for an IPTV MediaItem. */
    fun getIptvStreamUrl(itemId: Int): String? = iptvChannelMap[itemId]?.streamUrl

    /** Get the current EPG (now/next) for an IPTV MediaItem. Updated by the EPG refresh timer. */
    fun getLiveChannelEpg(itemId: Int): com.arflix.tv.data.model.IptvNowNext? = iptvNowNextMap[itemId]

    private fun iptvChannelToMediaItem(
        channel: com.arflix.tv.data.model.IptvChannel,
        epg: com.arflix.tv.data.model.IptvNowNext?
    ): MediaItem {
        val stableId = channel.id.hashCode() and 0x7FFFFFFF
        iptvChannelMap[stableId] = channel
        if (epg != null) iptvNowNextMap[stableId] = epg else iptvNowNextMap.remove(stableId)

        val nowProgram = epg?.now
        val nextProgram = epg?.next ?: epg?.later ?: epg?.upcoming?.firstOrNull()
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        // Time range intentionally NOT baked in here — the Home hero (HeroSection
        // in HomeScreen.kt) now renders the current program's start-end time as
        // its own dedicated element next to the progress bar; including it here
        // too would duplicate it in the overview text shown right below.
        val overviewParts = mutableListOf<String>()
        if (nowProgram != null) {
            overviewParts.add("Now: ${nowProgram.title}")
            if (!nowProgram.description.isNullOrBlank()) {
                overviewParts.add(nowProgram.description)
            }
        }
        if (nextProgram != null) {
            val nextStart = timeFmt.format(java.util.Date(nextProgram.startUtcMillis))
            overviewParts.add("Next: $nextStart  ${nextProgram.title}")
        }

        return MediaItem(
            id = stableId,
            title = channel.name,
            subtitle = channel.group,
            overview = overviewParts.joinToString("\n").ifBlank { "Live TV" },
            mediaType = MediaType.TV,
            image = channel.logo ?: "",
            backdrop = channel.logo,
            badge = "LIVE",
            status = "$IPTV_STATUS_PREFIX${channel.id}",
            isOngoing = true
        )
    }

    private val defaultPinnedApps = listOf(
        "org.smarttube.stable",
        "org.smarttube.beta",
        "com.cxinventor.file.explorer",
        "com.android.tv.settings",
    )

    private fun buildInstalledAppsCategory(): Category {
        val pm = context.packageManager
        val stored = runCatching {
            kotlinx.coroutines.runBlocking {
                context.settingsDataStore.data.first()[com.arflix.tv.data.repository.PINNED_APPS_KEY]
            }
        }.getOrNull()
        val pinned = if (!stored.isNullOrBlank()) {
            stored.split(",").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            defaultPinnedApps
        }
        var apps = pinned.mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                com.arflix.tv.data.model.MediaItem(
                    id = pkg.hashCode(),
                    title = label,
                    status = "app:$pkg",
                    mediaType = com.arflix.tv.data.model.MediaType.MOVIE
                )
            }.getOrNull()
        }
        if (apps.isEmpty()) {
            val leanbackIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
            val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            apps = (pm.queryIntentActivities(leanbackIntent, 0) + pm.queryIntentActivities(launcherIntent, 0))
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != context.packageName }
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .sortedBy { it.second.lowercase() }
                .map { (pkg, label) ->
                    com.arflix.tv.data.model.MediaItem(
                        id = pkg.hashCode(),
                        title = label,
                        status = "app:$pkg",
                        mediaType = com.arflix.tv.data.model.MediaType.MOVIE
                    )
                }
        }
        val allAppsTile = com.arflix.tv.data.model.MediaItem(
            id = "all_apps".hashCode(),
            title = "All Apps",
            status = "all_apps",
            mediaType = com.arflix.tv.data.model.MediaType.MOVIE
        )
        val settingsTile = com.arflix.tv.data.model.MediaItem(
            id = "settings_tile".hashCode(),
            title = "Settings",
            status = "settings",
            mediaType = com.arflix.tv.data.model.MediaType.MOVIE
        )
        return Category(id = APPS_CATEGORY_ID, title = "Apps", items = apps + settingsTile + allAppsTile)
    }

    /**
     * "Your library" tile row — Home's presentation of navSectionsByProfile
     * (the same config NavRail shows vertically on other screens; see
     * NavRail.kt). Home never shows itself; Search and Settings live in the
     * restricted NavRail instead (opened via LEFT from this row) since neither
     * has meaningful "dynamic content" to put on a landscape card.
     * Encodes each tile's identity into MediaItem.status as "navtile:kind:<KIND>"
     * or "navtile:custom:<customId>" — dispatched in HomeScreen's onItemClick.
     * Each tile's image/backdrop is best-effort borrowed from whatever's
     * already loaded in [categories] for that destination (on-now channel,
     * first camera, first watchlist item, first matching movie/show) — no
     * extra fetches, so a tile with nothing loaded yet just falls back to
     * MediaCard's plain gradient background.
     */
    private fun buildLibraryTilesCategory(
        navSections: List<com.arflix.tv.data.model.NavSectionConfig>,
        categories: List<Category>,
    ): Category? {
        val entries = navSections.filter {
            it.visible &&
                it.kind != com.arflix.tv.data.model.NavSectionKind.HOME &&
                it.kind != com.arflix.tv.data.model.NavSectionKind.SEARCH &&
                it.kind != com.arflix.tv.data.model.NavSectionKind.SETTINGS
        }.sortedBy { it.order }
        if (entries.isEmpty()) return null
        fun firstItem(categoryId: String) = categories.firstOrNull { it.id == categoryId }?.items?.firstOrNull()
        // Excludes itself — on a recompute, categories already contains the
        // previous tile row, and every tile's MediaItem is tagged MOVIE, which
        // would otherwise make the "movies" tile a mirror of some other tile.
        val otherCategories = categories.filter { it.id != LIBRARY_TILES_CATEGORY_ID }
        val items = entries.map { entry ->
            val isCustom = entry.kind == com.arflix.tv.data.model.NavSectionKind.CUSTOM
            val statusId = if (isCustom) "navtile:custom:${entry.customId}" else "navtile:kind:${entry.kind.name}"
            val label = entry.label ?: entry.customId?.replaceFirstChar { it.uppercase() } ?: entry.kind.name
            val sourceItem = when {
                entry.kind == com.arflix.tv.data.model.NavSectionKind.TV -> firstItem(FAVORITE_TV_CATEGORY_ID)
                entry.kind == com.arflix.tv.data.model.NavSectionKind.CAMERAS -> firstItem(CAMERAS_CATEGORY_ID)
                isCustom && entry.customId == "watchlist" -> firstItem(WATCHLIST_CATEGORY_ID)
                isCustom && entry.customId == "movies" ->
                    otherCategories.asSequence().flatMap { it.items }.firstOrNull { it.mediaType == com.arflix.tv.data.model.MediaType.MOVIE }
                isCustom && entry.customId == "shows" ->
                    otherCategories.asSequence().flatMap { it.items }.firstOrNull { it.mediaType == com.arflix.tv.data.model.MediaType.TV }
                else -> null
            }
            com.arflix.tv.data.model.MediaItem(
                id = statusId.hashCode(),
                title = label,
                status = statusId,
                mediaType = com.arflix.tv.data.model.MediaType.MOVIE,
                image = sourceItem?.image.orEmpty(),
                backdrop = sourceItem?.backdrop ?: sourceItem?.image,
            )
        }
        return Category(id = LIBRARY_TILES_CATEGORY_ID, title = "Your library", items = items)
    }

    private suspend fun buildCamerasCategory(): Category? {
        val cameras = neolinkRepository.getCameras()
        if (cameras.isEmpty()) return null
        val items = cameras.map { camera ->
            val stableId = camera.name.hashCode()
            cameraMap[stableId] = camera
            com.arflix.tv.data.model.MediaItem(
                id = stableId,
                title = camera.displayName,
                subtitle = "Camera",
                status = "$CAMERA_STATUS_PREFIX${camera.name}",
                image = camera.snapshotUrl,
                mediaType = com.arflix.tv.data.model.MediaType.MOVIE,
                isOngoing = true,
            )
        }
        return Category(id = CAMERAS_CATEGORY_ID, title = "Cameras", items = items)
    }

    private suspend fun buildFavoriteTvCategory(): Category? {
        // Use non-blocking memory read first; fall back to mutex-guarded disk read
        val snapshot = iptvRepository.getMemoryCachedSnapshot()
            ?: return null
        val favoriteIds = snapshot.favoriteChannels.toHashSet()
        if (favoriteIds.isEmpty()) return null

        // Re-derive now/next from cached programs so "Now" shifts when a program ends.
        // This is free (no network) — just recalculates which program is live.
        val favoriteChannelIds = snapshot.channels
            .filter { favoriteIds.contains(it.id) }
            .map { it.id }
            .toSet()
        iptvRepository.reDeriveCachedNowNext(favoriteChannelIds)
        // Re-read snapshot after re-derive to get updated nowNext
        val freshSnapshot = iptvRepository.getMemoryCachedSnapshot() ?: snapshot

        // Iterate channels in their original list order (matching TV page order)
        val items = freshSnapshot.channels
            .filter { favoriteIds.contains(it.id) }
            .mapNotNull { channel ->
                val epg = freshSnapshot.nowNext[channel.id]
                iptvChannelToMediaItem(channel, epg)
            }
        if (items.isEmpty()) return null

        return Category(
            id = FAVORITE_TV_CATEGORY_ID,
            title = "On Now",
            items = items
        ).also { lastFavoriteTvCategory = it }
    }

    private fun isCustomCatalogConfig(cfg: CatalogConfig): Boolean {
        if (cfg.kind == CatalogKind.COLLECTION || cfg.kind == CatalogKind.COLLECTION_RAIL) {
            return false
        }
        return !cfg.isPreinstalled ||
            cfg.id.startsWith("custom_") ||
            !cfg.sourceUrl.isNullOrBlank() ||
            !cfg.sourceRef.isNullOrBlank()
    }

    private fun isCollectionRailConfig(cfg: CatalogConfig): Boolean = cfg.kind == CatalogKind.COLLECTION_RAIL

    private fun isCollectionTileConfig(cfg: CatalogConfig): Boolean = cfg.kind == CatalogKind.COLLECTION

    private fun collectionRowId(group: CollectionGroupKind): String {
        return "collection_row_${group.name.lowercase(Locale.US)}"
    }

    private fun hasRealItems(category: Category?): Boolean {
        return category?.items?.any { !it.isPlaceholder } == true
    }

    private fun chooseInitialHero(categories: List<Category>): MediaItem? {
        // hero.type == "live_resume": prefer the On Now row's current item (the
        // last-played/favorited live channel) as the idle hero, same MediaItem
        // shape and HomeHeroLayer rendering as any other item — just a different
        // pick. "none" (or anything unrecognized) falls back to today's behavior.
        if (cachedHomeLayout.hero.type == "live_resume") {
            val onNowItem = (categories.firstOrNull { it.id == FAVORITE_TV_CATEGORY_ID } ?: lastFavoriteTvCategory)
                ?.items?.firstOrNull { !it.isPlaceholder }
            if (onNowItem != null) return onNowItem
        }
        val preferredRow = categories.firstOrNull { category ->
            !category.id.startsWith("collection_row_") && category.items.any { !it.isPlaceholder }
        }
        return preferredRow?.items?.firstOrNull { !it.isPlaceholder }
            ?: categories.asSequence()
                .flatMap { it.items.asSequence() }
                .firstOrNull { !it.isPlaceholder }
            ?: categories.firstOrNull()?.items?.firstOrNull()
    }

    /**
     * Refresh the Favorite TV category's EPG data (Now/Next display).
     * @param networkFetch If true, also fetch fresh EPG from the Xtream short EPG API.
     *                     If false, only re-derive from cached program data (free, no network).
     */
    private fun refreshFavoriteTvEpg(networkFetch: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val categories = _uiState.value.categories
                val favTvIndex = categories.indexOfFirst { it.id == FAVORITE_TV_CATEGORY_ID }

                if (favTvIndex < 0) {
                    // Row missing — delegate to the reactive builder which handles
                    // snapshot availability correctly.
                    val favIds = runCatching {
                        iptvRepository.observeFavoriteChannels().first().toHashSet()
                    }.getOrNull() ?: emptySet()
                    if (favIds.isNotEmpty()) attemptBuildOnNowRow(favIds)
                    return@launch
                }

                val currentFavTv = categories[favTvIndex]
                // Collect channel IDs from current items
                val channelIds = currentFavTv.items.mapNotNull { getIptvChannelId(it) }.toSet()
                if (channelIds.isEmpty()) return@launch

                // Optionally do network refresh first
                if (networkFetch) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEpgNetworkRefreshMs >= EPG_NETWORK_REFRESH_MS) {
                        lastEpgNetworkRefreshMs = now
                        runCatching { iptvRepository.refreshEpgForChannels(channelIds) }
                    }
                }

                // Re-derive now/next from (possibly updated) cached data
                iptvRepository.reDeriveCachedNowNext(channelIds)

                // Rebuild the category with updated EPG text
                val freshCategory = withContext(Dispatchers.IO) {
                    runCatching { buildFavoriteTvCategory() }.getOrNull()
                } ?: return@launch

                // Check if anything actually changed to avoid needless recomposition
                val oldOverviews = currentFavTv.items.map { it.overview }
                val newOverviews = freshCategory.items.map { it.overview }
                if (oldOverviews == newOverviews) return@launch

                // Apply user-renamed title if applicable
                val cfg = savedCatalogById[FAVORITE_TV_CATEGORY_ID]
                val titled = if (cfg != null && cfg.title.isNotBlank() && cfg.title != freshCategory.title) {
                    freshCategory.copy(title = cfg.title)
                } else {
                    freshCategory
                }

                withContext(Dispatchers.Main.immediate) {
                    val current = _uiState.value.categories.toMutableList()
                    val idx = current.indexOfFirst { it.id == FAVORITE_TV_CATEGORY_ID }
                    if (idx >= 0) {
                        current[idx] = titled
                        _uiState.value = _uiState.value.copy(
                            categories = current,
                            liveChannelEpg = iptvNowNextMap.toMap(),
                        )
                    }
                }
            } catch (e: Exception) {
                // ignore — EPG refresh is best-effort
            }
        }
    }

    /** Start periodic EPG refresh for the Favorite TV home row. */
    private fun startEpgRefreshTimer() {
        epgRefreshJob?.cancel()
        epgRefreshJob = viewModelScope.launch {
            // Short initial delay — fire quickly after home loads.
            // refreshFavoriteTvEpg() handles the "row missing" case itself.
            delay(if (isLowRamDevice) 4_000L else 2_000L)
            var tickCount = 0L
            while (true) {
                tickCount++
                // Every tick (60s): local re-derive
                // Every 5th tick (5 min): also do network refresh
                val doNetwork = tickCount % ((EPG_NETWORK_REFRESH_MS / EPG_LOCAL_REFRESH_MS).coerceAtLeast(1)) == 0L
                refreshFavoriteTvEpg(networkFetch = doNetwork)
                delay(EPG_LOCAL_REFRESH_MS)
            }
        }
    }

    private val isTvDevice = detectDeviceType(context) == DeviceType.TV
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val isLowRamDevice =
        activityManager.isLowRamDevice ||
            activityManager.memoryClass <= 256 ||
            (isTvDevice && activityManager.memoryClass <= 384)
    private val gson = com.google.gson.Gson()

    // Disk cache key for the home categories — profile-scoped so each profile gets
    // its own cached home screen. On app launch, the cached categories are shown
    // immediately (within 1 frame) while loadHomeData() fetches fresh data from
    // TMDB in the background. This eliminates the visible skeleton/loading phase
    // that made the app feel slow on startup.
    // Use a plain file in the cache dir instead of DataStore — DataStore has
    // a size limit and the categories JSON can be 200KB+. A cache file is faster
    // to read/write and doesn't trigger DataStore observers.
    private fun categoriesCacheFile(): java.io.File {
        val profileId = profileManager.getProfileIdSync()
            .ifBlank { "default" }
            .replace(ALPHANUMERIC_REGEX, "_")
        val language = (mediaRepository.contentLanguage ?: "en-US")
            .replace(ALPHANUMERIC_REGEX, "_")
        return java.io.File(context.cacheDir, "home_categories_cache_${profileId}_$language.json")
    }

    private suspend fun applyContentLanguageFromPrefs(): String {
        val prefs = context.settingsDataStore.data.first()
        val profileId = profileManager.getProfileId()
        val fallbackLanguage = prefs[LAST_APP_LANGUAGE_KEY] ?: "en-US"
        val language = prefs[profileManager.profileStringKeyFor(profileId, "content_language")]
            ?: fallbackLanguage
        mediaRepository.contentLanguage = if (language == "en-US") null else language
        return language
    }

    private fun persistCategoriesCache(categories: List<Category>) {
        val cacheable = categories.filter { cat ->
            cat.items.isNotEmpty() && cat.items.none { it.isPlaceholder }
        }
        if (cacheable.isEmpty()) return
        runCatching {
            categoriesCacheFile().writeText(gson.toJson(cacheable))
        }
    }

    private fun loadCategoriesCache(): List<Category> {
        return runCatching {
            val file = categoriesCacheFile()
            if (!file.exists()) return emptyList()
            if (file.length() > maxCategoriesCacheBytes) {
                file.delete()
                return emptyList()
            }
            val json = file.readText()
            if (json.isBlank()) return emptyList()
            val type = com.google.gson.reflect.TypeToken
                .getParameterized(MutableList::class.java, Category::class.java)
                .type
            val parsed: List<Category> = gson.fromJson(json, type) ?: emptyList()
            parsed.filter { it.items.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
    // IO concurrency for network requests (logo fetches, catalog loads, etc.)
    private val networkParallelism = if (isLowRamDevice) 1 else 2
    private val networkDispatcher = Dispatchers.IO.limitedParallelism(networkParallelism)
    private var lastContinueWatchingItems: List<MediaItem> = emptyList()
    private var lastContinueWatchingUpdateMs: Long = 0L
    private var lastResolvedBaseCategories: List<Category> = emptyList()
    private val dismissedContinueWatchingKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val CONTINUE_WATCHING_REFRESH_MS = 45_000L
    private val WATCHED_BADGES_REFRESH_MS = 90_000L
    private var lastWatchedBadgesRefreshMs: Long = 0L
    private val HOME_PLACEHOLDER_ITEM_COUNT = 8

    // EPG refresh intervals for Favorite TV row
    /** Local re-derive: shift now/next from cached programs when a program ends. */
    private val EPG_LOCAL_REFRESH_MS = 60_000L
    /** Network refresh: fetch fresh short EPG for favorite channels (Xtream only). */
    private val EPG_NETWORK_REFRESH_MS = 5 * 60_000L
    private var epgRefreshJob: Job? = null
    private var lastEpgNetworkRefreshMs: Long = 0L

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    val cardLogoUrls = mutableStateMapOf<String, String>()

    // Home's one configurable row (Design v4 §3): "watchlist" | "up_next" |
    // "continue_watching", per profile. HomeScreen filters uiState.categories
    // down to just this one alongside the fixed rows — the underlying
    // watchlist/CW fetches all still run unconditionally so switching the
    // selection is instant with no reload.
    private val _homeRowSelection = MutableStateFlow(DEFAULT_HOME_ROW_SELECTION)
    val homeRowSelection: StateFlow<String> = _homeRowSelection.asStateFlow()

    fun setHomeRowSelection(value: String) {
        viewModelScope.launch {
            val profileId = profileManager.getProfileId()
            context.settingsDataStore.edit {
                it[profileManager.profileStringKeyFor(profileId, "home_row_selection")] = value
            }
            _homeRowSelection.value = value
        }
    }

    private val _browseItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val browseItems: StateFlow<List<MediaItem>> = _browseItems.asStateFlow()
    private val _isBrowseLoading = MutableStateFlow(false)
    val isBrowseLoading: StateFlow<Boolean> = _isBrowseLoading.asStateFlow()
    // Extra per-item metadata for the "All Shows"/"All Movies" library browser,
    // keyed the same way as cardLogoUrls ("${mediaType}_${tmdbId}"). Only
    // populated when browsing is sourced from Sonarr/Radarr instead of the
    // home server's file listing — empty for the ordinary Jellyfin/Plex/Emby path.
    private val _browseLibraryMeta = MutableStateFlow<Map<String, LibraryBrowseEntry>>(emptyMap())
    val browseLibraryMeta: StateFlow<Map<String, LibraryBrowseEntry>> = _browseLibraryMeta.asStateFlow()
    // True while the current browse-all category is being sourced from Sonarr/
    // Radarr. The overlay uses this to skip its "instant preview list" fallback
    // (category.items, which is the old Jellyfin-file-based row) for this case —
    // that preview's item 0 is a different title than the Sonarr/Radarr list's
    // item 0, and swapping between them mid-load is what breaks the overlay's
    // focus/nav (the swap can dispose the composable a focus requester points
    // at). Skipping the mismatched preview removes the swap entirely instead of
    // trying to out-race it.
    private val _isLibrarySourcedBrowse = MutableStateFlow(false)
    val isLibrarySourcedBrowse: StateFlow<Boolean> = _isLibrarySourcedBrowse.asStateFlow()

    // Debounce job for hero updates (Phase 6.1)
    private var heroUpdateJob: Job? = null
    private var heroDetailsJob: Job? = null
    private var prefetchJob: Job? = null
    private var preloadCategoryPriorityJob: Job? = null
    private val preloadCategoryJobs = ConcurrentHashMap<Int, Job>()
    private var startupCatalogWarmupJob: Job? = null
    private var customCatalogsJob: Job? = null
    private var loadHomeJob: Job? = null
    private var refreshContinueWatchingJob: Job? = null
    private var watchedBadgesJob: Job? = null
    private var loadHomeRequestId: Long = 0L
    private var activeRuntimeProfileId: String? = null
    private val HERO_DEBOUNCE_MS = 80L // Short debounce; focus idle is handled in HomeScreen
    private val startupCreatedAtMs = SystemClock.elapsedRealtime()
    private val startupSettleMs = if (isLowRamDevice) 5_000L else 4_000L

    // Phase 6.2-6.3: Fast scroll detection
    private var lastFocusChangeTime = 0L
    private var consecutiveFastChanges = 0
    private val FAST_SCROLL_THRESHOLD_MS = 650L  // Under 650ms = fast scrolling
    private val FAST_SCROLL_DEBOUNCE_MS = 220L   // Keep hero responsive without updating every key repeat

    private val FOCUS_PREFETCH_COALESCE_MS = if (isLowRamDevice) 180L else 120L
    private val BACKDROP_IDLE_PREFETCH_MS = if (isLowRamDevice) 220L else 160L

    private val homeLandscapeCardWidthDp = 210
    private val homeLogoWidthDp = 220
    private val homeLogoHeightDp = 64
    private val logoPreloadWidth = (homeLogoWidthDp * context.resources.displayMetrics.density)
        .toInt()
        .coerceAtLeast(1)
    private val logoPreloadHeight = (homeLogoHeightDp * context.resources.displayMetrics.density)
        .toInt()
        .coerceAtLeast(1)
    private val cardBackdropWidth = (homeLandscapeCardWidthDp * context.resources.displayMetrics.density)
        .toInt()
        .coerceAtLeast(1)
    private val cardBackdropHeight = (cardBackdropWidth / (16f / 9f))
        .toInt()
        .coerceAtLeast(1)
    private val backdropPreloadWidth = cardBackdropWidth
    private val backdropPreloadHeight = cardBackdropHeight
    private val initialLogoPrefetchRows = 1
    private val initialLogoPrefetchItemsPerRow = if (isLowRamDevice) 1 else 2
    // Prefetch enough backdrops to fill the first visible row on the home screen
    // (typically 6-8 cards on a TV). Was 2, which left the majority of the first
    // row unpreloaded on cold start, causing visible black -> image pop-in.
    private val initialBackdropPrefetchItems = if (isLowRamDevice) 4 else 8
    private val incrementalLogoPrefetchItems = if (isLowRamDevice) 4 else 6
    private val prioritizedLogoPrefetchItems = if (isLowRamDevice) 5 else 7
    private val incrementalBackdropPrefetchItems = if (isLowRamDevice) 4 else 7
    private val startupWarmupCategoryCount = if (isLowRamDevice) 5 else 8
    private val startupWarmupItemsPerRow = if (isLowRamDevice) 5 else 8
    private val initialCategoryItemCap = if (isLowRamDevice) 8 else 10
    private val categoryPageSize = if (isLowRamDevice) 8 else 10
    private val initialMdblistCatalogCount = 1
    private val nearEndThreshold = 4

    // Track current focus for ahead-of-focus preloading
    private var currentRowIndex = 0
    private var currentItemIndex = 0

    // Track if preloaded data was used to avoid duplicate loading
    private var usedPreloadedData = false

    // Cached CW state — initialized eagerly before setPreloadedData() can race against the observer
    private var cachedCwPlacement: com.arflix.tv.data.model.CatalogPlacement =
        com.arflix.tv.data.model.CatalogPlacement.HOME
    private var cachedCwHidden: Boolean = false
    private var cachedCwSortOrder: Int = 0

    private val maxLogoCacheEntries = if (isLowRamDevice) 220 else 420
    private val maxLogoCacheJsonChars = if (isLowRamDevice) 250_000 else 500_000
    private val maxCategoriesCacheBytes = if (isLowRamDevice) 500_000L else 1_000_000L
    private val logoCacheLock = Any()
    private val logoCache = LinkedHashMap<String, String>(maxLogoCacheEntries + 32, 0.75f, true)
    private var logoCacheRevision: Long = 0L
    private var lastPublishedLogoCacheRevision: Long = -1L
    private val logoCachePrefs = context.getSharedPreferences("logo_cache", Context.MODE_PRIVATE)
    private var logoCacheDiskWriteJob: Job? = null
    private val logoFetchInFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private val heroDetailsCache = ConcurrentHashMap<String, HeroDetailsSnapshot>()
    private val savedCatalogById = ConcurrentHashMap<String, CatalogConfig>()
    private val categoryPaginationStates = ConcurrentHashMap<String, CategoryPaginationState>()
    private val preloadedRequests: MutableSet<String> = run {
        val backingMap = object : java.util.LinkedHashMap<String, Boolean>(1200, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
                return size > 1200
            }
        }
        Collections.synchronizedSet(Collections.newSetFromMap(backingMap))
    }
    private var logoCachePublishJob: Job? = null
    @Volatile
    private var pendingLogoPublishPriority: Boolean = false
    private var lastLogoCachePublishMs: Long = 0L
    private val LOGO_CACHE_PUBLISH_THROTTLE_MS = if (isLowRamDevice) 650L else 420L
    private val LOGO_CACHE_IDLE_REQUIRED_MS = if (isLowRamDevice) 520L else 360L
    private val LOGO_CACHE_FAST_SCROLL_IDLE_MS = if (isLowRamDevice) 320L else 220L

    private fun getCachedLogo(key: String): String? = synchronized(logoCacheLock) {
        logoCache[key]
    }

    private fun isStartupSettling(): Boolean {
        return SystemClock.elapsedRealtime() - startupCreatedAtMs < startupSettleMs
    }

    private suspend fun delayUntilStartupSettled(extraDelayMs: Long = 0L) {
        val waitMs = (startupCreatedAtMs + startupSettleMs + extraDelayMs) -
            SystemClock.elapsedRealtime()
        if (waitMs > 0L) {
            delay(waitMs)
        }
    }

    private fun scheduleInitialHomeLoad() {
        viewModelScope.launch {
            applyContentLanguageFromPrefs()
            delay(if (isLowRamDevice) 1_000L else 800L)
            if (
                usedPreloadedData ||
                _uiState.value.categories.isNotEmpty() ||
                _uiState.value.heroItem != null
            ) {
                delayUntilStartupSettled(extraDelayMs = 700L)
            }
            loadHomeData()
        }
    }

    private fun scheduleStartupImageWarmup(
        logoUrls: List<String> = emptyList(),
        backdropUrls: List<String> = emptyList()
    ) {
        if (logoUrls.isEmpty() && backdropUrls.isEmpty()) return
        viewModelScope.launch(networkDispatcher) {
            delayUntilStartupSettled(1_200L)
            if (logoUrls.isNotEmpty()) {
                preloadLogoImages(
                    logoUrls,
                    batchLimit = if (isLowRamDevice) 2 else 3
                )
            }
            if (backdropUrls.isNotEmpty()) {
                preloadBackdropImages(backdropUrls)
            }
        }
    }

    private fun scheduleStartupCatalogImageWarmup(categories: List<Category>) {
        if (categories.isEmpty()) return
        startupCatalogWarmupJob?.cancel()
        val warmupRows = categories
            .asSequence()
            .filterNot { it.id.startsWith("collection_row_") }
            .take(startupWarmupCategoryCount)
            .toList()
        if (warmupRows.isEmpty()) return

        startupCatalogWarmupJob = viewModelScope.launch(networkDispatcher) {
            delayUntilStartupSettled(if (isLowRamDevice) 1_600L else 1_000L)
            warmupRows.forEach { category ->
                val backdropUrls = category.items
                    .take(startupWarmupItemsPerRow)
                    .mapNotNull { it.backdrop ?: it.image }
                if (backdropUrls.isNotEmpty()) {
                    preloadBackdropImages(backdropUrls)
                }
                delay(if (isLowRamDevice) 260L else 160L)
            }
        }
    }

    private fun scheduleStartupHeroHydration(item: MediaItem) {
        heroDetailsJob?.cancel()
        heroDetailsJob = viewModelScope.launch(networkDispatcher) {
            delayUntilStartupSettled(900L)
            val currentHero = _uiState.value.heroItem
            if (currentHero?.id == item.id && currentHero.mediaType == item.mediaType) {
                hydrateHeroDetailsIfNeeded(item)
            }
        }
    }

    private fun resetProfileRuntimeState(profileId: String) {
        loadHomeJob?.cancel()
        refreshContinueWatchingJob?.cancel()
        cwFetchJob?.cancel()
        cwFetchJob = null
        serverResumeFetchJob?.cancel()
        serverResumeFetchJob = null
        watchedBadgesJob?.cancel()
        preloadCategoryPriorityJob?.cancel()
        preloadCategoryJobs.values.forEach { it.cancel() }
        preloadCategoryJobs.clear()
        startupCatalogWarmupJob?.cancel()
        customCatalogsJob?.cancel()
        epgRefreshJob?.cancel()
        lastContinueWatchingItems = emptyList()
        lastContinueWatchingUpdateMs = 0L
        lastResolvedBaseCategories = emptyList()
        dismissedContinueWatchingKeys.clear()
        categoryPaginationStates.clear()
        savedCatalogById.clear()
        collectionCatalogByMediaId.clear()
        iptvChannelMap.clear()
        traktRepository.clearAllProfileCaches()
        watchlistRepository.clearWatchlistCache()
        watchHistoryRepository.clearProfileCaches()
        iptvRepository.invalidateCache()
        _uiState.value = HomeUiState(syncStatus = _uiState.value.syncStatus)
        activeRuntimeProfileId = profileId
    }

    private fun hasCachedLogo(key: String): Boolean = synchronized(logoCacheLock) {
        logoCache.containsKey(key)
    }

    private fun putCachedLogo(key: String, value: String): Boolean {
        synchronized(logoCacheLock) {
            val existing = logoCache[key]
            if (existing == value) return false
            logoCache[key] = value
            while (logoCache.size > maxLogoCacheEntries) {
                val oldestKey = logoCache.entries.iterator().next().key
                logoCache.remove(oldestKey)
            }
            logoCacheRevision += 1L
            return true
        }
    }

    private fun putCachedLogos(entries: Map<String, String>): Boolean {
        if (entries.isEmpty()) return false
        var changed = false
        synchronized(logoCacheLock) {
            entries.forEach { (key, value) ->
                if (logoCache[key] != value) {
                    logoCache[key] = value
                    changed = true
                }
            }
            if (changed) {
                while (logoCache.size > maxLogoCacheEntries) {
                    val oldestKey = logoCache.entries.iterator().next().key
                    logoCache.remove(oldestKey)
                }
                logoCacheRevision += 1L
            }
        }
        if (changed) saveLogoCacheToDisk()
        return changed
    }

    private fun snapshotLogoCache(): Map<String, String> = synchronized(logoCacheLock) {
        LinkedHashMap(logoCache)
    }

    private fun replaceCardLogoState(snapshot: Map<String, String>) {
        val keysToRemove = cardLogoUrls.keys.toList().filterNot { snapshot.containsKey(it) }
        keysToRemove.forEach { cardLogoUrls.remove(it) }
        snapshot.forEach { (key, value) ->
            if (cardLogoUrls[key] != value) {
                cardLogoUrls[key] = value
            }
        }
    }

    private fun publishLogoCacheSnapshotIfChanged() {
        val snapshot: Map<String, String>
        synchronized(logoCacheLock) {
            if (logoCacheRevision == lastPublishedLogoCacheRevision) return
            snapshot = LinkedHashMap(logoCache)
            lastPublishedLogoCacheRevision = logoCacheRevision
        }
        lastLogoCachePublishMs = SystemClock.elapsedRealtime()
        replaceCardLogoState(snapshot)
    }

    private fun scheduleLogoCachePublish(highPriority: Boolean = false) {
        if (highPriority) {
            pendingLogoPublishPriority = true
        }
        val idleElapsedAtSchedule = System.currentTimeMillis() - lastFocusChangeTime
        if (highPriority && idleElapsedAtSchedule < LOGO_CACHE_FAST_SCROLL_IDLE_MS) {
            // During rapid D-pad movement, avoid forcing immediate full-map publishes.
            pendingLogoPublishPriority = false
        }
        if (logoCachePublishJob?.isActive == true) {
            if (highPriority) {
                logoCachePublishJob?.cancel()
            } else {
                return
            }
        }
        logoCachePublishJob = viewModelScope.launch {
            val priorityNow = pendingLogoPublishPriority
            pendingLogoPublishPriority = false
            if (priorityNow) {
                val elapsedSincePublish = SystemClock.elapsedRealtime() - lastLogoCachePublishMs
                val priorityThrottleMs = if (isLowRamDevice) 120L else 80L
                val throttleWaitMs = (priorityThrottleMs - elapsedSincePublish).coerceAtLeast(0L)
                val idleElapsedMs = System.currentTimeMillis() - lastFocusChangeTime
                val idleWaitMs = (LOGO_CACHE_FAST_SCROLL_IDLE_MS - idleElapsedMs).coerceAtLeast(0L)
                val waitMs = maxOf(throttleWaitMs, idleWaitMs)
                if (waitMs > 0L) delay(waitMs)
            } else {
                while (true) {
                    val elapsedSincePublish = SystemClock.elapsedRealtime() - lastLogoCachePublishMs
                    val throttleWaitMs = (LOGO_CACHE_PUBLISH_THROTTLE_MS - elapsedSincePublish).coerceAtLeast(0L)
                    val idleElapsedMs = System.currentTimeMillis() - lastFocusChangeTime
                    val idleWaitMs = (LOGO_CACHE_IDLE_REQUIRED_MS - idleElapsedMs).coerceAtLeast(0L)
                    val waitMs = maxOf(throttleWaitMs, idleWaitMs)
                    if (waitMs <= 0L) break
                    delay(waitMs)
                }
            }
            publishLogoCacheSnapshotIfChanged()
        }
    }

    /** Restore logo URL cache from disk (SharedPreferences). Called once at init. */
    private fun restoreLogoCacheFromDisk() {
        try {
            val json = logoCachePrefs.getString("urls", null) ?: return
            if (json.length > maxLogoCacheJsonChars) {
                logoCachePrefs.edit().remove("urls").apply()
                return
            }
            val map = org.json.JSONObject(json)
            val keys = map.keys()
            synchronized(logoCacheLock) {
                while (keys.hasNext()) {
                    val key = keys.next()
                    logoCache[key] = map.getString(key)
                    while (logoCache.size > maxLogoCacheEntries) {
                        val eldestKey = logoCache.entries.iterator().next().key
                        logoCache.remove(eldestKey)
                    }
                }
                if (logoCache.isNotEmpty()) {
                    logoCacheRevision += 1L
                }
            }
            System.err.println("HomeVM: restored ${logoCache.size} logo URLs from disk cache")
        } catch (e: Throwable) {
            System.err.println("HomeVM: failed to restore logo cache: ${e.message}")
            runCatching { logoCachePrefs.edit().remove("urls").apply() }
        }
    }

    /** Persist logo URL cache to disk (debounced). */
    private fun saveLogoCacheToDisk() {
        logoCacheDiskWriteJob?.cancel()
        logoCacheDiskWriteJob = viewModelScope.launch(Dispatchers.IO) {
            delay(2_000L) // debounce: wait 2s after last change before writing
            try {
                val snapshot = synchronized(logoCacheLock) { LinkedHashMap(logoCache) }
                val json = org.json.JSONObject(snapshot as Map<*, *>).toString()
                logoCachePrefs.edit().putString("urls", json).apply()
            } catch (e: Exception) {
                System.err.println("HomeVM: failed to save logo cache: ${e.message}")
            }
        }
    }

    init {
        viewModelScope.launch {
            profileManager.activeProfileId
                .distinctUntilChanged()
                .collect { profileId ->
                    val previousProfileId = activeRuntimeProfileId
                    if (previousProfileId == null) {
                        activeRuntimeProfileId = profileId
                        return@collect
                    }
                    if (previousProfileId != profileId) {
                        resetProfileRuntimeState(profileId)
                        loadHomeData()
                        refreshContinueWatchingOnly(force = true)
                        launchServerResumeFetch()
                        startEpgRefreshTimer()
                    }
                }
        }

        // Home row selection (Design v4 §3) — re-reads whenever the active
        // profile changes, same convergence pattern as NavSectionRepository's
        // per-profile observers.
        viewModelScope.launch {
            profileManager.activeProfileId
                .distinctUntilChanged()
                .flatMapLatest { profileId ->
                    context.settingsDataStore.data.map { prefs ->
                        prefs[profileManager.profileStringKeyFor(profileId, "home_row_selection")]
                            ?: DEFAULT_HOME_ROW_SELECTION
                    }
                }
                .distinctUntilChanged()
                .collect { _homeRowSelection.value = it }
        }

        // "Up Next" only gets built/fetched when it's actually the selected row —
        // Watchlist/Continue Watching already run unconditionally for other reasons
        // (badges, sync, etc.) so they don't need this gating.
        viewModelScope.launch {
            homeRowSelection.collect { selection ->
                val current = _uiState.value.categories.toMutableList()
                val idx = current.indexOfFirst { it.id == UP_NEXT_CATEGORY_ID }
                if (selection == "up_next") {
                    val category = runCatching { buildUpNextCategory() }.getOrNull()
                    if (category != null) {
                        if (idx >= 0) current[idx] = category else current.add(category)
                        _uiState.value = _uiState.value.copy(categories = current)
                    }
                } else if (idx >= 0) {
                    current.removeAt(idx)
                    _uiState.value = _uiState.value.copy(categories = current)
                }
            }
        }

        // Reactive On Now row builder — runs independently of loadHomeData().
        launchOnNowRowObserver()

        // home_layout: cache hero/footer config for chooseInitialHero(), and keep
        // the Apps row pinned last when footer.type == "apps_catalog". Idempotent —
        // only mutates categories when the Apps row isn't already last, so this
        // can't loop against its own _uiState.categories observation.
        viewModelScope.launch {
            homeLayoutRepository.observeLayoutForActiveProfile()
                .combine(_uiState.map { it.categories }.distinctUntilChanged()) { layout, categories -> layout to categories }
                .collect { (layout, categories) ->
                    cachedHomeLayout = layout
                    if (layout.footer.type != "apps_catalog") return@collect
                    val appsIdx = categories.indexOfFirst { it.id == APPS_CATEGORY_ID }
                    if (appsIdx < 0 || appsIdx == categories.lastIndex) return@collect
                    val reordered = categories.toMutableList()
                    reordered.add(reordered.removeAt(appsIdx))
                    _uiState.value = _uiState.value.copy(categories = reordered)
                }
        }

        // "Your library" tile row — Home's presentation of navSectionsByProfile.
        // Positioned between On Now and Continue Watching on first insert only;
        // once present, updates rebuild its content in place without re-deriving
        // position, same convergence pattern as the footer-pinning observer above.
        viewModelScope.launch {
            navSectionRepository.observeSectionsForActiveProfile()
                .combine(_uiState.map { it.categories }.distinctUntilChanged()) { sections, categories -> sections to categories }
                .collect { (sections, categories) ->
                    val tileCategory = buildLibraryTilesCategory(sections, categories)
                    val existingIdx = categories.indexOfFirst { it.id == LIBRARY_TILES_CATEGORY_ID }
                    val updated = categories.toMutableList()
                    when {
                        tileCategory == null && existingIdx >= 0 -> updated.removeAt(existingIdx)
                        tileCategory == null -> return@collect
                        existingIdx >= 0 && updated[existingIdx] == tileCategory -> return@collect
                        existingIdx >= 0 -> updated[existingIdx] = tileCategory
                        else -> {
                            val cwIdx = updated.indexOfFirst { it.id == "continue_watching" }
                            val favIdx = updated.indexOfFirst { it.id == FAVORITE_TV_CATEGORY_ID }
                            val insertIdx = when {
                                cwIdx >= 0 -> cwIdx
                                favIdx >= 0 -> favIdx + 1
                                else -> updated.size.coerceAtMost(1)
                            }
                            updated.add(insertIdx, tileCategory)
                        }
                    }
                    _uiState.value = _uiState.value.copy(categories = updated)
                }
        }

        // Load Neolink cameras and recent events independently of main catalog load.
        launchCamerasRow()
        launchNeolinkEventsRow()

        // Reactively inject the watchlist row whenever watchlist items change.
        viewModelScope.launch {
            watchlistRepository.watchlistItems.collect { items ->
                val current = _uiState.value.categories
                if (current.isEmpty()) return@collect
                val prefs = context.settingsDataStore.data.first()
                val watchlistPlacement = prefs[com.arflix.tv.data.repository.WATCHLIST_PLACEMENT_KEY]
                    ?.let { runCatching { CatalogPlacement.valueOf(it) }.getOrNull() }
                    ?: CatalogPlacement.HOME
                val sortOrder = prefs[com.arflix.tv.data.repository.WATCHLIST_SORT_ORDER_KEY]?.toIntOrNull() ?: 0
                val isWatchlistHidden = prefs[com.arflix.tv.data.repository.WATCHLIST_HIDDEN_KEY] ?: false
                val updated = current.toMutableList()
                updated.removeAll { it.id == WATCHLIST_CATEGORY_ID }
                if (items.isNotEmpty() && !isWatchlistHidden && watchlistPlacement == CatalogPlacement.HOME) {
                    val cat = Category(id = WATCHLIST_CATEGORY_ID, title = "My Watchlist", items = items)
                    val cwIdx = updated.indexOfFirst { it.id == "continue_watching" }
                    val baseIdx = if (cwIdx >= 0) cwIdx + 1 else 0
                    val insertIdx = (baseIdx + sortOrder).coerceIn(0, updated.size)
                    updated.add(insertIdx, cat)
                }
                if (updated != current) {
                    _uiState.value = _uiState.value.copy(categories = updated)
                }
            }
        }
        // Re-inject/remove watchlist row when placement or sort order changes.
        viewModelScope.launch {
            context.settingsDataStore.data.drop(1).collect { prefs ->
                val newPlacement = prefs[com.arflix.tv.data.repository.WATCHLIST_PLACEMENT_KEY]
                    ?.let { runCatching { CatalogPlacement.valueOf(it) }.getOrNull() }
                    ?: CatalogPlacement.HOME
                val sortOrder = prefs[com.arflix.tv.data.repository.WATCHLIST_SORT_ORDER_KEY]?.toIntOrNull() ?: 0
                val isWatchlistHidden = prefs[com.arflix.tv.data.repository.WATCHLIST_HIDDEN_KEY] ?: false
                val items = watchlistRepository.watchlistItems.value
                val current = _uiState.value.categories
                if (current.isEmpty()) return@collect
                val updated = current.toMutableList()
                updated.removeAll { it.id == WATCHLIST_CATEGORY_ID }
                if (items.isNotEmpty() && !isWatchlistHidden && newPlacement == CatalogPlacement.HOME) {
                    val cat = Category(id = WATCHLIST_CATEGORY_ID, title = "My Watchlist", items = items)
                    val cwIdx = updated.indexOfFirst { it.id == "continue_watching" }
                    val baseIdx = if (cwIdx >= 0) cwIdx + 1 else 0
                    val insertIdx = (baseIdx + sortOrder).coerceIn(0, updated.size)
                    updated.add(insertIdx, cat)
                }
                _uiState.value = _uiState.value.copy(categories = updated)
            }
        }
        // React to CW placement/hidden/sort changes: remove or restore CW row on Home.
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { Triple(it[com.arflix.tv.data.repository.CW_PLACEMENT_KEY], it[com.arflix.tv.data.repository.CW_HIDDEN_KEY] ?: false, it[com.arflix.tv.data.repository.CW_SORT_ORDER_KEY]?.toIntOrNull() ?: 0) }
                .distinctUntilChanged()
                .collect { (str, cwHidden, cwSort) ->
                    cachedCwPlacement = str
                        ?.let { runCatching { com.arflix.tv.data.model.CatalogPlacement.valueOf(it) }.getOrNull() }
                        ?: com.arflix.tv.data.model.CatalogPlacement.HOME
                    cachedCwHidden = cwHidden
                    cachedCwSortOrder = cwSort
                    val current = _uiState.value.categories.toMutableList()
                    val hasCW = current.any { it.id == "continue_watching" }
                    val cwCat = cwHolder.cwCategory.value
                    if (!cwHidden && cachedCwPlacement == com.arflix.tv.data.model.CatalogPlacement.HOME) {
                        if (!hasCW && cwCat != null) current.add(cwSort.coerceIn(0, current.size), cwCat)
                    } else {
                        if (hasCW) current.removeAll { it.id == "continue_watching" }
                    }
                    if (current != _uiState.value.categories)
                        _uiState.value = _uiState.value.copy(categories = current)
                }
        }

        // Pre-load watchlist items: sync from server first (server is source of truth),
        // then enrich with TMDB data so posters appear. If Trakt is connected and local
        // state is empty after server sync, pull from Trakt so the home row shows without
        // requiring the user to visit the Watchlist/Discover screen first.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { watchlistRepository.syncFromSyncServer() }
            runCatching { watchlistRepository.getWatchlistItems() }
            if (watchlistRepository.watchlistItems.value.isEmpty()) {
                runCatching {
                    val (hasTrakt, syncResult) = traktRepository.getWatchlistSyncResultWithAuthState()
                    if (hasTrakt) {
                        val items = syncResult?.items.orEmpty()
                        if (items.isNotEmpty()) {
                            watchlistRepository.syncFromTraktOrder(items)
                        }
                    }
                }
            }
        }

        // Load top-level UI preferences used on Home
        viewModelScope.launch {
            try {
                val prefs = context.settingsDataStore.data.first()
                // Search for any profile key that matches trailer_auto_play
                val trailerEnabled = prefs.asMap().any { (key, value) ->
                    key.name.endsWith("_trailer_auto_play") && value == true
                }
                // show_budget_on_home defaults to TRUE so existing users see no change
                // until they explicitly disable it. We check any active-profile key; if
                // none exist yet the default of true is preserved. Issue #72.
                val showBudgetExplicit = prefs.asMap().entries
                    .firstOrNull { (key, _) -> key.name.endsWith("_show_budget_on_home") }
                    ?.value as? Boolean
                val showBudget = showBudgetExplicit ?: true
                val clockFormat = prefs.asMap().entries
                    .firstOrNull { (key, _) -> key.name.endsWith("_clock_format") }
                    ?.value as? String ?: "24h"
                val trailerSoundEnabled = prefs.asMap().entries
                    .firstOrNull { (key, _) -> key.name.endsWith("_trailer_sound_enabled") }
                    ?.value as? Boolean ?: false
                val trailerDelaySeconds = (prefs.asMap().entries
                    .firstOrNull { (key, _) -> key.name.endsWith("_trailer_delay_seconds") }
                    ?.value as? String)?.toIntOrNull() ?: 2
                val launcherModeEnabled = prefs[com.arflix.tv.data.repository.LAUNCHER_MODE_KEY] ?: false
                _uiState.value = _uiState.value.copy(
                    trailerAutoPlay = trailerEnabled,
                    trailerSoundEnabled = trailerSoundEnabled,
                    trailerDelaySeconds = trailerDelaySeconds,
                    showBudget = showBudget,
                    clockFormat = clockFormat,
                    launcherModeEnabled = launcherModeEnabled
                )
            } catch (_: Exception) {}
        }

        // Subscribe to realtime watch_history events so Continue Watching refreshes on
        // this device within a few seconds of another device updating the shared
        // Supabase watch_history table. Before this, mid-playback updates from device A
        // were only visible on device B after a manual Home ON_RESUME or the 5-minute
        // periodic sync — so switching devices mid-episode always showed the stale
        // resume position. Fixes #91.
        viewModelScope.launch {
            realtimeSyncManager.watchHistoryEvents.collect {
                // Fast path: directly update the progress bar on existing CW cards using
                // Supabase watch_history data (which Device A writes every ~5s). This gives
                // immediate visual feedback without waiting for the full Trakt re-fetch
                // (which can take 10+ seconds for profiles with hundreds of watched items).
                runCatching {
                    val historyEntries = watchHistoryRepository.getContinueWatching()
                    if (historyEntries.isNotEmpty()) {
                        val latestCategories = _uiState.value.categories.toMutableList()
                        val cwIndex = latestCategories.indexOfFirst { it.id == "continue_watching" }
                        if (cwIndex >= 0) {
                            val existingCategory = latestCategories[cwIndex]
                            val sortedHistory = historyEntries.sortedByDescending {
                                it.updated_at ?: it.paused_at.orEmpty()
                            }
                            val updatedItems = existingCategory.items.map { mediaItem ->
                                val mediaTypeKey = if (mediaItem.mediaType == MediaType.TV) "tv" else "movie"
                                val itemSeason = mediaItem.nextEpisode?.seasonNumber
                                val itemEpisode = mediaItem.nextEpisode?.episodeNumber
                                val exactKey = "$mediaTypeKey:${mediaItem.id}:${itemSeason ?: -1}:${itemEpisode ?: -1}"
                                val match = sortedHistory.firstOrNull { entry ->
                                    "${entry.media_type}:${entry.show_tmdb_id}:${entry.season ?: -1}:${entry.episode ?: -1}" == exactKey
                                }
                                if (match != null) {
                                    val storedProgress = (match.progress * 100f).toInt()
                                    val derivedProgress = if (storedProgress <= 0 && match.duration_seconds > 0 && match.position_seconds > 0) {
                                        ((match.position_seconds.toFloat() / match.duration_seconds.toFloat()) * 100f).toInt()
                                    } else {
                                        storedProgress
                                    }
                                    mediaItem.copy(progress = derivedProgress.coerceIn(0, 100))
                                } else {
                                    mediaItem
                                }
                            }
                            latestCategories[cwIndex] = existingCategory.copy(items = updatedItems)
                            _uiState.value = _uiState.value.copy(categories = latestCategories)
                        }
                    }
                }
                // Full refresh: re-resolve from all sources (Trakt, local, Supabase).
                // This runs after the fast path so the progress bar updates immediately,
                // then the authoritative Trakt data (with correct subtitle/resume label)
                // replaces it when the fetch completes.
                refreshContinueWatchingOnly(force = true)
                runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
            }
        }

        // Reload home data when account_sync changes arrive from another device
        // (catalogs, addons, settings pushed by TV/phone). This ensures the UI
        // reflects the latest state without waiting for the next ON_RESUME.
        viewModelScope.launch {
            realtimeSyncManager.accountSyncEvents.collect {
                loadHomeData()
                runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
            }
        }

        // Collect sync status so the UI can show a connection indicator.
        viewModelScope.launch {
            realtimeSyncManager.syncStatusFlow.collect { status ->
                _uiState.value = _uiState.value.copy(syncStatus = status)
            }
        }
        // Restore logo URL cache from disk off the main thread. This used to run
        // synchronously during HomeViewModel init, which made the profile->home
        // transition much more likely to hitch or ANR on phones with larger caches.
        viewModelScope.launch(Dispatchers.IO) {
            restoreLogoCacheFromDisk()
            val cachedLogos = snapshotLogoCache()
            if (cachedLogos.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    replaceCardLogoState(cachedLogos)
                }
                // Keep startup smooth: defer memory warmup until after initial Home rendering.
                delay(if (isLowRamDevice) 1_800L else 1_200L)
                val cachedUrls = synchronized(logoCacheLock) { logoCache.values.toList() }
                preloadLogoImages(
                    cachedUrls.takeLast(if (isLowRamDevice) 8 else 12),
                    batchLimit = if (isLowRamDevice) 4 else 6
                )
            }
        }

        // Instantly show last session's home categories from disk cache so the home
        // screen appears populated within 1 frame of launch — no skeleton loading
        // phase. loadHomeData() refreshes from TMDB in the background and silently
        // replaces the cached data when it arrives.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                applyContentLanguageFromPrefs()
                val cachedCategories = loadCategoriesCache()
                if (cachedCategories.isNotEmpty() && _uiState.value.categories.isEmpty()) {
                    val heroItem = chooseInitialHero(cachedCategories)
                    val heroKey = heroItem?.let { "${it.mediaType}_${it.id}" }
                    val heroLogo = heroKey?.let { getCachedLogo(it) }
                    withContext(Dispatchers.Main) {
                        if (_uiState.value.categories.isEmpty()) {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                isInitialLoad = false,
                                categories = cachedCategories,
                                heroItem = heroItem,
                                heroLogoUrl = heroLogo,
                                error = null
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Instantly show Continue Watching from disk cache before anything else loads.
        // When Trakt is connected, we still use the cache for instant display but the
        // cache will only contain Trakt-sourced items after the first successful
        // resolveContinueWatchingItems call (which saves Trakt-only data to the cache).
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dismissedKeys = runCatching {
                    traktRepository.getDismissedContinueWatchingShowKeys()
                }.getOrDefault(emptySet())
                val cached = preloadStartupContinueWatchingItems().filterNot { item ->
                    dismissedKeys.contains("${item.mediaType.name}:${item.id}")
                }
                if (cached.isNotEmpty()) {
                    val merged = mergeContinueWatchingResumeData(cached)
                    val cwCategory = Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = merged.map { it.toMediaItem() }
                    )
                    cwCategory.items.forEach { mediaRepository.cacheItem(it) }

                    // Set hero item IMMEDIATELY from raw CW data (before the slow
                    // merge step) so the hero section, clear logo, and overview text
                    // appear on the very first frame after profile selection.
                    val rawFirstItem = cached.firstOrNull()?.toMediaItem()
                    val heroKey = rawFirstItem?.let { "${it.mediaType}_${it.id}" }
                    val heroLogo = heroKey?.let { getCachedLogo(it) }

                    withContext(Dispatchers.Main) {
                        if (rawFirstItem != null && _uiState.value.heroItem == null) {
                            if (heroLogo != null) {
                                if (isStartupSettling()) {
                                    scheduleStartupImageWarmup(logoUrls = listOf(heroLogo))
                                } else {
                                    preloadLogoImages(listOf(heroLogo), batchLimit = 1)
                                }
                            }
                            mediaRepository.cacheItem(rawFirstItem)
                            _uiState.value = _uiState.value.copy(
                                heroItem = rawFirstItem,
                                heroLogoUrl = heroLogo
                            )
                            if (isStartupSettling()) {
                                scheduleStartupHeroHydration(rawFirstItem)
                            } else {
                                hydrateHeroDetailsIfNeeded(rawFirstItem)
                            }
                        }

                        lastContinueWatchingItems = cwCategory.items
                        lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                        cwHolder.update(cwCategory)
                        val cwPrefs = context.settingsDataStore.data.first()
                        val cwPlacementPreload = cwPrefs[com.arflix.tv.data.repository.CW_PLACEMENT_KEY]
                            ?.let { runCatching { com.arflix.tv.data.model.CatalogPlacement.valueOf(it) }.getOrNull() }
                            ?: com.arflix.tv.data.model.CatalogPlacement.HOME
                        val cwHiddenPreload = cwPrefs[com.arflix.tv.data.repository.CW_HIDDEN_KEY] ?: false
                        val cwSortPreload = cwPrefs[com.arflix.tv.data.repository.CW_SORT_ORDER_KEY]?.toIntOrNull() ?: 0
                        val updated = _uiState.value.categories.toMutableList()
                        val idx = updated.indexOfFirst { it.id == "continue_watching" }
                        if (!cwHiddenPreload && cwPlacementPreload == com.arflix.tv.data.model.CatalogPlacement.HOME) {
                            if (idx >= 0) updated[idx] = cwCategory else updated.add(cwSortPreload.coerceIn(0, updated.size), cwCategory)
                        } else {
                            if (idx >= 0) updated.removeAt(idx)
                        }
                        _uiState.value = _uiState.value.copy(
                            categories = updated
                        )
                    }
                }
            } catch (e: Exception) {
                System.err.println("HomeVM: preload CW cache failed: ${e.message}")
            }
        }
        scheduleInitialHomeLoad()
        launchServerResumeFetch()
        // Defer heavy background warmups so first-launch navigation remains smooth.
        viewModelScope.launch {
            delay(if (isLowRamDevice) 10 * 60_000L else 8 * 60_000L)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    iptvRepository.warmXtreamVodCachesIfPossible()
                } catch (e: Exception) {
                }
            }
        }
        viewModelScope.launch {
            try {
                // Start CW fetch as soon as Trakt auth is available. This no longer
                // blocks on base catalog loading because the CW fetch runs in its own
                // coroutine and updates the row independently when ready.
                traktRepository.isAuthenticated.filter { it }.first()
                launchContinueWatchingFetch()
            } catch (e: Exception) {
                System.err.println("HomeVM: auth observer CW refresh failed: ${e.message}")
            }
        }
        viewModelScope.launch {
            traktSyncService.syncEvents.collect { status ->
                if (status == com.arflix.tv.data.repository.SyncStatus.COMPLETED) {
                    refreshContinueWatchingOnly(force = true)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            delay(if (isLowRamDevice) 8 * 60_000L else 6 * 60_000L)
            // Warm IPTV channels + EPG in background after startup settles.
            // First load from disk cache (fast), then do targeted network EPG refresh
            // for favorite channels so home screen shows current program info.
            try {
                iptvRepository.prefetchFreshStartupData()
                val snapshot = iptvRepository.getMemoryCachedSnapshot() ?: return@launch
                // Phase 2: Refresh EPG for favorite channels (lightweight network call)
                val snap = iptvRepository.getMemoryCachedSnapshot()
                if (snap != null) {
                    val favIds = snap.favoriteChannels.toHashSet()
                    val favChannelIds = snap.channels
                        .filter { favIds.contains(it.id) }
                        .map { it.id }
                        .toSet()
                    if (favChannelIds.isNotEmpty()) {
                        refreshFavoriteTvEpg(networkFetch = false)
                    }
                }
            } catch (_: Exception) {
            }
        }
        // Periodically refresh EPG data for Favorite TV row after Home settles.
        viewModelScope.launch {
            delay(if (isLowRamDevice) 8 * 60_000L else 6 * 60_000L)
            startEpgRefreshTimer()
        }
        viewModelScope.launch {
            catalogRepository.observeCatalogs()
                .map { catalogs ->
                    catalogs.joinToString("|") { "${it.id}:${it.title}:${it.sourceUrl.orEmpty()}" }
                }
                .distinctUntilChanged()
                .drop(2) // Skip first two emissions to avoid re-triggering loadHomeData during
                         // initial startup and ensurePreinstalledDefaults DataStore write.
                .collect {
                    // Apply catalog reorder/add/remove immediately on Home.
                    loadHomeData()
                }
        }

        viewModelScope.launch {
            var previousStatus: com.arflix.tv.updater.UpdateStatus = com.arflix.tv.updater.UpdateStatus.Idle
            updateStatusManager.status.collect { status ->
                val hasBadge = status is com.arflix.tv.updater.UpdateStatus.UpdateAvailable || status is com.arflix.tv.updater.UpdateStatus.ReadyToInstall || status is com.arflix.tv.updater.UpdateStatus.Downloading

                var shouldAutoOpen = false
                var isIgnored = false
                if (status is com.arflix.tv.updater.UpdateStatus.UpdateAvailable) {
                    val persistedIgnoredTag = updatePreferences.ignoredTag.first()
                    if (persistedIgnoredTag == status.update.tag || updateStatusManager.sessionIgnoredTag == status.update.tag) {
                        isIgnored = true
                    }
                }

                // Auto-open only when we first discover a new unignored update
                if (status is com.arflix.tv.updater.UpdateStatus.UpdateAvailable && previousStatus !is com.arflix.tv.updater.UpdateStatus.UpdateAvailable && !isIgnored) {
                    shouldAutoOpen = true
                }

                _uiState.value = _uiState.value.copy(
                    updateStatus = status,
                    showAppUpdateDialog = if (shouldAutoOpen) true else _uiState.value.showAppUpdateDialog,
                    hasUpdateBadge = hasBadge && !isIgnored
                )

                previousStatus = status
            }
        }

        // Check for updates shortly after startup
        viewModelScope.launch {
            delay(if (isLowRamDevice) 15_000L else 10_000L)
            checkForAppUpdates(silent = true)
        }

    }

    /**
     * Set preloaded data from StartupViewModel for instant display.
     * This skips the initial network call since data is already loaded.
     *
     * Shows placeholder Continue Watching cards immediately while real data loads.
     */
    suspend fun setPreloadedData(
        categories: List<Category>,
        heroItem: MediaItem?,
        heroLogoUrl: String?,
        logoCache: Map<String, String>
    ) {

        if (usedPreloadedData) {
            if (logoCache.isNotEmpty()) {
                if (putCachedLogos(logoCache)) {
                    publishLogoCacheSnapshotIfChanged()
                }
            }
            val currentState = _uiState.value
            if (heroLogoUrl != null && currentState.heroLogoUrl == null) {
                _uiState.value = currentState.copy(heroLogoUrl = heroLogoUrl)
            }
            return
        }
        if (categories.isEmpty()) {
            return
        }

        usedPreloadedData = true

        // Read CW prefs fresh here to avoid the startup race where cachedCwHidden is
        // still false before the DataStore observer fires its first emission.
        val cwPrefs = context.settingsDataStore.data.first()
        val cwHidden = cwPrefs[com.arflix.tv.data.repository.CW_HIDDEN_KEY] ?: false
        val cwPlacement = cwPrefs[com.arflix.tv.data.repository.CW_PLACEMENT_KEY]
            ?.let { runCatching { com.arflix.tv.data.model.CatalogPlacement.valueOf(it) }.getOrNull() }
            ?: com.arflix.tv.data.model.CatalogPlacement.HOME
        val cwSortOrder = cwPrefs[com.arflix.tv.data.repository.CW_SORT_ORDER_KEY]?.toIntOrNull() ?: 0
        // Warm the cache so synchronous code that runs next is consistent
        cachedCwHidden = cwHidden
        cachedCwPlacement = cwPlacement
        cachedCwSortOrder = cwSortOrder

        putCachedLogos(logoCache)

        // Filter out any existing continue_watching from preloaded data
        val filteredCategories = categories.filter { it.id != "continue_watching" }.toMutableList()

        // Preserve real CW data if we already have it (from disk cache preload in init).
        // Only show placeholders if we have NO real CW items yet.
        val existingCW = _uiState.value.categories.firstOrNull {
            it.id == "continue_watching" && it.items.isNotEmpty() &&
                it.items.none { item -> item.isPlaceholder }
        }
        if (!cwHidden && cwPlacement == com.arflix.tv.data.model.CatalogPlacement.HOME) {
            val cwInsertIdx = cwSortOrder.coerceIn(0, filteredCategories.size)
            if (existingCW != null) {
                filteredCategories.add(cwInsertIdx, existingCW)
            } else {
                val placeholderItems = (1..5).map { index ->
                    MediaItem(
                        id = -index, // Negative IDs for placeholders
                        title = "",
                        mediaType = MediaType.MOVIE,
                        isPlaceholder = true
                    )
                }
                val placeholderContinueWatching = Category(
                    id = "continue_watching",
                    title = "Continue Watching",
                    items = placeholderItems
                )
                filteredCategories.add(cwInsertIdx, placeholderContinueWatching)
            }
        }

        // Adjust hero item if it was from continue watching
        val adjustedHeroItem = if (heroItem != null &&
            categories.firstOrNull()?.id == "continue_watching" &&
            categories.firstOrNull()?.items?.any { it.id == heroItem.id } == true) {
            // Hero was from continue watching, use first item from filtered categories
            filteredCategories.getOrNull(1)?.items?.firstOrNull() ?: heroItem
        } else if (heroItem != null && !isCollectionItem(heroItem)) {
            heroItem
        } else {
            chooseInitialHero(filteredCategories)
        }

        // If CW preload already set a hero with a logo, preserve it — the preloaded
        // hero from startup doesn't carry a logo URL and would cause a visible flash
        // from logo → text → logo.
        val currentHero = _uiState.value.heroItem
        val currentLogo = _uiState.value.heroLogoUrl
        val finalHero = if (currentHero != null && currentLogo != null) {
            currentHero  // keep CW hero that already has a logo
        } else {
            adjustedHeroItem
        }
        val finalLogo = if (finalHero == currentHero && currentLogo != null) {
            currentLogo  // keep the cached logo
        } else if (adjustedHeroItem == heroItem) {
            heroLogoUrl  // use whatever startup preloaded
        } else {
            null
        }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isInitialLoad = false,
            categories = filteredCategories,
            heroItem = finalHero,
            heroLogoUrl = finalLogo,
            error = null
        )
        replaceCardLogoState(snapshotLogoCache())
        refreshWatchedBadges()
    }

    private var cwFetchJob: Job? = null
    private var serverResumeFetchJob: Job? = null
    private val prefetchedDetailsKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val collectionCatalogByMediaId = ConcurrentHashMap<Int, CatalogConfig>()

    fun getCollectionCatalog(item: MediaItem): CatalogConfig? {
        return collectionCatalogByMediaId[item.id]
    }

    private fun toCollectionCategory(row: HomeCollectionRow): Category {
        val items = row.items.mapIndexed { index, config ->
            val fakeId = (config.id.hashCode() and Int.MAX_VALUE).let { if (it == 0) index + 1 else it }
            collectionCatalogByMediaId[fakeId] = config
            // Cards intentionally do NOT seed a clearlogo or a description
            // for collection tiles — the branded cover is the whole card,
            // per the design spec. Clearlogo lives only on the collection
            // detail hero; description lives only on detail, never on
            // the home-row card.
            MediaItem(
                id = fakeId,
                title = config.title,
                overview = "",
                mediaType = MediaType.MOVIE,
                image = config.collectionCoverImageUrl.orEmpty(),
                backdrop = config.collectionFocusGifUrl
                    ?: config.collectionHeroImageUrl
                    ?: config.collectionCoverImageUrl,
                status = "collection:${config.id}",
                collectionGroup = config.collectionGroup,
                collectionTileShape = config.collectionTileShape,
                collectionHideTitle = config.collectionHideTitle
            )
        }
        return Category(
            id = row.id,
            title = row.title,
            items = items
        )
    }

    /**
     * Fetch Continue Watching in its OWN coroutine that is NOT cancelled by
     * loadHomeData restarts. loadHomeData() is called multiple times during
     * startup (profile load, catalog observer, cloud pull), each cancelling
     * the previous job. When getContinueWatching() was inside that job, the
     * 30-60s Trakt fetch (all watched shows × progress API) was always getting
     * cancelled before completing. This decoupled coroutine survives those
     * restarts and updates the CW row independently when the data arrives.
     */
    private fun launchContinueWatchingFetch() {
        // Don't restart if already running
        if (cwFetchJob?.isActive == true) return
        cwFetchJob = viewModelScope.launch(Dispatchers.IO) {
            val cachedResult = runCatching { preloadStartupContinueWatchingItems() }
            cachedResult.onFailure { error ->
                AppLogger.recordException(
                    throwable = error,
                    context = mapOf(
                        "error_area" to "ContinueWatching",
                        "cw_phase" to "preload_startup"
                    )
                )
            }
            val cached = cachedResult.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                publishContinueWatching(cached)
            }

            // FAST PATH — resolve from cached Trakt/local data and publish
            // immediately. Avoids the 30–60s cold-refresh wait that used to
            // leave the CW row empty for minutes (especially when the Trakt
            // progress endpoint throttles with HTTP 429).
            val instantResult = runCatching { resolveContinueWatchingItemsStable(forceFresh = false) }
            instantResult.onFailure { error ->
                AppLogger.recordException(
                    throwable = error,
                    context = mapOf(
                        "error_area" to "ContinueWatching",
                        "cw_phase" to "instant"
                    )
                )
            }
            val instant = instantResult.getOrDefault(emptyList())
            if (instant.isNotEmpty()) {
                publishContinueWatching(instant)
            }

            // SLOW PATH — do a freshness refresh in the background. If it
            // returns something different, republish. Swallows transient
            // Trakt 429s so the visible row doesn't blink back to empty.
            val freshResult = runCatching { resolveContinueWatchingItemsStable(forceFresh = true) }
            freshResult.onFailure { error ->
                AppLogger.recordException(
                    throwable = error,
                    context = mapOf(
                        "error_area" to "ContinueWatching",
                        "cw_phase" to "fresh"
                    )
                )
            }
            val fresh = freshResult.getOrDefault(emptyList())
            if (fresh.isNotEmpty() && fresh != instant) {
                publishContinueWatching(fresh)
            }
            val traktConnected = runCatching { traktRepository.hasTrakt() }.getOrDefault(false)
            if (traktConnected && cached.isEmpty() && instant.isEmpty() && fresh.isEmpty()) {
                AppLogger.breadcrumb(
                    tag = "ContinueWatching",
                    message = "trakt_connected_empty_all_paths",
                    severity = "warning"
                )
            }
        }
    }

    private fun restartContinueWatchingFetch() {
        cwFetchJob?.cancel()
        cwFetchJob = null
        launchContinueWatchingFetch()
    }

    private suspend fun publishContinueWatching(items: List<ContinueWatchingItem>) {
        val baseItems = items.map { it.toMediaItem() }
        val fillerNeeded = (CW_TARGET_ROW_SIZE - baseItems.size).coerceAtLeast(0)
        val fillerItems = if (fillerNeeded > 0) {
            runCatching {
                buildUpcomingFillerItems(excludeTmdbIds = items.map { it.id }.toSet(), limit = fillerNeeded)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val continueWatchingCategory = Category(
            id = "continue_watching",
            title = "Continue Watching",
            items = baseItems + fillerItems
        )
        continueWatchingCategory.items.forEach { mediaRepository.cacheItem(it) }
        lastContinueWatchingItems = continueWatchingCategory.items
        lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
        cwHolder.update(continueWatchingCategory)
        val prefs = context.settingsDataStore.data.first()
        val cwPlacement = prefs[com.arflix.tv.data.repository.CW_PLACEMENT_KEY]
            ?.let { runCatching { com.arflix.tv.data.model.CatalogPlacement.valueOf(it) }.getOrNull() }
            ?: com.arflix.tv.data.model.CatalogPlacement.HOME
        val isCwHidden = prefs[com.arflix.tv.data.repository.CW_HIDDEN_KEY] ?: false
        withContext(Dispatchers.Main) {
            val current = _uiState.value.categories.toMutableList()
            val cwIdx = current.indexOfFirst { it.id == "continue_watching" }
            if (!isCwHidden && cwPlacement == com.arflix.tv.data.model.CatalogPlacement.HOME) {
                if (cwIdx >= 0) current[cwIdx] = continueWatchingCategory
                else current.add(cachedCwSortOrder.coerceIn(0, current.size), continueWatchingCategory)
            } else {
                if (cwIdx >= 0) current.removeAt(cwIdx)
            }
            _uiState.value = _uiState.value.copy(categories = current)
        }
    }

    private fun launchServerResumeFetch() {
        // Server resume rows removed — library rows cover the same content sorted by recent.
        // Remove any in-memory rows directly; do NOT call publishServerResume/syncServerResumeCatalogEntries
        // since that writes to DataStore, triggering observeCatalogs → loadHomeData() and potentially
        // losing the favorite_tv row during the rebuild race.
        serverResumeFetchJob?.cancel()
        serverResumeFetchJob = viewModelScope.launch(Dispatchers.Main) {
            val current = _uiState.value.categories.toMutableList()
            if (current.removeAll { it.id.startsWith("server_resume_") }) {
                _uiState.value = _uiState.value.copy(categories = current)
            }
        }
    }

    /**
     * Reactive On Now row pipeline.
     *
     * Runs completely independently of loadHomeData():
     *  1. Warm up IPTV from disk cache immediately (no network, fast).
     *  2. Observe favorite channel IDs from DataStore. Every time the list changes
     *     (including the first emission at subscription time), attempt to build the row.
     *  3. Retry with backoff for cold starts where the disk cache is empty — this covers
     *     the case where the user hasn't opened the TV guide yet this session.
     *
     * loadHomeData() only *preserves* the row, never builds or removes it.
     */
    private fun launchCamerasRow(delayMs: Long = 1_000L) {
        viewModelScope.launch(Dispatchers.IO) {
            if (delayMs > 0L) delay(delayMs)
            val cfg = savedCatalogById[CAMERAS_CATEGORY_ID]
            if (cfg?.isHidden == true || cfg?.placement == CatalogPlacement.DISCOVER) return@launch
            val category = runCatching { buildCamerasCategory() }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                val current = _uiState.value.categories.toMutableList()
                val existing = current.indexOfFirst { it.id == CAMERAS_CATEGORY_ID }
                if (existing >= 0) {
                    current[existing] = category
                } else {
                    // Insert after On Now row if present, then after Watchlist, then after CW
                    val afterIdx = listOf(
                        current.indexOfFirst { it.id == FAVORITE_TV_CATEGORY_ID },
                        current.indexOfFirst { it.id == WATCHLIST_CATEGORY_ID },
                        current.indexOfFirst { it.id == "continue_watching" },
                    ).filter { it >= 0 }.maxOrNull() ?: -1
                    current.add(if (afterIdx >= 0) afterIdx + 1 else 0, category)
                }
                _uiState.value = _uiState.value.copy(categories = current)
            }
        }
    }

    private suspend fun buildNeolinkEventsCategory(): Category? {
        if (!neolinkRepository.isConfigured()) return null
        val events = neolinkRepository.getRecentEvents(20)
        if (events.isEmpty()) return null
        val nowMs = System.currentTimeMillis()
        val items = events.map { event ->
            val itemId = event.id.hashCode().let { h -> if (h >= 0) -(h + 1) else h }
            neolinkEventClipMap[itemId] = event.clipUrl
            val displayName = event.camera.replace('_', ' ')
                .split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            val diff = nowMs - event.startTimeMs
            val timeAgo = when {
                diff < 60_000L -> "Just now"
                diff < 3_600_000L -> "${diff / 60_000}m ago"
                diff < 86_400_000L -> "${diff / 3_600_000}h ago"
                else -> "${diff / 86_400_000}d ago"
            }
            val label = event.label.replaceFirstChar { it.uppercaseChar() }
            com.arflix.tv.data.model.MediaItem(
                id = itemId,
                title = displayName,
                subtitle = "$label • $timeAgo",
                status = "$NEOLINK_EVENT_STATUS_PREFIX${event.id}",
                image = event.thumbnailUrl,
                mediaType = com.arflix.tv.data.model.MediaType.MOVIE,
            )
        }
        if (items.isEmpty()) return null
        return Category(id = NEOLINK_EVENTS_CATEGORY_ID, title = "Recent Events", items = items)
    }

    private fun launchNeolinkEventsRow(delayMs: Long = 1_500L) {
        viewModelScope.launch(Dispatchers.IO) {
            if (delayMs > 0L) delay(delayMs)
            val cfg = savedCatalogById[NEOLINK_EVENTS_CATEGORY_ID]
            if (cfg?.isHidden == true || cfg?.placement == CatalogPlacement.DISCOVER) return@launch
            val category = runCatching { buildNeolinkEventsCategory() }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                val current = _uiState.value.categories.toMutableList()
                val existing = current.indexOfFirst { it.id == NEOLINK_EVENTS_CATEGORY_ID }
                if (existing >= 0) {
                    current[existing] = category
                } else {
                    val afterIdx = listOf(
                        current.indexOfFirst { it.id == CAMERAS_CATEGORY_ID },
                        current.indexOfFirst { it.id == FAVORITE_TV_CATEGORY_ID },
                        current.indexOfFirst { it.id == WATCHLIST_CATEGORY_ID },
                        current.indexOfFirst { it.id == "continue_watching" },
                    ).filter { it >= 0 }.maxOrNull() ?: -1
                    current.add(if (afterIdx >= 0) afterIdx + 1 else 0, category)
                }
                _uiState.value = _uiState.value.copy(categories = current)
            }
        }
    }

    private fun formatUpcomingAirDate(iso8601: String): String {
        return try {
            val instant = java.time.Instant.parse(iso8601)
            java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)
                .withZone(java.time.ZoneId.systemDefault())
                .format(instant)
        } catch (_: Exception) {
            ""
        }
    }

    private fun com.arflix.tv.data.repository.SonarrCalendarEntry.toUpcomingMediaItem(
        tmdbId: Int?,
        tmdbPoster: String?,
        tmdbBackdrop: String?,
    ): com.arflix.tv.data.model.MediaItem {
        val dateLabel = formatUpcomingAirDate(airDate)
        return com.arflix.tv.data.model.MediaItem(
            id = tmdbId ?: tvdbId ?: seriesId,
            title = title,
            subtitle = episodeTitle.takeIf { it.isNotBlank() && !it.equals("TBA", ignoreCase = true) }
                ?: "Season $season · Episode $episode",
            mediaType = com.arflix.tv.data.model.MediaType.TV,
            // Prefer TMDB art (matches the rest of the app, and Sonarr/TheTVDB's own
            // cached poster has been seen serving flat-out mismatched artwork for a
            // given series) - Sonarr's poster is only a last-resort fallback.
            image = tmdbPoster ?: poster ?: "",
            backdrop = tmdbBackdrop ?: tmdbPoster ?: poster,
            badge = dateLabel.takeIf { it.isNotBlank() }?.let { "Airs $it" },
        )
    }

    /**
     * Home's "Up Next" row option (Design v4 §3) — reuses the same Sonarr-calendar
     * data as Continue Watching's filler instead of a separate fetch, just as a
     * standalone row rather than padding. No excludeTmdbIds here since, unlike the
     * CW filler, this isn't topping up a list of real-progress items.
     */
    private suspend fun buildUpNextCategory(): Category? {
        val items = buildUpcomingFillerItems(excludeTmdbIds = emptySet(), limit = CW_TARGET_ROW_SIZE)
        if (items.isEmpty()) return null
        return Category(id = UP_NEXT_CATEGORY_ID, title = "Up Next", items = items)
    }

    /**
     * Continue Watching overflow filler: shows with a real next-episode-to-air date
     * (Sonarr's calendar) that aren't already represented by a real Continue Watching
     * entry. Not a separate row - `publishContinueWatching()` appends the result of
     * this to the end of the real CW items, only enough to top the row up to
     * [CW_TARGET_ROW_SIZE], sorted soonest-air-date-first. This is how "next episode
     * ready now" (real CW), "airs in a few days" (weekly show, caught up), and
     * "premieres in a couple months" (a show finished long ago, renewed) end up as one
     * prioritized row instead of three separate concepts.
     */
    private suspend fun buildUpcomingFillerItems(
        excludeTmdbIds: Set<Int>,
        limit: Int,
    ): List<com.arflix.tv.data.model.MediaItem> = coroutineScope {
        if (limit <= 0) return@coroutineScope emptyList()
        if (!sonarrRepository.isConfigured()) return@coroutineScope emptyList()
        val entries = runCatching { sonarrRepository.getCalendar() }.getOrDefault(emptyList())
        if (entries.isEmpty()) return@coroutineScope emptyList()
        // One card per series - the soonest upcoming episode, not every episode in range.
        val soonestPerSeries = entries
            .groupBy { it.seriesId }
            .mapNotNull { (_, eps) -> eps.minByOrNull { it.airDate } }
            .sortedBy { it.airDate }

        val resolved = soonestPerSeries.map { entry ->
            async {
                val tmdbId = entry.tvdbId?.let { tvdbId ->
                    runCatching {
                        mediaRepository.resolveTvdbToTmdbRef(tvdbId, com.arflix.tv.data.model.MediaType.TV)
                    }.getOrNull()?.second
                }
                entry to tmdbId
            }
        }.awaitAll()

        resolved
            .filter { (_, tmdbId) -> tmdbId == null || tmdbId !in excludeTmdbIds }
            .sortedBy { (entry, _) -> entry.airDate }
            .take(limit)
            .map { (entry, tmdbId) ->
                val tmdbItem = tmdbId?.let { id ->
                    runCatching { mediaRepository.getTvDetails(id) }.getOrNull()
                }
                entry.toUpcomingMediaItem(
                    tmdbId = tmdbId,
                    tmdbPoster = tmdbItem?.image?.takeIf { it.isNotBlank() },
                    tmdbBackdrop = tmdbItem?.backdrop,
                )
            }
    }

    private fun launchOnNowRowObserver() {
        // Primary: react to favorites changes (covers disk-warm starts and favorite toggles).
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { iptvRepository.warmupFromCacheOnly() }
            iptvRepository.observeFavoriteChannels()
                .distinctUntilChanged()
                .collectLatest { favoriteIds ->
                    if (favoriteIds.isEmpty()) return@collectLatest
                    attemptBuildOnNowRow(favoriteIds.toHashSet())
                }
        }
        // Backoff retry for cold starts: disk cache empty, snapshot arrives later
        // (e.g. after user opens TV guide or after prefetchFreshStartupData runs).
        viewModelScope.launch(Dispatchers.IO) {
            for (delayMs in listOf(3_000L, 8_000L, 20_000L, 60_000L)) {
                delay(delayMs)
                if (_uiState.value.categories.any { it.id == FAVORITE_TV_CATEGORY_ID }) return@launch
                val favIds = runCatching {
                    iptvRepository.observeFavoriteChannels().first().toHashSet()
                }.getOrNull() ?: emptySet()
                if (favIds.isEmpty()) continue
                attemptBuildOnNowRow(favIds)
            }
        }
    }

    /**
     * Attempt to build the On Now row from the current IPTV snapshot.
     * Silently no-ops if the snapshot isn't ready or has no matching favorites.
     * Inserts the row if absent; updates items in-place if already present.
     */
    private suspend fun attemptBuildOnNowRow(favoriteIds: Set<String>) {
        val snapshot = iptvRepository.getMemoryCachedSnapshot()
            ?: runCatching { iptvRepository.getCachedSnapshotOrNull() }.getOrNull()
            ?: return

        val channels = snapshot.channels.filter { favoriteIds.contains(it.id) }
        if (channels.isEmpty()) return

        val channelIdSet = channels.map { it.id }.toSet()
        iptvRepository.reDeriveCachedNowNext(channelIdSet)
        val fresh = iptvRepository.getMemoryCachedSnapshot() ?: snapshot

        val items = channels.mapNotNull { channel ->
            val epg = fresh.nowNext[channel.id]
            iptvChannelToMediaItem(channel, epg)
        }.distinctBy { "${it.mediaType.name}-${it.id}" }

        if (items.isEmpty()) return

        val cfg = savedCatalogById[FAVORITE_TV_CATEGORY_ID]
        val title = if (cfg != null && cfg.title.isNotBlank()) cfg.title else "On Now"
        val category = Category(id = FAVORITE_TV_CATEGORY_ID, title = title, items = items)
        lastFavoriteTvCategory = category

        withContext(Dispatchers.Main) {
            val current = _uiState.value.categories.toMutableList()
            val idx = current.indexOfFirst { it.id == FAVORITE_TV_CATEGORY_ID }
            if (idx >= 0) {
                current[idx] = category
            } else {
                val cwIdx = current.indexOfFirst { it.id == "continue_watching" }
                current.add(if (cwIdx >= 0) cwIdx + 1 else 0, category)
            }
            _uiState.value = _uiState.value.copy(
                categories = current,
                liveChannelEpg = iptvNowNextMap.toMap()
            )
        }
    }

    @Suppress("unused")
    private fun launchServerResumeFetchImpl() {
        serverResumeFetchJob?.cancel()
        serverResumeFetchJob = viewModelScope.launch(Dispatchers.IO) {
            val items = runCatching {
                homeServerRepository.fetchResumeItems()
            }.getOrDefault(emptyList())
            val categories = items
                .groupBy { it.connectionId }
                .mapNotNull { (_, serverItems) ->
                    val first = serverItems.first()
                    val mediaItems = serverItems.map { resumeItem ->
                        val subtitle = if (resumeItem.season != null && resumeItem.episode != null) {
                            val ep = "S${resumeItem.season.toString().padStart(2, '0')}E${resumeItem.episode.toString().padStart(2, '0')}"
                            resumeItem.episodeTitle?.let { "$ep · $it" } ?: ep
                        } else ""
                        com.arflix.tv.data.model.MediaItem(
                            id = resumeItem.tmdbId,
                            title = resumeItem.title,
                            subtitle = subtitle,
                            mediaType = resumeItem.mediaType,
                            image = resumeItem.imageUrl,
                            progress = resumeItem.progress,
                            showPlaybackProgress = true
                        )
                    }.distinctBy { "${it.mediaType.name}-${it.id}" }
                    if (mediaItems.isEmpty()) return@mapNotNull null
                    Category(
                        id = "server_resume_${first.connectionId}",
                        title = "Continue on ${first.serverName}",
                        items = mediaItems
                    )
                }
            publishServerResume(categories)
        }
    }

    private suspend fun publishServerResume(categories: List<Category>) {
        // Register entries in savedCatalogs so they appear in Catalog ordering settings.
        val entries = categories.map { it.id to it.title }
        runCatching { catalogRepository.syncServerResumeCatalogEntries(entries) }

        withContext(Dispatchers.Main) {
            val current = _uiState.value.categories.toMutableList()
            val newById = categories.associateBy { it.id }

            // Update items in-place for existing rows (preserves user-set ordering).
            var idx = 0
            while (idx < current.size) {
                val cat = current[idx]
                val updated = newById[cat.id]
                if (updated != null) {
                    current[idx] = updated
                } else if (cat.id.startsWith("server_resume_")) {
                    // Server no longer returning this connection — remove it.
                    current.removeAt(idx)
                    idx--
                }
                idx++
            }

            // Insert brand-new rows (first time we've seen this connection) after CW.
            val presentIds = current.map { it.id }.toHashSet()
            val newRows = categories.filter { it.id !in presentIds }
            if (newRows.isNotEmpty()) {
                val cwIdx = current.indexOfFirst { it.id == "continue_watching" }
                val insertIdx = if (cwIdx >= 0) cwIdx + 1 else 0
                current.addAll(insertIdx, newRows)
            }

            _uiState.value = _uiState.value.copy(categories = current)
        }
    }

    private fun loadHomeData() {
        loadHomeJob?.cancel()
        val requestId = ++loadHomeRequestId
        loadHomeJob = viewModelScope.launch loadHome@{
            // Skip delay - preloading now happens on profile focus for instant display
            // Only add minimal delay if no preloaded data exists yet
            if (!usedPreloadedData) {
                delay(50) // Minimal delay for LaunchedEffect to potentially set preloaded data
            }
            if (requestId != loadHomeRequestId) return@loadHome
            applyContentLanguageFromPrefs()

            try {
                if (_uiState.value.categories.isEmpty()) {
                    // Build the early skeleton from the default catalog list minus any
                    // preinstalled catalogs the user has explicitly hidden for the active
                    // profile. Without this filter, deleted catalogs flash back into view
                    // for 1-3 seconds on every cold load and on every loadHomeData() call,
                    // which is the "catalog reappears after deletion" symptom reported in
                    // issue #71 (and the duplicate #74 which we already closed).
                    val hiddenForSkeleton = runCatching {
                        catalogRepository.getHiddenPreinstalledCatalogIdsForActiveProfile().toSet()
                    }.getOrDefault(emptySet())
                    val skeletonDefaults = mediaRepository.getDefaultCatalogConfigs()
                        .filterNot { cfg -> cfg.isPreinstalled && cfg.id in hiddenForSkeleton }
                    val earlySkeleton = buildProfileSkeletonCategories(
                        savedCatalogs = skeletonDefaults,
                        cachedContinueWatching = emptyList()
                    )
                    if (requestId != loadHomeRequestId) return@loadHome
                    if (earlySkeleton.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = true,
                            isInitialLoad = false,
                            categories = earlySkeleton,
                            heroItem = earlySkeleton.firstOrNull()?.items?.firstOrNull { !it.isPlaceholder },
                            heroLogoUrl = null,
                            error = null
                        )
                    }
                }

                val cachedContinueWatching = preloadStartupContinueWatchingItems()
                val savedCatalogs = withContext(networkDispatcher) {
                    runCatching {
                        streamRepository.removeCustomAddonsByUrl(
                            CollectionTemplateManifest.autoInstalledAddonUrls() +
                                listOf(MediaRepository.STREAMING_COLLECTION_ADDON_URL)
                        )
                        val addons = streamRepository.installedAddons.first()
                        catalogRepository.syncAddonCatalogs(addons)
                        catalogRepository.syncHomeServerCatalogs(homeServerRepository.getCatalogCandidates())
                        // Ensure built-in preinstalled catalogs exist, then re-read
                        // the persisted catalog list so the returned value includes
                        // BOTH built-ins AND the addon catalogs just synced above.
                        // Previously `ensurePreinstalledDefaults()` was returned
                        // directly, and its output didn't include newly-synced
                        // addon catalogs — that's why user-added Stremio addons
                        // (Bharat Binge, AIO Metadata, etc.) appeared in the
                        // Catalogs tab but were dropped from the Home screen.
                        catalogRepository.ensurePreinstalledDefaults(
                            mediaRepository.getDefaultCatalogConfigs()
                        )
                        catalogRepository.getCatalogs()
                    }.getOrElse {
                        // If sync/defaults fail, fall back to whatever is already saved
                        // (includes user's custom Trakt catalogs) instead of only preinstalled defaults.
                        runCatching { catalogRepository.getCatalogs() }
                            .getOrDefault(mediaRepository.getDefaultCatalogConfigs())
                    }
                }
                savedCatalogById.clear()
                savedCatalogs.forEach { savedCatalogById[it.id] = it }
                categoryPaginationStates.clear()

                // When Home is opened from profile selection, avoid an empty frame by showing
                // profile-ordered skeleton rows immediately while real catalogs load.
                if (_uiState.value.categories.isEmpty()) {
                    val skeletonCategories = buildProfileSkeletonCategories(
                        savedCatalogs = savedCatalogs,
                        cachedContinueWatching = cachedContinueWatching
                    )
                    if (requestId != loadHomeRequestId) return@loadHome
                    if (skeletonCategories.isNotEmpty()) {
                        val skeletonHero = chooseInitialHero(skeletonCategories)
                        _uiState.value = _uiState.value.copy(
                            isLoading = true,
                            isInitialLoad = false,
                            categories = skeletonCategories,
                            heroItem = skeletonHero,
                            heroLogoUrl = null,
                            error = null
                        )
                    }
                } else {
                    // Keep preloaded/previous UI visible and refresh in background.
                    _uiState.value = _uiState.value.copy(isLoading = false, error = null)
                }

                val currentBaseCategories = _uiState.value.categories.filter {
                    it.id != "continue_watching" && !it.id.startsWith("collection_row_")
                }
                // On Now row is managed entirely by launchOnNowRowObserver() — never
                // build or remove it here. Preserve whatever is already in state or the
                // last successfully built copy so catalog reloads never wipe the row.
                val preservedOnNow = currentBaseCategories
                    .firstOrNull { it.id == FAVORITE_TV_CATEGORY_ID }
                    ?.takeIf { it.items.isNotEmpty() }
                    ?: lastFavoriteTvCategory
                // Cameras and events rows are managed by their own launchers.
                // Preservation is done fresh inside withContext so a fast home reload doesn't
                // capture a null snapshot before the 1.5s launcher has had a chance to fire.

                var categories = withContext(networkDispatcher) {
                    val baseCategories = runCatching {
                        mediaRepository.getHomeCategories()
                    }.getOrElse { emptyList() }

                    val baseById = LinkedHashMap<String, Category>().apply {
                        currentBaseCategories.forEach { put(it.id, it) }
                        baseCategories.forEach { put(it.id, it) }
                        if (preservedOnNow != null) {
                            put(FAVORITE_TV_CATEGORY_ID, preservedOnNow)
                        }
                        // If neither exists, omit — launchOnNowRowObserver() will insert
                        // it once the IPTV snapshot is populated.
                        put(APPS_CATEGORY_ID, buildInstalledAppsCategory())
                        // Read cameras/events from the CURRENT state (not the snapshot taken at
                        // the start of loadHomeData) so a fast reload doesn't wipe rows that
                        // the 1-1.5s launchers already injected. Gate on catalog config so
                        // hiding or moving to Discover in settings takes effect immediately.
                        val latestCats = _uiState.value.categories
                        val cameraCfg = savedCatalogById[CAMERAS_CATEGORY_ID]
                        if (cameraCfg?.isHidden != true && cameraCfg?.placement != CatalogPlacement.DISCOVER) {
                            latestCats.firstOrNull { it.id == CAMERAS_CATEGORY_ID }
                                ?.takeIf { it.items.isNotEmpty() }
                                ?.let { put(CAMERAS_CATEGORY_ID, it) }
                        }
                        val eventsCfg = savedCatalogById[NEOLINK_EVENTS_CATEGORY_ID]
                        if (eventsCfg?.isHidden != true && eventsCfg?.placement != CatalogPlacement.DISCOVER) {
                            latestCats.firstOrNull { it.id == NEOLINK_EVENTS_CATEGORY_ID }
                                ?.takeIf { it.items.isNotEmpty() }
                                ?.let { put(NEOLINK_EVENTS_CATEGORY_ID, it) }
                        }
                    }

                    // Split preinstalled into TMDB-based and MDBList-based
                    val tmdbPreinstalled = savedCatalogs
                        .filter {
                            !it.isHidden && it.placement != CatalogPlacement.DISCOVER && it.placement != CatalogPlacement.SEARCH &&
                                it.isPreinstalled &&
                                it.sourceUrl.isNullOrBlank() &&
                                !isCollectionRailConfig(it) &&
                                !isCollectionTileConfig(it)
                        }
                        .mapNotNull { cfg ->
                            val category = baseById[cfg.id] ?: return@mapNotNull null
                            if (cfg.title.isNotBlank() && cfg.title != category.title) {
                                category.copy(title = cfg.title)
                            } else {
                                category
                            }
                        }
                    // Load MDBList preinstalled catalogs - first 8 immediately, rest lazily on scroll
                    val mdblistConfigs = savedCatalogs.filter {
                        !it.isHidden && it.placement != CatalogPlacement.DISCOVER && it.placement != CatalogPlacement.SEARCH &&
                            it.isPreinstalled &&
                            !it.sourceUrl.isNullOrBlank() &&
                            !isCollectionRailConfig(it) &&
                            !isCollectionTileConfig(it)
                    }
                    val initialBatch = mdblistConfigs.take(initialMdblistCatalogCount)
                    val deferredBatch = mdblistConfigs.drop(initialBatch.size)
                    val mdblistInitial = initialBatch.map { cfg ->
                        async(networkDispatcher) {
                            try {
                                val result = mediaRepository.loadCustomCatalogPage(
                                    catalog = cfg,
                                    offset = 0,
                                    limit = if (isHardCappedTop10Catalog(cfg.id)) TOP_10_ITEM_LIMIT else 20
                                )
                                if (result.items.isNotEmpty()) {
                                    Category(id = cfg.id, title = cfg.title, items = result.items)
                                        .withTop10CapIfNeeded()
                                } else null
                            } catch (_: Exception) { null }
                        }
                    }
                    val mdblistCategories = mdblistInitial.awaitAll().filterNotNull()
                    // Create placeholder categories for deferred ones (will load on scroll)
                    val deferredCategories = deferredBatch.map { cfg -> Category(id = cfg.id, title = cfg.title, items = emptyList()) }
                    // Merge both lists maintaining the saved catalog order
                    val allPreinstalledById = (tmdbPreinstalled + mdblistCategories + deferredCategories).associateBy { it.id }

                    // Load deferred catalogs in background in batches of 3
                    if (deferredBatch.isNotEmpty()) {
                        viewModelScope.launch(networkDispatcher) {
                            deferredBatch.chunked(3).forEach { batch ->
                                val results = batch.map { cfg ->
                                    async {
                                        try {
                                            val result = mediaRepository.loadCustomCatalogPage(
                                                catalog = cfg,
                                                offset = 0,
                                                limit = if (isHardCappedTop10Catalog(cfg.id)) TOP_10_ITEM_LIMIT else 20
                                            )
                                            if (result.items.isNotEmpty()) {
                                                Category(id = cfg.id, title = cfg.title, items = result.items)
                                                    .withTop10CapIfNeeded()
                                            } else null
                                        } catch (_: Exception) { null }
                                    }
                                }.awaitAll().filterNotNull()
                                if (results.isNotEmpty()) {
                                    val current = _uiState.value.categories.toMutableList()
                                    for (cat in results) {
                                        val idx = current.indexOfFirst { it.id == cat.id }
                                        if (idx >= 0) current[idx] = cat else current.add(cat)
                                    }
                                    _uiState.value = _uiState.value.copy(categories = current)
                                }
                            }
                        }
                    }
                    val preinstalled = savedCatalogs
                        .filter {
                            it.isPreinstalled &&
                                !isCollectionRailConfig(it) &&
                                !isCollectionTileConfig(it)
                        }
                        .mapNotNull { cfg -> allPreinstalledById[cfg.id] }
                    val customCatalogConfigs = savedCatalogs.filter { cfg ->
                        !cfg.isHidden && cfg.placement != CatalogPlacement.DISCOVER && cfg.placement != CatalogPlacement.SEARCH && isCustomCatalogConfig(cfg)
                    }

                    // Fetch ALL custom catalogs (Trakt lists, user-added) in parallel
                    // right here in the bulk path, instead of deferring to
                    // loadCustomCatalogsIncrementally which loaded them one-by-one and
                    // caused slow incremental insertion. This way everything appears in
                    // the single bulk categories set at line ~1250.
                    val customSemaphore = kotlinx.coroutines.sync.Semaphore(if (isLowRamDevice) 1 else 2)
                    val freshCustomCategories = customCatalogConfigs.map { cfg ->
                        async(networkDispatcher) {
                            customSemaphore.withPermit {
                                try {
                                    val result = mediaRepository.loadCustomCatalogPage(
                                        catalog = cfg,
                                        offset = 0,
                                        limit = catalogInitialLimit(cfg)
                                    )
                                    if (result.items.isNotEmpty()) {
                                        val category = Category(id = cfg.id, title = cfg.title, items = result.items)
                                            .withTop10CapIfNeeded()
                                        categoryPaginationStates[cfg.id] = CategoryPaginationState(
                                            loadedCount = category.items.size,
                                            hasMore = result.hasMore && !isHardCappedTop10Catalog(cfg.id)
                                        )
                                        category
                                    } else if (cfg.sourceType == com.arflix.tv.data.model.CatalogSourceType.ADDON ||
                                        cfg.sourceType == com.arflix.tv.data.model.CatalogSourceType.HOME_SERVER) {
                                        // Keep addon/server rows in allById even when empty so the
                                        // resolved-list step can decide to keep them visible.
                                        Category(id = cfg.id, title = cfg.title, items = emptyList())
                                    } else null
                                } catch (_: Exception) { null }
                            }
                        }
                    }.awaitAll().filterNotNull()

                    // Fall back to previously cached data for any custom catalog
                    // that failed to load in this round
                    val stickyCustomById = currentBaseCategories
                        .filter { category ->
                            customCatalogConfigs.any { it.id == category.id } && category.items.isNotEmpty()
                        }
                        .associateBy { it.id }
                    val freshCustomById = freshCustomCategories.associateBy { it.id }

                    // Build the final list following the user's savedCatalogs order
                    // for ALL catalog types (preinstalled, MDBList, custom/Trakt).
                    // The previous code added all preinstalled first, then appended
                    // custom catalogs at the end — which ignored the user's configured
                    // ordering and always put Favorite TV before custom catalogs
                    // regardless of where the user placed it.
                    val allById = LinkedHashMap<String, Category>()
                    // Seed with preinstalled data
                    preinstalled.forEach { allById[it.id] = it }
                    // Layer on fresh custom catalog data (prefer fresh, fall back to cached)
                    customCatalogConfigs.forEach { cfg ->
                        val cat = freshCustomById[cfg.id]
                            ?: stickyCustomById[cfg.id]
                            ?: return@forEach
                        allById[cat.id] = cat
                    }
                    // Fallback: if no preinstalled loaded at all, use base/cached
                    if (preinstalled.isEmpty()) {
                        val fallback = baseCategories.ifEmpty { currentBaseCategories.ifEmpty { lastResolvedBaseCategories } }
                        fallback.forEach { allById.putIfAbsent(it.id, it) }
                    }

                    // Resolve in savedCatalogs order — this is the user's configured
                    // catalog ordering from Settings > Catalogs.
                    // Addon-provided service-branded catalogs (Netflix, Disney+,
                    // Hulu etc. rows contributed by aio-metadata / org.kris /
                    // local scraper addons) are suppressed here - we already surface
                    // those services via the collection-tile Services row, so
                    // having a second identically-named catalog row below it
                    // was duplicative per user feedback. Preinstalled catalogs
                    // (Trending, Just Added, Top 10, etc.) are never skipped.
                    val serviceTitleBlocklist = setOf(
                        "netflix", "prime video", "prime", "apple tv+", "apple tv plus",
                        "apple tv", "disney+", "disney plus", "paramount+", "paramount plus",
                        "hbo max", "max", "hulu", "shudder", "jiohotstar", "sonyliv",
                        "sky", "crunchyroll", "peacock"
                    )
                    val resolved = savedCatalogs.mapNotNull { cfg ->
                        if (isCollectionRailConfig(cfg) || isCollectionTileConfig(cfg)) {
                            return@mapNotNull null
                        }
                        val cat = allById[cfg.id]
                        if (cat == null) return@mapNotNull null
                        // Preinstalled rows (Trending, Top 10, etc.) are hidden when empty.
                        // Addon and home-server rows are kept even when empty so they stay
                        // visible rather than silently disappearing when the addon is
                        // unreachable or items can't resolve to TMDB.
                        val isUserConfiguredRow = cfg.sourceType == com.arflix.tv.data.model.CatalogSourceType.ADDON ||
                            cfg.sourceType == com.arflix.tv.data.model.CatalogSourceType.HOME_SERVER
                        if (cat.items.isEmpty() && !isUserConfiguredRow) return@mapNotNull null
                        if (!cfg.isPreinstalled &&
                            cat.title.trim().lowercase(Locale.US) in serviceTitleBlocklist
                        ) return@mapNotNull null
                        cat.withTop10CapIfNeeded()
                    }.toMutableList()
                    resolved
                }
                val collectionRows = withContext(networkDispatcher) {
                    val collectionConfigs = savedCatalogs.filter { cfg ->
                        !cfg.isHidden && cfg.placement != CatalogPlacement.DISCOVER && cfg.placement != CatalogPlacement.SEARCH &&
                            isCollectionTileConfig(cfg) && CollectionTemplateManifest.isValidCollectionConfig(cfg)
                    }

                    savedCatalogs.mapNotNull { cfg ->
                        if (cfg.isHidden || cfg.placement == CatalogPlacement.DISCOVER || cfg.placement == CatalogPlacement.SEARCH ||
                            !isCollectionRailConfig(cfg) || !CollectionTemplateManifest.isValidCollectionConfig(cfg)) {
                            return@mapNotNull null
                        }
                        val group = cfg.collectionGroup ?: return@mapNotNull null
                        val items = collectionConfigs
                            .filter { it.collectionGroup == group }
                        if (items.isEmpty()) {
                            null
                        } else {
                            HomeCollectionRow(
                                id = collectionRowId(group),
                                title = cfg.title,
                                items = items
                            )
                        }
                    }
                }
                val categoryById = categories.associateBy { it.id }
                val collectionCategoryById = collectionRows.associateBy { it.id }
                categories = savedCatalogs.mapNotNull { cfg ->
                    if (cfg.isHidden || cfg.placement == CatalogPlacement.DISCOVER || cfg.placement == CatalogPlacement.SEARCH) return@mapNotNull null
                    when {
                        isCollectionTileConfig(cfg) -> null
                        isCollectionRailConfig(cfg) -> {
                            val group = cfg.collectionGroup ?: return@mapNotNull null
                            collectionCategoryById[collectionRowId(group)]?.let { toCollectionCategory(it) }
                        }
                        else -> categoryById[cfg.id]
                    }
                }.toMutableList()
                if (categories.any { it.id != "continue_watching" && !it.id.startsWith("collection_row_") }) {
                    lastResolvedBaseCategories = categories.filter {
                        it.id != "continue_watching" && !it.id.startsWith("collection_row_")
                    }
                }
                categories.forEach { category ->
                    if (category.id != "continue_watching" && !category.id.startsWith("collection_row_")) {
                        categoryPaginationStates[category.id] = CategoryPaginationState(
                            loadedCount = category.items.size,
                            hasMore = category.items.size >= categoryPageSize && !isHardCappedTop10Catalog(category.id)
                        )
                    }
                }
                if (requestId != loadHomeRequestId) return@loadHome

                // CW resolution is decoupled from loadHomeData to prevent cancellation.
                // loadHomeData() is called multiple times during startup (profile load,
                // catalog observer, cloud pull) — each call cancels the previous one via
                // loadHomeJob?.cancel(). When getContinueWatching() was inside this job,
                // the 30-60s Trakt API fetch (97+ shows × progress call) was always
                // getting cancelled before it could finish. Now it runs in its own
                // independent coroutine that survives loadHomeData restarts.
                // NOTE: cwPlacementInLoad / isCwHiddenInLoad read from prefs below (with watchlist prefs).
                // We use cachedCwPlacement/cachedCwHidden here since prefs are read after this block.
                if (!cachedCwHidden && cachedCwPlacement == CatalogPlacement.HOME) {
                    val existingCW = _uiState.value.categories.firstOrNull {
                        it.id == "continue_watching" && it.items.isNotEmpty() &&
                            it.items.none { item -> item.isPlaceholder }
                    }
                    val cwInsert = cachedCwSortOrder.coerceIn(0, categories.size)
                    if (existingCW != null) {
                        categories.add(cwInsert, existingCW)
                    } else if (cachedContinueWatching.isNotEmpty()) {
                        val merged = mergeContinueWatchingResumeData(cachedContinueWatching)
                        val cwCat = Category(
                            id = "continue_watching",
                            title = "Continue Watching",
                            items = merged.map { it.toMediaItem() }
                        )
                        categories.add(cwInsert, cwCat)
                    }
                }
                // Re-inject "Continue on <Server>" rows — they're dynamically added
                // by launchServerResumeFetch() and not part of savedCatalogs, so they
                // get wiped on every categories rebuild. Preserve the last-known rows
                // so they stay visible during reloads; launchServerResumeFetch() will
                // refresh them when it fires.
                val existingServerResume = _uiState.value.categories
                    .filter { it.id.startsWith("server_resume_") && it.items.isNotEmpty() }
                if (existingServerResume.isNotEmpty()) {
                    val cwIdx = categories.indexOfFirst { it.id == "continue_watching" }
                    var insertIdx = if (cwIdx >= 0) cwIdx + 1 else 0
                    existingServerResume.forEach { row ->
                        if (categories.none { it.id == row.id }) {
                            categories.add(insertIdx++, row)
                        }
                    }
                }

                // Inject watchlist row — only when placement is HOME
                val watchlistPrefs = runCatching { context.settingsDataStore.data.first() }.getOrNull()
                val watchlistPlacementInLoad = watchlistPrefs
                    ?.get(com.arflix.tv.data.repository.WATCHLIST_PLACEMENT_KEY)
                    ?.let { runCatching { CatalogPlacement.valueOf(it) }.getOrNull() }
                    ?: CatalogPlacement.HOME
                val watchlistSortOrder = watchlistPrefs
                    ?.get(com.arflix.tv.data.repository.WATCHLIST_SORT_ORDER_KEY)
                    ?.toIntOrNull() ?: 0
                val isWatchlistHiddenInLoad = watchlistPrefs?.get(com.arflix.tv.data.repository.WATCHLIST_HIDDEN_KEY) ?: false
                val cwPlacementInLoad = watchlistPrefs
                    ?.get(com.arflix.tv.data.repository.CW_PLACEMENT_KEY)
                    ?.let { runCatching { CatalogPlacement.valueOf(it) }.getOrNull() }
                    ?: CatalogPlacement.HOME
                val isCwHiddenInLoad = watchlistPrefs?.get(com.arflix.tv.data.repository.CW_HIDDEN_KEY) ?: false
                val liveWatchlistItems = watchlistRepository.watchlistItems.value
                if (liveWatchlistItems.isNotEmpty() && !isWatchlistHiddenInLoad && watchlistPlacementInLoad == CatalogPlacement.HOME) {
                    val cwIdx = categories.indexOfFirst { it.id == "continue_watching" }
                    val baseIdx = if (cwIdx >= 0) cwIdx + 1 else 0
                    val insertIdx = (baseIdx + watchlistSortOrder).coerceIn(0, categories.size)
                    categories.add(insertIdx, Category(
                        id = WATCHLIST_CATEGORY_ID,
                        title = "My Watchlist",
                        items = liveWatchlistItems
                    ))
                }

                // Launch the independent CW fetch
                launchContinueWatchingFetch()

                // During catalog-triggered reloads, chooseInitialHero can pick
                // the first Continue Watching item and overwrite the currently
                // focused hero. Preserve the existing hero when possible, and
                // let the focus watcher correct it on the next focus change.
                val heroItem = if (_uiState.value.heroItem != null) {
                    val currentHero = _uiState.value.heroItem!!
                    // Preserve current hero during reload.  Try to find it in the fresh
                    // categories; if the same id/mediaType still exists, use the fresh
                    // instance to ensure reference consistency.  If not found, keep the
                    // old hero — it's still valid UI and the hero-update LaunchedEffect
                    // will correct it when the user moves focus.
                    categories.asSequence()
                        .flatMap { it.items.asSequence() }
                        .firstOrNull { it.id == currentHero.id && it.mediaType == currentHero.mediaType }
                        ?: currentHero
                } else {
                    chooseInitialHero(categories)
                }

                // Preload logos for the first visible rows so card overlays appear immediately.
                // Skip IPTV items — their channel logo is already in item.image.
                // Skip items with disk-cached logos — no network call needed.
                val itemsToPreload = categories
                    .take(initialLogoPrefetchRows)
                    .flatMap { it.items.take(initialLogoPrefetchItemsPerRow) }
                    .filter { isActionableMediaItem(it) && !isIptvItem(it) }

                // Separate: items already in logo cache (instant) vs items needing fetch
                val cachedLogoResults = mutableMapOf<String, String>()
                val itemsNeedingFetch = mutableListOf<MediaItem>()
                for (item in itemsToPreload) {
                    val key = "${item.mediaType}_${item.id}"
                    val cached = getCachedLogo(key)
                    if (cached != null) {
                        cachedLogoResults[key] = cached
                    } else {
                        itemsNeedingFetch.add(item)
                    }
                }

                // If we have disk-cached logos, publish them immediately (before network fetch)
                if (cachedLogoResults.isNotEmpty()) {
                    val heroLogoFromCache = heroItem?.let { item ->
                        val key = "${item.mediaType}_${item.id}"
                        cachedLogoResults[key]
                    }
                    if (heroLogoFromCache != null || cachedLogoResults.isNotEmpty()) {
                        // Use high batch limit for initial display — preload all cached logos at once
                        if (isStartupSettling()) {
                            scheduleStartupImageWarmup(logoUrls = cachedLogoResults.values.toList())
                        } else {
                            preloadLogoImages(
                                cachedLogoResults.values.toList(),
                                batchLimit = if (isLowRamDevice) 4 else 6
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            isLoading = _uiState.value.isLoading,
                            categories = categories,
                            collectionRows = collectionRows,
                            heroItem = heroItem,
                            heroLogoUrl = heroLogoFromCache ?: _uiState.value.heroLogoUrl
                        )
                        heroItem?.let { item ->
                            if (isStartupSettling()) {
                                scheduleStartupHeroHydration(item)
                            } else {
                                hydrateHeroDetailsIfNeeded(item)
                            }
                        }
                        replaceCardLogoState(snapshotLogoCache())
                    }
                }

                // Fetch remaining logos from TMDB (only items not in disk cache)
                val logoJobs = itemsNeedingFetch.map { item ->
                    async(networkDispatcher) {
                        val key = "${item.mediaType}_${item.id}"
                        try {
                            val logoUrl = mediaRepository.getLogoUrl(item.mediaType, item.id)
                            if (logoUrl != null) key to logoUrl else null
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                val fetchedLogoResults = logoJobs.awaitAll().filterNotNull().toMap()
                if (requestId != loadHomeRequestId) return@loadHome

                val logoResults = cachedLogoResults + fetchedLogoResults

                // Phase 1.2: Preload actual images with Coil (only newly fetched)
                if (fetchedLogoResults.isNotEmpty()) {
                    if (isStartupSettling()) {
                        scheduleStartupImageWarmup(logoUrls = fetchedLogoResults.values.toList())
                    } else {
                        preloadLogoImages(
                            fetchedLogoResults.values.toList(),
                            batchLimit = if (isLowRamDevice) 4 else 6
                        )
                    }
                }

                // Also preload backdrop images for first row
                val backdropUrls = categories.firstOrNull()?.items?.take(initialBackdropPrefetchItems)?.mapNotNull {
                    it.backdrop ?: it.image
                } ?: emptyList()
                if (isStartupSettling()) {
                    scheduleStartupImageWarmup(backdropUrls = backdropUrls)
                } else {
                    preloadBackdropImages(backdropUrls)
                }

                val heroLogoUrl = heroItem?.let { item ->
                    val key = "${item.mediaType}_${item.id}"
                    getCachedLogo(key) ?: logoResults[key]
                }

                putCachedLogos(logoResults)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isInitialLoad = false,
                    categories = categories,
                    collectionRows = collectionRows,
                    heroItem = heroItem,
                    heroLogoUrl = heroLogoUrl,
                    isAuthenticated = traktRepository.isAuthenticated.first(),
                    liveChannelEpg = iptvNowNextMap.toMap(),
                    error = null
                )
                heroItem?.let { item ->
                    if (isStartupSettling()) {
                        scheduleStartupHeroHydration(item)
                    } else {
                        hydrateHeroDetailsIfNeeded(item)
                    }
                }
                replaceCardLogoState(snapshotLogoCache())
                refreshWatchedBadges()
                scheduleStartupCatalogImageWarmup(categories)
                // Re-inject cameras and events rows immediately (no delay) so they're present
                // even when loadHomeData completes before the init-time launchers have fired.
                launchCamerasRow(delayMs = 0L)
                launchNeolinkEventsRow(delayMs = 0L)

                // Persist the real categories to disk so the next app launch
                // shows them immediately without waiting for TMDB API calls.
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { persistCategoriesCache(categories) }
                }

                // On mobile/touch devices, prefetch logos for ALL categories in the background
                // since there's no D-pad focus to trigger incremental loading.
                if (!isLowRamDevice && !isTvDevice) {
                    viewModelScope.launch(networkDispatcher) {
                        delay(1_500L) // Let initial UI settle first
                        for (i in initialLogoPrefetchRows until categories.size) {
                            preloadLogosForCategory(i, prioritizeVisible = false)
                            delay(300L) // Stagger to avoid API rate limiting
                        }
                    }
                }

                // If the Favorite TV / On Now row is missing (IPTV snapshot not yet in
                // memory when home data loaded), retry in the background after a short
                // delay so the row appears once IPTV finishes loading from disk cache.
                if (_uiState.value.categories.none { it.id == FAVORITE_TV_CATEGORY_ID }) {
                    viewModelScope.launch(Dispatchers.IO) {
                        // Try immediately — IPTV cache may just need a moment to settle
                        refreshFavoriteTvEpg(networkFetch = false)
                        if (_uiState.value.categories.none { it.id == FAVORITE_TV_CATEGORY_ID }) {
                            delay(2_000L)
                            refreshFavoriteTvEpg(networkFetch = false)
                        }
                        if (_uiState.value.categories.none { it.id == FAVORITE_TV_CATEGORY_ID }) {
                            delay(8_000L)
                            refreshFavoriteTvEpg(networkFetch = false)
                        }
                    }
                }

                // If the cached Favorite TV row has stale/empty EPG text, refresh it
                // after Home has had time to settle. Doing network EPG work during
                // startup competes with TV D-pad navigation and causes GC stalls.
                val favTvCat = _uiState.value.categories.firstOrNull { it.id == FAVORITE_TV_CATEGORY_ID }
                if (favTvCat != null && favTvCat.items.any { it.overview == "Live TV" }) {
                    viewModelScope.launch(Dispatchers.IO) {
                        delay(if (isLowRamDevice) 8 * 60_000L else 6 * 60_000L)
                        val channelIds = favTvCat.items.mapNotNull { getIptvChannelId(it) }.toSet()
                        if (channelIds.isNotEmpty()) {
                            refreshFavoriteTvEpg(networkFetch = false)
                            // Also update hero if it's an IPTV item showing stale EPG
                            val currentHero = _uiState.value.heroItem
                            if (currentHero != null && isIptvItem(currentHero) && currentHero.overview == "Live TV") {
                                val updatedCat = _uiState.value.categories.firstOrNull { it.id == FAVORITE_TV_CATEGORY_ID }
                                val updatedHero = updatedCat?.items?.firstOrNull { it.id == currentHero.id }
                                if (updatedHero != null) {
                                    withContext(Dispatchers.Main.immediate) {
                                        _uiState.value = _uiState.value.copy(heroItem = updatedHero)
                                    }
                                }
                            }
                        }
                    }
                }

                // Custom catalogs are now loaded in the bulk path above (parallel
                // fetch alongside TMDB + MDBList). No need for incremental loading
                // which caused the slow one-by-one appearance and the viewport "trip"
                // when rows were inserted mid-list.

                viewModelScope.launch cw@{
                    if (requestId != loadHomeRequestId) return@cw
                    delay(if (isLowRamDevice) 2_200L else 1_200L)
                    if (requestId != loadHomeRequestId) return@cw
                    val freshContinueWatching = resolveContinueWatchingItemsStable(forceFresh = true)
                    if (requestId != loadHomeRequestId) return@cw

                    if (freshContinueWatching.isNotEmpty()) {
                        val mergedContinueWatching = mergeContinueWatchingResumeData(freshContinueWatching)
                        val continueWatchingCategory = Category(
                            id = "continue_watching",
                            title = "Continue Watching",
                            items = mergedContinueWatching.map { it.toMediaItem() }
                        )
                        continueWatchingCategory.items.forEach { mediaRepository.cacheItem(it) }
                        lastContinueWatchingItems = continueWatchingCategory.items
                        lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                        cwHolder.update(continueWatchingCategory)
                        if (!cachedCwHidden && cachedCwPlacement == CatalogPlacement.HOME) {
                            val updated = _uiState.value.categories.toMutableList()
                            val index = updated.indexOfFirst { it.id == "continue_watching" }
                            if (index >= 0) {
                                updated[index] = continueWatchingCategory
                            } else {
                                updated.add(cachedCwSortOrder.coerceIn(0, updated.size), continueWatchingCategory)
                            }
                            _uiState.value = _uiState.value.copy(categories = updated)
                        }
                    }
                }
              } catch (e: Exception) {
                if (requestId != loadHomeRequestId) return@loadHome
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isInitialLoad = false,
                    error = if (_uiState.value.categories.isEmpty()) e.message ?: "Failed to load content" else null
                )
            } finally {
            }
        }
    }

    /**
     * Warm critical details assets while the card is focused so opening Details feels instant:
     * - clearlogo URL
     * - next-episode season episodes for TV (or season 1)
     * The calls hit in-memory/network caches and are safe to fire-and-forget.
     */
    fun prefetchDetailsAssets(item: MediaItem) {
        if (!isActionableMediaItem(item) || isIptvItem(item) || isCollectionItem(item)) return
        val key = "${item.mediaType}_${item.id}_${item.nextEpisode?.seasonNumber ?: 1}"
        if (!prefetchedDetailsKeys.add(key)) return
        viewModelScope.launch(networkDispatcher) {
            runCatching { mediaRepository.getLogoUrl(item.mediaType, item.id) }
            if (item.mediaType == MediaType.TV) {
                val season = item.nextEpisode?.seasonNumber ?: 1
                runCatching { mediaRepository.getSeasonEpisodes(item.id, season) }
            }
        }
    }

    private fun loadCustomCatalogsIncrementally(savedCatalogs: List<CatalogConfig>) {
        customCatalogsJob?.cancel()
        customCatalogsJob = viewModelScope.launch(networkDispatcher) {
            delay(if (isLowRamDevice) 700L else 350L)
            val customCatalogs = savedCatalogs.filter { cfg ->
                !cfg.isHidden && cfg.placement != CatalogPlacement.DISCOVER && cfg.placement != CatalogPlacement.SEARCH && isCustomCatalogConfig(cfg)
            }
            if (customCatalogs.isEmpty()) return@launch
            val customIds = customCatalogs.map { it.id }.toSet()
            val existingCustomById = _uiState.value.categories
                .filter { category -> customIds.contains(category.id) && category.items.isNotEmpty() }
                .associateBy { it.id }
            val baseCategories = _uiState.value.categories.filterNot { customIds.contains(it.id) }
            val baseById = baseCategories.associateBy { it.id }

            val loadedById = java.util.concurrent.ConcurrentHashMap<String, Category>()
            var lastCustomCatalogPublishMs = 0L
            fun publishMerged() {
                val latestState = _uiState.value
                val currentCategories = latestState.categories.toMutableList()

                // Preserve CW from latest state
                val latestCW = latestState.categories.firstOrNull {
                    it.id == "continue_watching" && it.items.isNotEmpty()
                }

                // For each custom catalog that has loaded, update it IN-PLACE if it
                // already has a slot in the displayed list, or APPEND it at the end
                // if it's brand new. This prevents mid-list insertions that cause
                // LazyColumn to re-layout and the viewport to jump ("trip" bug).
                // Base (TMDB) catalogs are NOT touched here — they're set in the
                // main loadHomeData path which does a single bulk replacement.
                var anyChange = false
                for (id in customIds) {
                    val freshData = loadedById[id]
                        ?: existingCustomById[id]
                        ?: continue
                    if (freshData.items.isEmpty()) continue

                    val existingIdx = currentCategories.indexOfFirst { it.id == id }
                    if (existingIdx >= 0) {
                        if (currentCategories[existingIdx].items.size != freshData.items.size) {
                            currentCategories[existingIdx] = freshData
                            anyChange = true
                        }
                    } else {
                        // Append new custom catalog at end — no mid-list insert
                        currentCategories.add(freshData)
                        anyChange = true
                    }
                }

                // Also update Favorite TV if it exists in base but not yet in displayed list
                val favTv = baseById[FAVORITE_TV_CATEGORY_ID]
                if (favTv != null && favTv.items.isNotEmpty()) {
                    val favIdx = currentCategories.indexOfFirst { it.id == FAVORITE_TV_CATEGORY_ID }
                    if (favIdx >= 0) {
                        currentCategories[favIdx] = favTv
                        anyChange = true
                    } else {
                        currentCategories.add(favTv)
                        anyChange = true
                    }
                }

                // Restore CW at its configured position
                if (latestCW != null) {
                    val cwIdx = currentCategories.indexOfFirst { it.id == "continue_watching" }
                    if (cwIdx >= 0) {
                        currentCategories[cwIdx] = latestCW
                    } else {
                        currentCategories.add(cachedCwSortOrder.coerceIn(0, currentCategories.size), latestCW)
                        anyChange = true
                    }
                }

                if (!anyChange) return
                _uiState.value = latestState.copy(categories = currentCategories)
            }
            suspend fun publishMergedThrottled(force: Boolean = false) {
                if (!force) {
                    val elapsed = SystemClock.elapsedRealtime() - lastCustomCatalogPublishMs
                    val waitMs = (450L - elapsed).coerceAtLeast(0L)
                    if (waitMs > 0L) delay(waitMs)
                }
                withContext(Dispatchers.Main.immediate) {
                    publishMerged()
                    lastCustomCatalogPublishMs = SystemClock.elapsedRealtime()
                }
            }
            publishMergedThrottled(force = true)

            // Load custom catalogs in parallel (up to 3 concurrently) for faster appearance
            val catalogSemaphore = kotlinx.coroutines.sync.Semaphore(if (isLowRamDevice) 2 else 3)
            val jobs = customCatalogs.map { catalog ->
                async(networkDispatcher) {
                    catalogSemaphore.withPermit {
                        val firstPage = runCatching {
                            mediaRepository.loadCustomCatalogPage(
                                catalog = catalog,
                                offset = 0,
                                limit = catalogInitialLimit(catalog)
                            )
                        }.getOrNull()
                        if (firstPage == null || firstPage.items.isEmpty()) {
                            loadedById[catalog.id] = Category(
                                id = catalog.id,
                                title = catalog.title,
                                items = emptyList()
                            )
                            categoryPaginationStates[catalog.id] = CategoryPaginationState(
                                loadedCount = 0,
                                hasMore = false
                            )
                        } else {
                            val category = Category(
                                id = catalog.id,
                                title = catalog.title,
                                items = firstPage.items
                            ).withTop10CapIfNeeded()
                            loadedById[catalog.id] = category
                            categoryPaginationStates[catalog.id] = CategoryPaginationState(
                                loadedCount = category.items.size,
                                hasMore = firstPage.hasMore && !isHardCappedTop10Catalog(catalog.id)
                            )
                        }
                        // Coalesce incremental row inserts so catalog arrivals do
                        // not repeatedly relayout Home while the user is scrolling.
                        publishMergedThrottled()
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    fun maybeLoadNextPageForCategory(categoryId: String, focusedItemIndex: Int) {
        if (categoryId == "continue_watching") return
        if (isHardCappedTop10Catalog(categoryId)) return
        val currentCategory = _uiState.value.categories.firstOrNull { it.id == categoryId } ?: return
        if (currentCategory.items.isEmpty() || currentCategory.items.all { it.isPlaceholder }) return
        if (focusedItemIndex < currentCategory.items.size - nearEndThreshold) return
        loadNextPageForCategory(categoryId)
    }

    private fun loadNextPageForCategory(categoryId: String) {
        if (isHardCappedTop10Catalog(categoryId)) return
        val currentCount = _uiState.value.categories.firstOrNull { it.id == categoryId }?.items?.size ?: 0
        if (currentCount >= HOME_ROW_ITEM_CAP) return
        val pagination = categoryPaginationStates.getOrPut(categoryId) {
            CategoryPaginationState(loadedCount = currentCount)
        }
        if (!pagination.hasMore || pagination.isLoading) return

        pagination.isLoading = true
        viewModelScope.launch(networkDispatcher) {
            try {
                val currentCategories = _uiState.value.categories
                val currentCategory = currentCategories.firstOrNull { it.id == categoryId } ?: return@launch

                val catalog = savedCatalogById[categoryId]
                val result = if (catalog?.isPreinstalled == true && catalog.sourceUrl.isNullOrBlank()) {
                    // Pure TMDB preinstalled catalog (no MDBList source)
                    val nextPage = (currentCategory.items.size / categoryPageSize) + 1
                    mediaRepository.loadHomeCategoryPage(categoryId, nextPage)
                } else {
                    // MDBList/custom catalog (including preinstalled MDBList ones)
                    val cfg = catalog ?: return@launch
                    mediaRepository.loadCustomCatalogPage(
                        catalog = cfg,
                        offset = currentCategory.items.size,
                        limit = categoryPageSize
                    )
                }

                if (result.items.isEmpty()) {
                    pagination.hasMore = false
                    return@launch
                }

                val seen = currentCategory.items
                    .map { "${it.mediaType.name}_${it.id}" }
                    .toHashSet()
                val uniqueNewItems = result.items.filter { item ->
                    seen.add("${item.mediaType.name}_${item.id}")
                }
                if (uniqueNewItems.isEmpty()) {
                    pagination.hasMore = false
                    return@launch
                }

                val updatedCategories = currentCategories.map { category ->
                    if (category.id == categoryId) {
                        val merged = (category.items + uniqueNewItems).take(HOME_ROW_ITEM_CAP)
                        if (merged.size >= HOME_ROW_ITEM_CAP) pagination.hasMore = false
                        category.copy(items = merged)
                    } else {
                        category
                    }
                }

                uniqueNewItems.forEach { mediaRepository.cacheItem(it) }
                val logoEntries = uniqueNewItems.take(6).mapNotNull { item ->
                    if (!isActionableMediaItem(item) || isIptvItem(item)) return@mapNotNull null
                    val key = "${item.mediaType}_${item.id}"
                    if (hasCachedLogo(key) || !logoFetchInFlight.add(key)) return@mapNotNull null
                    val logo = runCatching {
                        mediaRepository.getLogoUrl(item.mediaType, item.id)
                    }.getOrNull()
                    logoFetchInFlight.remove(key)
                    if (logo == null) return@mapNotNull null
                    key to logo
                }
                if (logoEntries.isNotEmpty()) {
                    val logoMap = logoEntries.toMap()
                    if (putCachedLogos(logoMap)) {
                        scheduleLogoCachePublish()
                    }
                    preloadLogoImages(logoMap.values.toList())
                }
                preloadBackdropImages(uniqueNewItems.take(incrementalBackdropPrefetchItems).mapNotNull { it.backdrop ?: it.image })

                pagination.loadedCount = updatedCategories
                    .firstOrNull { it.id == categoryId }
                    ?.items
                    ?.size
                    ?: pagination.loadedCount
                pagination.hasMore = result.hasMore

                _uiState.value = _uiState.value.copy(categories = updatedCategories)
            } catch (_: Exception) {
                // Keep UI stable; user can retry naturally by continuing to browse the row.
            } finally {
                pagination.isLoading = false
            }
        }
    }

    fun loadAllPagesForCategory(categoryId: String) {
        if (isHardCappedTop10Catalog(categoryId)) return
        viewModelScope.launch {
            var pagesLoaded = 0
            while (pagesLoaded < 50) {
                val pagination = categoryPaginationStates[categoryId]
                if (pagination != null && !pagination.hasMore) break
                if (pagination?.isLoading == true) {
                    delay(200)
                    continue
                }
                loadNextPageForCategory(categoryId)
                pagesLoaded++
                delay(200) // give isLoading a moment to flip before re-checking
            }
        }
    }

    private enum class LibraryBrowseKind { SHOWS, MOVIES }

    // Nav row "Movies"/"Shows" cards target these — whichever home-server
    // library catalogue (Jellyfin/Emby/Plex "Movies"/"Shows" folder) is
    // already configured, same one startBrowseAll()/CategoryBrowseOverlay
    // already know how to render as a full library browse. Null when no such
    // catalogue exists (no home server connected) — caller falls back to the
    // trending-catalog nav target instead of a dead card.
    fun findMoviesLibraryCatalogId(): String? =
        savedCatalogById.entries.firstOrNull { detectLibraryBrowseKind(it.value) == LibraryBrowseKind.MOVIES }?.key

    fun findShowsLibraryCatalogId(): String? =
        savedCatalogById.entries.firstOrNull { detectLibraryBrowseKind(it.value) == LibraryBrowseKind.SHOWS }?.key

    private fun detectLibraryBrowseKind(catalog: CatalogConfig): LibraryBrowseKind? {
        if (catalog.sourceType != com.arflix.tv.data.model.CatalogSourceType.HOME_SERVER) return null
        val sourceRef = catalog.sourceRef ?: return null
        val parsed = HomeServerRepository.parseCatalogSourceRef(sourceRef) ?: return null
        val (_, _, collectionType) = parsed
        val type = collectionType.lowercase(java.util.Locale.US)
        return when {
            type in setOf("show", "shows", "series", "tvshows") -> LibraryBrowseKind.SHOWS
            type in setOf("movie", "movies") -> LibraryBrowseKind.MOVIES
            else -> null
        }
    }

    fun deleteLibraryItem(item: MediaItem, entry: LibraryBrowseEntry, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(networkDispatcher) {
            val ok = when {
                entry.sonarrSeriesId != null -> sonarrRepository.deleteSeries(entry.sonarrSeriesId)
                entry.radarrMovieId != null -> radarrRepository.deleteMovie(entry.radarrMovieId)
                else -> false
            }
            if (ok) {
                val key = "${item.mediaType}_${item.id}"
                _browseItems.value = _browseItems.value.filterNot { "${it.mediaType}_${it.id}" == key }
                _browseLibraryMeta.value = _browseLibraryMeta.value - key
            }
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun startBrowseAll(categoryId: String) {
        _browseItems.value = emptyList()
        _browseLibraryMeta.value = emptyMap()
        val catalog = savedCatalogById[categoryId]
        // Preinstalled/in-memory catalogs (watchlist, CW) have items already in the category;
        // overlay falls back to category.items when _browseItems is empty — no network load needed.
        if (catalog == null || catalog.sourceType == com.arflix.tv.data.model.CatalogSourceType.PREINSTALLED) {
            _isLibrarySourcedBrowse.value = false
            return
        }
        val libraryKind = detectLibraryBrowseKind(catalog)
        // Set optimistically, synchronously, before Sonarr/Radarr configuration is
        // even confirmed on IO — so the overlay never renders the mismatched
        // Jellyfin preview for this category, not even for one frame. Corrected
        // below once configuration is confirmed (falls back to false if neither
        // is actually configured).
        _isLibrarySourcedBrowse.value = libraryKind != null
        _isBrowseLoading.value = true
        viewModelScope.launch(networkDispatcher) {
            try {
                val usedLibrarySource = when (libraryKind) {
                    LibraryBrowseKind.SHOWS -> if (sonarrRepository.isConfigured()) {
                        loadShowsFromSonarr(); true
                    } else false
                    LibraryBrowseKind.MOVIES -> if (radarrRepository.isConfigured()) {
                        loadMoviesFromRadarr(); true
                    } else false
                    null -> false
                }
                _isLibrarySourcedBrowse.value = usedLibrarySource
                if (usedLibrarySource) return@launch

                val effectiveCatalog = getBrowseCatalog(catalog)
                var offset = 0
                val pageSize = 50
                while (true) {
                    val page = try {
                        mediaRepository.loadCustomCatalogPage(effectiveCatalog, offset, pageSize)
                    } catch (_: Exception) { break }
                    if (page.items.isEmpty()) break
                    _browseItems.value = _browseItems.value + page.items
                    if (!page.hasMore) break
                    offset += page.items.size
                    if (offset >= 5000) break
                }
            } finally {
                _isBrowseLoading.value = false
            }
        }
    }

    // Sonarr tracks every series regardless of file presence, unlike Jellyfin
    // which can only show what's actually on disk — this is the primary source
    // for "All Shows" when Sonarr is configured, so fully-watched/cleaned-up
    // shows (zero files) still appear instead of silently vanishing.
    //
    // Resolved in chunks rather than one giant batch: each series needs a TMDB
    // tvdb-lookup + details call, so a full ~90-series library can take several
    // seconds end to end. Populating _browseItems progressively means the user
    // sees content quickly instead of staring at "Loading…", and it also avoids
    // one single disruptive late list-swap that would otherwise fight with
    // whatever they've already navigated to (see the browse overlay's own focus
    // comments for why a late swap is a real problem, not just cosmetic).
    private suspend fun loadShowsFromSonarr() {
        val allSeries = sonarrRepository.getAllSeries()
        val semaphore = Semaphore(4)
        allSeries.chunked(LIBRARY_BROWSE_CHUNK_SIZE).forEach { chunk ->
            val resolved = coroutineScope {
                chunk.map { series ->
                    async(networkDispatcher) {
                        semaphore.withPermit {
                            val tvdbId = series.tvdbId ?: return@withPermit null
                            val ref = runCatching {
                                mediaRepository.resolveTvdbToTmdbRef(tvdbId, com.arflix.tv.data.model.MediaType.TV)
                            }.getOrNull() ?: return@withPermit null
                            val item = runCatching { mediaRepository.getTvDetails(ref.second) }.getOrNull()
                                ?: return@withPermit null
                            series to item
                        }
                    }
                }.mapNotNull { it.await() }
            }
            if (resolved.isEmpty()) return@forEach
            _browseItems.value = _browseItems.value + resolved.map { it.second }
            _browseLibraryMeta.value = _browseLibraryMeta.value + resolved.associate { (series, item) ->
                "${item.mediaType}_${item.id}" to com.arflix.tv.data.model.LibraryBrowseEntry(
                    sonarrSeriesId = series.seriesId,
                    statusLabel = series.status,
                    fileStateLabel = when {
                        series.totalEpisodeCount <= 0 -> null
                        series.episodeFileCount >= series.totalEpisodeCount -> "Available"
                        series.episodeFileCount > 0 -> "Partial"
                        else -> "Missing"
                    },
                    assignedRule = series.assignedRule,
                    episodeFileCount = series.episodeFileCount,
                    totalEpisodeCount = series.totalEpisodeCount,
                )
            }
        }
    }

    // Radarr already returns tmdbId directly (no tvdb-style resolution needed),
    // and — same rationale as Sonarr above — tracks every movie regardless of
    // file presence, so this is the primary source for "All Movies" when configured.
    // Chunked for the same progressive-population reason as loadShowsFromSonarr.
    private suspend fun loadMoviesFromRadarr() {
        val allMovies = radarrRepository.getAllMovies()
        val semaphore = Semaphore(4)
        allMovies.chunked(LIBRARY_BROWSE_CHUNK_SIZE).forEach { chunk ->
            val resolved = coroutineScope {
                chunk.map { movie ->
                    async(networkDispatcher) {
                        semaphore.withPermit {
                            val tmdbId = movie.tmdbId ?: return@withPermit null
                            val item = runCatching { mediaRepository.getMovieDetails(tmdbId) }.getOrNull()
                                ?: return@withPermit null
                            movie to item
                        }
                    }
                }.mapNotNull { it.await() }
            }
            if (resolved.isEmpty()) return@forEach
            _browseItems.value = _browseItems.value + resolved.map { it.second }
            _browseLibraryMeta.value = _browseLibraryMeta.value + resolved.associate { (movie, item) ->
                "${item.mediaType}_${item.id}" to com.arflix.tv.data.model.LibraryBrowseEntry(
                    radarrMovieId = movie.movieId,
                    statusLabel = movie.status,
                    fileStateLabel = if (movie.hasFile) "Available" else "Missing",
                    assignedRule = movie.assignedRule,
                )
            }
        }
    }

    private fun getBrowseCatalog(catalog: CatalogConfig): CatalogConfig {
        if (catalog.sourceType != com.arflix.tv.data.model.CatalogSourceType.HOME_SERVER) return catalog
        val sourceRef = catalog.sourceRef ?: return catalog
        val parsed = HomeServerRepository.parseCatalogSourceRef(sourceRef) ?: return catalog
        val (serverKey, collectionId, collectionType) = parsed
        val isShows = collectionType.lowercase(java.util.Locale.US) in setOf("show", "shows", "series", "tvshows")
        if (!isShows) return catalog
        val browseRef = HomeServerRepository.buildCatalogSourceRefParts(serverKey, collectionId, "${collectionType}_all")
        return catalog.copy(sourceRef = browseRef)
    }

    private fun buildProfileSkeletonCategories(
        savedCatalogs: List<com.arflix.tv.data.model.CatalogConfig>,
        cachedContinueWatching: List<ContinueWatchingItem>
    ): List<Category> {
        val placeholderItems = (1..HOME_PLACEHOLDER_ITEM_COUNT).map { index ->
            MediaItem(
                id = -index,
                title = "",
                mediaType = MediaType.MOVIE,
                isPlaceholder = true
            )
        }

        val rows = mutableListOf<Category>()
        if (!cachedCwHidden && cachedCwPlacement == CatalogPlacement.HOME) {
            if (cachedContinueWatching.isNotEmpty()) {
                rows.add(
                    Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = cachedContinueWatching.map { it.toMediaItem() }
                    )
                )
            } else {
                rows.add(
                    Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = placeholderItems
                    )
                )
            }
        }

        savedCatalogs.forEach { cfg ->
            if (isCollectionTileConfig(cfg)) return@forEach
            rows.add(
                Category(
                    id = if (isCollectionRailConfig(cfg)) {
                        collectionRowId(cfg.collectionGroup ?: return@forEach)
                    } else {
                        cfg.id
                    },
                    title = cfg.title,
                    items = placeholderItems
                )
            )
        }

        return rows
    }

    /**
     * Phase 1.2: Preload images into Coil's memory/disk cache
     * Uses target display sizes to reduce decode overhead.
     */
    private fun preloadImagesWithCoil(urls: List<String>, width: Int, height: Int, batchLimit: Int = 0) {
        // Bumped from 2/4 to 4/8 — enough to preload one full row of cards on
        // the home screen. The old limits left the majority of visible cards
        // without preloaded images on cold start.
        val defaultLimit = if (isLowRamDevice) 4 else 8
        val limit = if (batchLimit > 0) batchLimit else defaultLimit
        val uniqueUrls = urls.filter { url ->
            preloadedRequests.add("$url|${width}x${height}")
        }.take(limit)
        if (uniqueUrls.isEmpty()) return

        uniqueUrls.forEach { url ->
            val requestWidth = width.coerceAtLeast(1)
            val requestHeight = height.coerceAtLeast(1)
            val cacheKey = "$url|${requestWidth}x$requestHeight"
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(requestWidth, requestHeight)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .memoryCacheKey(cacheKey)
                .placeholderMemoryCacheKey(cacheKey)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .build()
            imageLoader.enqueue(request)
        }
    }

    private fun preloadLogoImages(urls: List<String>, batchLimit: Int = 0) {
        preloadImagesWithCoil(urls, logoPreloadWidth, logoPreloadHeight, batchLimit)
    }

    private fun preloadBackdropImages(urls: List<String>) {
        preloadImagesWithCoil(urls, backdropPreloadWidth, backdropPreloadHeight)
    }

    private fun scheduleIdleBackdropPreload(urls: List<String>) {
        if (urls.isEmpty()) return
        val requestedAt = lastFocusChangeTime
        viewModelScope.launch(networkDispatcher) {
            delay(BACKDROP_IDLE_PREFETCH_MS)
            if (lastFocusChangeTime != requestedAt) return@launch
            preloadBackdropImages(urls)
        }
    }

    fun refresh() {
        loadHomeData()
    }

    private suspend fun resolveContinueWatchingItems(forceFresh: Boolean): List<ContinueWatchingItem> {
        val isTraktAuthenticated = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
        // Debug: write CW state to a file we can pull via adb
        val items: List<ContinueWatchingItem> = if (isTraktAuthenticated) {
            // When connected to Trakt, use ONLY Trakt as the source of truth for
            // Continue Watching. The previous code merged local/history items which
            // polluted the CW row with shows not on the user's Trakt — e.g., items
            // watched before connecting Trakt, or items from Xadarr watch_history
            // that Trakt doesn't know about. Trakt users expect CW to match exactly
            // what Trakt shows as "Up Next."
            val traktItems = if (forceFresh) {
                runCatching { traktRepository.getContinueWatching(forceRefresh = true) }.getOrDefault(emptyList())
            } else {
                val cached = traktRepository.getCachedContinueWatching()
                if (cached.isNotEmpty()) cached else runCatching { traktRepository.getContinueWatching() }.getOrDefault(emptyList())
            }

            // Enrich Trakt items with local resume position data where available.
            // This preserves exact playback position (e.g., "resume at 32:15") which
            // Trakt's progress API doesn't provide — it only gives episode-level progress.
            val localItems = runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            val historyItems = loadContinueWatchingFromHistory()
            val localByKey = (localItems + historyItems).associateBy { "${it.mediaType}:${it.id}" }

            traktItems.map { traktItem ->
                val key = "${traktItem.mediaType}:${traktItem.id}"
                val local = localByKey[key]
                if (local != null && local.season == traktItem.season && local.episode == traktItem.episode) {
                    // Same episode — enrich with local resume position
                    traktItem.copy(
                        resumePositionSeconds = maxOf(traktItem.resumePositionSeconds, local.resumePositionSeconds),
                        durationSeconds = maxOf(traktItem.durationSeconds, local.durationSeconds),
                        episodeTitle = traktItem.episodeTitle ?: local.episodeTitle,
                        backdropPath = traktItem.backdropPath ?: local.backdropPath,
                        posterPath = traktItem.posterPath ?: local.posterPath,
                        totalEpisodes = if (traktItem.totalEpisodes > 0) traktItem.totalEpisodes else local.totalEpisodes,
                        watchedEpisodes = if (traktItem.watchedEpisodes > 0) traktItem.watchedEpisodes else local.watchedEpisodes
                    )
                } else {
                    traktItem
                }
            }
        } else {
            val historyItems = loadContinueWatchingFromHistory()
            if (historyItems.isNotEmpty()) {
                // Cloud watch_history is the shared source of truth for non-Trakt
                // profiles. Only fall back to local CW when the cloud has nothing
                // yet, so one device's stale local cache cannot override another
                // device's synced progress/episode.
                historyItems
            } else {
                runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            }
        }

        val persistedDismissedKeys = runCatching {
            traktRepository.getDismissedContinueWatchingShowKeys()
        }.getOrDefault(emptySet())

        val sanitizedItems = sanitizeContinueWatchingItems(items)

        val dismissed = sanitizedItems.filter { item ->
            val showKey = continueWatchingKey(item.mediaType, item.id)
            dismissedContinueWatchingKeys.contains(showKey) || persistedDismissedKeys.contains(showKey)
        }
        return sanitizedItems
            .filterNot { item ->
                val showKey = continueWatchingKey(item.mediaType, item.id)
                dismissedContinueWatchingKeys.contains(showKey) || persistedDismissedKeys.contains(showKey)
            }
            // Don't filter by progress range for Trakt items — Trakt's progress API
            // is authoritative. Some "up next" items have synthetic progress = 0
            // (brand new season premiere, never started) which the old 1..99 filter
            // was dropping. For non-Trakt items, keep the 1..99 filter to avoid
            // showing completed (100%) or never-started (0%) items.
            .filter { item ->
                if (isTraktAuthenticated) true else item.progress in 1..99
            }
            .take(Constants.MAX_CONTINUE_WATCHING)
    }

    /**
     * Pull the full cloud state (addons, catalogs, settings, profiles) on ON_RESUME.
     * This is the critical fix for the "addon added on phone but not on TV" symptom:
     * when the TV comes back from background, the WebSocket may be dead, so we do
     * an explicit pull to catch any account_sync_state changes that were missed.
     * Throttled to at most once per 10 seconds to avoid excessive pulls on rapid
     * activity transitions (e.g., player back → home → details → home).
     */
    @Volatile
    private var lastCloudPullTimestamp = 0L
    private val cloudPullThrottleMs = 10_000L

    fun pullCloudStateOnResume() {
        val now = System.currentTimeMillis()
        if (now - lastCloudPullTimestamp < cloudPullThrottleMs) return
        lastCloudPullTimestamp = now
        viewModelScope.launch(Dispatchers.IO) {
            if (!cloudSyncRepository.isSyncServerConfigured()) return@launch
            // If a previous push failed (dirty flag), retry it now before pulling.
            // This ensures the cloud has our latest state before we pull the other
            // device's state on top of it — preventing stale overwrites.
            if (cloudSyncRepository.isPushDirty) {
                android.util.Log.i("HomeViewModel", "Retrying dirty push before pull")
                runCatching { cloudSyncRepository.pushToCloud() }
            }
            val result = runCatching {
                cloudSyncRepository.pullFromCloud()
            }
            result.onSuccess { restoreResult ->
                if (restoreResult == CloudSyncRepository.RestoreResult.RESTORED) {
                    val profileId = profileManager.getProfileIdSync().ifBlank { "default" }
                    if (activeRuntimeProfileId != null && activeRuntimeProfileId != profileId) {
                        resetProfileRuntimeState(profileId)
                    }
                    // Cloud state was applied — reload home data so catalog changes,
                    // addon changes, and settings from the other device take effect
                    // immediately without waiting for the observeCatalogs flow.
                    loadHomeData()
                    refreshContinueWatchingOnly(force = true)
                    restartContinueWatchingFetch()
                }
            }.onFailure {
                android.util.Log.w("HomeViewModel", "ON_RESUME cloud pull failed: ${it.message}")
            }
        }
    }

    fun refreshContinueWatchingOnly(force: Boolean = false) {
        // Don't cancel an in-progress Trakt fetch - restarting a fetch that takes
        // 10+ seconds (424 watched shows, 41 filtered, 50 progress API calls) wastes
        // time and causes Continue Watching to never appear. Multiple callers
        // (ON_RESUME, isAuthenticated observer, sync completion) would keep cancelling
        // each other's fetches. The throttle mechanism prevents redundant fetches.
        if (refreshContinueWatchingJob?.isActive == true) {
            if (force) refreshContinueWatchingJob?.cancel() else return
        }
        refreshContinueWatchingJob = viewModelScope.launch {
            try {
                val now = SystemClock.elapsedRealtime()
                val startCategories = _uiState.value.categories
                val continueWatchingIndexAtStart = startCategories.indexOfFirst { it.id == "continue_watching" }
                val existingContinueWatching = startCategories.getOrNull(continueWatchingIndexAtStart)
                val hasPlaceholders = existingContinueWatching?.items?.any { it.isPlaceholder } == true

                // Allow refresh if we have placeholders (need to replace them), otherwise throttle
                if (!force && !hasPlaceholders && now - lastContinueWatchingUpdateMs < CONTINUE_WATCHING_REFRESH_MS) {
                    return@launch
                }

                val resolvedContinueWatching = resolveContinueWatchingItemsStable(forceFresh = force)

                if (resolvedContinueWatching.isNotEmpty()) {
                    val mergedContinueWatching = mergeContinueWatchingResumeData(resolvedContinueWatching)
                    val continueWatchingCategory = Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = mergedContinueWatching.map { it.toMediaItem() }
                    )
                    continueWatchingCategory.items.forEach { mediaRepository.cacheItem(it) }
                    lastContinueWatchingItems = continueWatchingCategory.items
                    lastContinueWatchingUpdateMs = now
                    cwHolder.update(continueWatchingCategory)
                    if (!cachedCwHidden && cachedCwPlacement == CatalogPlacement.HOME) {
                        val latestCategories = _uiState.value.categories.toMutableList()
                        val continueWatchingIndex = latestCategories.indexOfFirst { it.id == "continue_watching" }
                        if (continueWatchingIndex >= 0) {
                            latestCategories[continueWatchingIndex] = continueWatchingCategory
                        } else {
                            latestCategories.add(cachedCwSortOrder.coerceIn(0, latestCategories.size), continueWatchingCategory)
                        }
                        _uiState.value = _uiState.value.copy(categories = latestCategories)
                    }
                    refreshWatchedBadges()
                } else {
                    // No new data from any source
                    val latestCategories = _uiState.value.categories.toMutableList()
                    val continueWatchingIndex = latestCategories.indexOfFirst { it.id == "continue_watching" }
                    val latestHasPlaceholders = latestCategories
                        .getOrNull(continueWatchingIndex)
                        ?.items
                        ?.any { it.isPlaceholder } == true
                    if (hasPlaceholders) {
                        // We had placeholders but no data loaded - remove the placeholder category
                        if (continueWatchingIndex >= 0) {
                            latestCategories.removeAt(continueWatchingIndex)
                            _uiState.value = _uiState.value.copy(categories = latestCategories)
                        }
                    } else if (!latestHasPlaceholders && continueWatchingIndex >= 0) {
                        // Continue Watching exists with real data - preserve it exactly as is
                        return@launch
                    } else if (lastContinueWatchingItems.isNotEmpty()) {
                        // Only resurrect last-known CW items for non-Trakt profiles.
                        // For Trakt profiles, if the fresh fetch returned empty, that
                        // means the user genuinely has nothing to continue — don't
                        // re-insert stale items that may include non-Trakt ghost data.
                        val isTraktAuth = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
                        if (isTraktAuth) {
                            return@launch // Trakt said empty — trust it
                        }
                        val persistedDismissedKeys = runCatching {
                            traktRepository.getDismissedContinueWatchingShowKeys()
                        }.getOrDefault(emptySet())
                        val safeItems = lastContinueWatchingItems.filterNot { item ->
                            val showKey = continueWatchingKey(item.mediaType, item.id)
                            dismissedContinueWatchingKeys.contains(showKey) || persistedDismissedKeys.contains(showKey)
                        }
                        if (safeItems.isEmpty()) {
                            return@launch
                        }
                        val continueWatchingCategory = Category(
                            id = "continue_watching",
                            title = "Continue Watching",
                            items = safeItems
                        )
                        latestCategories.add(cachedCwSortOrder.coerceIn(0, latestCategories.size), continueWatchingCategory)
                        _uiState.value = _uiState.value.copy(categories = latestCategories)
                    }
                    // Else: No data anywhere - nothing to show, UI already doesn't have it
                }
            } catch (e: Exception) {
                // Silently fail - don't clear existing data on error
            }
        }
    }

    private suspend fun loadContinueWatchingFromHistory(): List<ContinueWatchingItem> {
        return try {
            val entries = watchHistoryRepository.getContinueWatching()
            if (entries.isEmpty()) return emptyList()
            val mapped = entries.distinctBy { entry ->
                // Deduplicate at show level for TV — only keep the most recent episode per show.
                // Entries are already sorted by updated_at desc, so distinctBy keeps the latest.
                "${entry.media_type}:${entry.show_tmdb_id}"
            }.mapNotNull { entry ->
                val mediaType = if (entry.media_type == "tv") MediaType.TV else MediaType.MOVIE
                val storedPct = (entry.progress * 100f).toInt()
                val hasResumePosition = entry.position_seconds > 0L
                val derivedPct = when {
                    storedPct > 0 -> storedPct
                    entry.duration_seconds > 0 && hasResumePosition ->
                        ((entry.position_seconds.toFloat() / entry.duration_seconds.toFloat()) * 100f).toInt()
                    hasResumePosition -> 1
                    else -> 0
                }
                val resolvedTitle = entry.title
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: entry.episode_title
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: "Untitled"
                ContinueWatchingItem(
                    id = entry.show_tmdb_id,
                    title = resolvedTitle,
                    mediaType = mediaType,
                    progress = derivedPct.coerceIn(0, 100),
                    resumePositionSeconds = entry.position_seconds.coerceAtLeast(0L),
                    durationSeconds = entry.duration_seconds.coerceAtLeast(0L),
                    season = entry.season,
                    episode = entry.episode,
                    episodeTitle = entry.episode_title,
                    backdropPath = entry.backdrop_path,
                    posterPath = entry.poster_path
                )
            }
            traktRepository.enrichContinueWatchingItems(mapped)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun loadContinueWatchingFromHistoryStable(): List<ContinueWatchingItem> {
        return try {
            val entries = watchHistoryRepository.getContinueWatching()
            if (entries.isEmpty()) return emptyList()
            val mapped = entries.distinctBy { entry ->
                "${entry.media_type}:${entry.show_tmdb_id}"
            }.mapNotNull { entry ->
                val mediaType = if (entry.media_type == "tv") MediaType.TV else MediaType.MOVIE
                val storedPct = (entry.progress * 100f).toInt()
                val hasResumePosition = entry.position_seconds > 0L
                val derivedPct = when {
                    storedPct > 0 -> storedPct
                    entry.duration_seconds > 0 && hasResumePosition ->
                        ((entry.position_seconds.toFloat() / entry.duration_seconds.toFloat()) * 100f).toInt()
                    hasResumePosition -> 1
                    else -> 0
                }
                val resolvedTitle = entry.title
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: entry.episode_title
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: "Untitled"
                ContinueWatchingItem(
                    id = entry.show_tmdb_id,
                    title = resolvedTitle,
                    mediaType = mediaType,
                    progress = derivedPct.coerceIn(0, 100),
                    resumePositionSeconds = entry.position_seconds.coerceAtLeast(0L),
                    durationSeconds = entry.duration_seconds.coerceAtLeast(0L),
                    season = entry.season,
                    episode = entry.episode,
                    episodeTitle = entry.episode_title,
                    backdropPath = entry.backdrop_path,
                    posterPath = entry.poster_path,
                    updatedAtMs = parseContinueWatchingUpdatedAt(entry.updated_at, entry.paused_at)
                )
            }
            traktRepository.enrichContinueWatchingItems(mapped)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveContinueWatchingItemsStable(forceFresh: Boolean): List<ContinueWatchingItem> {
        val isTraktAuthenticated = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
        val items = if (isTraktAuthenticated) {
            val traktItems = if (forceFresh) {
                runCatching { traktRepository.getContinueWatching(forceRefresh = true) }
                    .onFailure { error ->
                        AppLogger.recordException(
                            throwable = error,
                            context = mapOf(
                                "error_area" to "ContinueWatching",
                                "cw_phase" to "trakt_fresh",
                                "force_fresh" to forceFresh.toString()
                            )
                        )
                    }
                    .getOrDefault(emptyList())
            } else {
                val cached = traktRepository.getCachedContinueWatching()
                if (cached.isNotEmpty()) {
                    cached
                } else {
                    runCatching { traktRepository.getContinueWatching() }
                        .onFailure { error ->
                            AppLogger.recordException(
                                throwable = error,
                                context = mapOf(
                                    "error_area" to "ContinueWatching",
                                    "cw_phase" to "trakt_cached_miss"
                                )
                            )
                        }
                        .getOrDefault(emptyList())
                }
            }
            val localItems = runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            val historyItems = loadContinueWatchingFromHistoryStable()
            if (traktItems.isEmpty() && historyItems.isNotEmpty()) {
                historyItems
            } else {
                mergeTraktAndRecentLocalContinueWatching(
                    traktItems = traktItems,
                    localItems = localItems,
                    historyItems = historyItems
                )
            }
        } else {
            val historyItems = loadContinueWatchingFromHistoryStable()
            if (historyItems.isNotEmpty()) {
                historyItems
            } else {
                runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            }
        }

        val persistedDismissedKeys = runCatching {
            traktRepository.getDismissedContinueWatchingShowKeys()
        }.getOrDefault(emptySet())

        val repairedItems = repairContinueWatchingMetadataIfNeeded(items)
        return sanitizeContinueWatchingItems(repairedItems)
            .filterNot { item ->
                val showKey = continueWatchingKey(item.mediaType, item.id)
                dismissedContinueWatchingKeys.contains(showKey) || persistedDismissedKeys.contains(showKey)
            }
            .filter { item ->
                if (isTraktAuthenticated) true
                else item.isUpNext || item.progress in 1..99 || item.resumePositionSeconds > 0L
            }
            .sortedWith(
                compareBy<ContinueWatchingItem> { if (it.isUpNext) 0 else 1 }
                    .thenByDescending { it.updatedAtMs }
            )
            .take(Constants.MAX_CONTINUE_WATCHING)
    }

    private suspend fun preloadStartupContinueWatchingItems(): List<ContinueWatchingItem> {
        val isTraktAuthenticated = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
        val items = if (isTraktAuthenticated) {
            runCatching { traktRepository.preloadContinueWatchingCache() }
                .onFailure { error ->
                    AppLogger.recordException(
                        throwable = error,
                        context = mapOf(
                            "error_area" to "ContinueWatching",
                            "cw_phase" to "preload_trakt_cache"
                        )
                    )
                }
                .getOrDefault(emptyList())
        } else {
            val historyItems = loadContinueWatchingFromHistoryStable()
            if (historyItems.isNotEmpty()) {
                historyItems
            } else {
                runCatching { traktRepository.getLocalContinueWatching() }.getOrDefault(emptyList())
            }
        }

        val persistedDismissedKeys = runCatching {
            traktRepository.getDismissedContinueWatchingShowKeys()
        }.getOrDefault(emptySet())

        val repairedItems = repairContinueWatchingMetadataIfNeeded(items)
        return sanitizeContinueWatchingItems(repairedItems)
            .filterNot { item ->
                val showKey = continueWatchingKey(item.mediaType, item.id)
                dismissedContinueWatchingKeys.contains(showKey) || persistedDismissedKeys.contains(showKey)
            }
            .filter { item ->
                if (isTraktAuthenticated) true
                else item.isUpNext || item.progress in 1..99 || item.resumePositionSeconds > 0L
            }
            .sortedWith(
                compareBy<ContinueWatchingItem> { if (it.isUpNext) 0 else 1 }
                    .thenByDescending { it.updatedAtMs }
            )
            .take(Constants.MAX_CONTINUE_WATCHING)
    }

    private suspend fun mergeContinueWatchingResumeData(
        items: List<ContinueWatchingItem>
    ): List<ContinueWatchingItem> {
        if (items.isEmpty()) return emptyList()
        return try {
            val historyEntries = watchHistoryRepository.getContinueWatching()
            if (historyEntries.isEmpty()) return items

            val sortedHistory = historyEntries.sortedByDescending { it.updated_at ?: it.paused_at.orEmpty() }
            val byExactKey = sortedHistory.associateBy { entry ->
                "${entry.media_type}:${entry.show_tmdb_id}:${entry.season ?: -1}:${entry.episode ?: -1}"
            }
            val byShowKey = sortedHistory.associateBy { entry ->
                "${entry.media_type}:${entry.show_tmdb_id}"
            }

            items.map { item ->
                val mediaTypeKey = if (item.mediaType == MediaType.TV) "tv" else "movie"
                val exactKey = "$mediaTypeKey:${item.id}:${item.season ?: -1}:${item.episode ?: -1}"
                val showKey = "$mediaTypeKey:${item.id}"
                // Only use exact episode-matched history, NOT show-level fallback.
                // Show-level fallback caused old episode's position to leak to the next episode.
                val match = byExactKey[exactKey]
                if (match == null) {
                    item
                } else {
                    val storedProgress = (match.progress * 100f).toInt()
                    val derivedProgress = if (storedProgress <= 0 && match.duration_seconds > 0 && match.position_seconds > 0) {
                        ((match.position_seconds.toFloat() / match.duration_seconds.toFloat()) * 100f).toInt()
                    } else {
                        storedProgress
                    }
                    item.copy(
                        progress = derivedProgress.coerceIn(0, 100),
                        resumePositionSeconds = match.position_seconds.coerceAtLeast(0L),
                        durationSeconds = maxOf(item.durationSeconds, match.duration_seconds.coerceAtLeast(0L)),
                        season = item.season ?: match.season,
                        episode = item.episode ?: match.episode,
                        episodeTitle = item.episodeTitle ?: match.episode_title
                    )
                }
            }
        } catch (_: Exception) {
            items
        }
    }

    private fun refreshWatchedBadges(immediate: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!immediate && now - lastWatchedBadgesRefreshMs < WATCHED_BADGES_REFRESH_MS) return

        watchedBadgesJob?.cancel()
        watchedBadgesJob = viewModelScope.launch(networkDispatcher) {
            if (!immediate) {
                delay(if (isLowRamDevice) 3_000L else 1_800L)
            }
            try {
                val isAuth = traktRepository.isAuthenticated.first()
                if (!isAuth) return@launch

                traktRepository.initializeWatchedCache()
                val categories = _uiState.value.categories
                if (categories.isEmpty()) return@launch

                val watchedMovies = traktRepository.getWatchedMoviesFromCache()

                // Performance: Build show watched map only for unique TV shows
                val showWatched = mutableMapOf<Int, Boolean>()
                val seenShows = mutableSetOf<Int>()
                for (category in categories) {
                    if (category.id == "continue_watching") continue
                    for (item in category.items) {
                        if (item.mediaType == MediaType.TV && seenShows.add(item.id)) {
                            showWatched[item.id] = traktRepository.hasWatchedEpisodes(item.id)
                        }
                    }
                }

                var anyChange = false
                val updatedCategories = categories.map { category ->
                    if (category.id == "continue_watching") {
                        category
                    } else {
                        var categoryChanged = false
                        val updatedItems = category.items.map { item ->
                            val newWatched = when (item.mediaType) {
                                MediaType.MOVIE -> watchedMovies.contains(item.id)
                                MediaType.TV -> showWatched[item.id] == true
                            }
                            if (item.isWatched != newWatched) {
                                categoryChanged = true
                                item.copy(isWatched = newWatched)
                            } else {
                                item
                            }
                        }
                        if (categoryChanged) {
                            anyChange = true
                            category.copy(items = updatedItems)
                        } else {
                            category
                        }
                    }
                }

                if (!anyChange) {
                    lastWatchedBadgesRefreshMs = SystemClock.elapsedRealtime()
                    return@launch
                }

                val heroItem = _uiState.value.heroItem
                val updatedHero = heroItem?.let { hero ->
                    updatedCategories.asSequence()
                        .flatMap { it.items.asSequence() }
                        .firstOrNull { it.id == hero.id && it.mediaType == hero.mediaType }
                        ?: hero
                }

                _uiState.value = _uiState.value.copy(
                    categories = updatedCategories,
                    heroItem = updatedHero
                )
                lastWatchedBadgesRefreshMs = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                System.err.println("HomeVM: refreshWatchedBadges failed: ${e.message}")
            }
        }
    }

    /**
     * Phase 1.4 & 6.1 & 6.2-6.3: Update hero with adaptive debouncing
     * Uses fast-scroll detection for smoother experience during rapid navigation
     */
    fun updateHeroVisualItemImmediately(item: MediaItem) {
        if (isCollectionItem(item)) {
            updateHeroItem(item)
            return
        }
        if (!isActionableMediaItem(item)) return

        val cacheKey = "${item.mediaType}_${item.id}"
        heroUpdateJob?.cancel()
        heroDetailsJob?.cancel()
        performHeroUpdate(item, getCachedLogo(cacheKey))
        scheduleHeroDetailsFetch(item, fastScrolling = true)
    }

    fun updateHeroItem(item: MediaItem) {
        if (isCollectionItem(item)) {
            if (item.isPlaceholder) return
            heroUpdateJob?.cancel()
            heroDetailsJob?.cancel()
            val catalog = collectionCatalogByMediaId[item.id]
            val collectionLogo = catalog?.collectionClearLogoUrl?.takeIf { it.isNotBlank() }
            // Prefer the high-res static hero art for the backdrop so the
            // hero area reads as a cinematic still rather than a looping GIF.
            val heroBackdrop = catalog?.collectionHeroImageUrl?.takeIf { it.isNotBlank() }
                ?: item.backdrop
            val heroItem = if (heroBackdrop != null && heroBackdrop != item.backdrop) {
                item.copy(backdrop = heroBackdrop)
            } else item
            _uiState.value = _uiState.value.copy(
                previousHeroItem = _uiState.value.heroItem,
                previousHeroLogoUrl = _uiState.value.heroLogoUrl,
                heroItem = heroItem,
                heroLogoUrl = collectionLogo,
                heroOverviewOverride = item.overview,
                heroTrailerKey = null,
                isHeroTransitioning = false
            )
            return
        }
        if (!isActionableMediaItem(item)) {
            return
        }

        val cacheKey = "${item.mediaType}_${item.id}"
        val cachedLogo = getCachedLogo(cacheKey)

        // Phase 6.2-6.3: Detect fast scrolling
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = currentTime - lastFocusChangeTime
        lastFocusChangeTime = currentTime

        val isFastScrolling = timeSinceLastChange < FAST_SCROLL_THRESHOLD_MS
        if (isFastScrolling) {
            consecutiveFastChanges++
        } else {
            consecutiveFastChanges = 0
        }

        // Adaptive debounce: higher during fast scroll sequences
        val debounceMs = when {
            consecutiveFastChanges > 3 -> FAST_SCROLL_DEBOUNCE_MS  // Very fast scroll
            consecutiveFastChanges > 1 -> HERO_DEBOUNCE_MS + 50    // Moderate fast scroll
            cachedLogo != null -> 0L  // Cached = instant
            else -> HERO_DEBOUNCE_MS  // Normal debounce
        }

        // Phase 1.4: If logo is cached and not fast-scrolling, update immediately
        val fastScrolling = consecutiveFastChanges > 1
        if (cachedLogo != null && !fastScrolling) {
            heroUpdateJob?.cancel()
            performHeroUpdate(item, cachedLogo)
            scheduleHeroDetailsFetch(item, fastScrolling)
            return
        }

        // Phase 6.1 + 6.2-6.3: Adaptive debounce
        heroUpdateJob?.cancel()
        heroDetailsJob?.cancel()
        heroUpdateJob = viewModelScope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
            }

            // Check if still the current focus after debounce
            val currentCachedLogo = getCachedLogo(cacheKey)
            performHeroUpdate(item, currentCachedLogo)
            scheduleHeroDetailsFetch(item, fastScrolling)

            // Fetch logo async if not cached (skip IPTV — uses channel logo directly)
            if (currentCachedLogo == null && isActionableMediaItem(item) && !isIptvItem(item)) {
                try {
                    val logoUrl = withContext(networkDispatcher) {
                        mediaRepository.getLogoUrl(item.mediaType, item.id)
                    }
                    if (logoUrl != null && _uiState.value.heroItem?.id == item.id) {
                        putCachedLogo(cacheKey, logoUrl)
                        _uiState.value = _uiState.value.copy(
                            heroLogoUrl = logoUrl,
                            isHeroTransitioning = false
                        )
                        scheduleLogoCachePublish()
                        // Preload the logo image
                        preloadLogoImages(listOf(logoUrl))
                    }
                } catch (e: Exception) {
                    // Logo fetch failed
                }
            }
        }
    }

    private fun performHeroUpdate(item: MediaItem, logoUrl: String?) {
        val currentState = _uiState.value
        val currentHero = currentState.heroItem
        if (currentHero?.id == item.id &&
            currentHero.mediaType == item.mediaType &&
            currentState.heroLogoUrl == logoUrl &&
            !currentState.isHeroTransitioning
        ) {
            return
        }

        // Save previous hero for crossfade animation, clear trailer for new hero
        _uiState.value = currentState.copy(
            previousHeroItem = currentState.heroItem,
            previousHeroLogoUrl = currentState.heroLogoUrl,
            heroItem = item,
            heroLogoUrl = logoUrl,
            heroOverviewOverride = null,
            heroTrailerKey = null,
            isHeroTransitioning = true
        )
    }

    private fun hydrateHeroDetailsIfNeeded(item: MediaItem) {
        if (!isActionableMediaItem(item) || isIptvItem(item) || isCollectionItem(item)) {
            return
        }

        // Fetch trailer for new hero item; skip if already loaded for this item (prevents restart mid-play)
        if (_uiState.value.trailerAutoPlay &&
            !(_uiState.value.heroItem?.id == item.id && _uiState.value.heroTrailerKey != null)
        ) {
            _uiState.value = _uiState.value.copy(heroTrailerKey = null)
            viewModelScope.launch(networkDispatcher) {
                try {
                    val trailerKey = mediaRepository.getTrailerKey(item.mediaType, item.id)
                    if (trailerKey != null && _uiState.value.heroItem?.id == item.id) {
                        _uiState.value = _uiState.value.copy(heroTrailerKey = trailerKey)
                        prefetchTrailerUrl(trailerKey)
                    }
                } catch (_: Exception) {}
            }
        }

        val normalizedOverview = item.overview.trim()
        val looksTruncated = normalizedOverview.endsWith("...") || normalizedOverview.length < 120
        if (normalizedOverview.isNotBlank() && !looksTruncated && item.duration.isNotBlank() && item.duration != "0m") {
            return
        }

        heroDetailsJob?.cancel()
        heroDetailsJob = viewModelScope.launch(networkDispatcher) {
            try {
                // Fetch details and network logo concurrently to avoid ~1s delay
                // on the clearlogo appearing after all other metadata.
                val detailsDeferred = async {
                    runCatching {
                        if (item.mediaType == MediaType.MOVIE) {
                            mediaRepository.getMovieDetails(item.id)
                        } else {
                            mediaRepository.getTvDetails(item.id)
                        }
                    }.getOrNull()
                }
                val logoDeferred = async {
                    resolvePrimaryNetworkLogo(item.mediaType, item.id)
                }
                val details = detailsDeferred.await()
                val primaryNetworkLogo = logoDeferred.await()
                val resolvedOverview = resolveBestOverview(
                    item = item,
                    candidateOverview = details?.overview?.ifBlank { item.overview } ?: item.overview
                )
                val detailsKey = "${item.mediaType}_${item.id}"
                heroDetailsCache[detailsKey] = HeroDetailsSnapshot(
                    duration = details?.duration.orEmpty(),
                    releaseDate = details?.releaseDate,
                    imdbRating = details?.imdbRating.orEmpty(),
                    tmdbRating = details?.tmdbRating.orEmpty(),
                    budget = details?.budget,
                    overview = resolvedOverview,
                    primaryNetworkLogo = primaryNetworkLogo
                )

                val latestHero = _uiState.value.heroItem
                if (latestHero?.id == item.id && latestHero.mediaType == item.mediaType) {
                    val updatedHero = latestHero.copy(
                        duration = details?.duration?.ifEmpty { latestHero.duration } ?: latestHero.duration,
                        releaseDate = details?.releaseDate ?: latestHero.releaseDate,
                        imdbRating = details?.imdbRating?.ifEmpty { latestHero.imdbRating } ?: latestHero.imdbRating,
                        tmdbRating = details?.tmdbRating?.ifEmpty { latestHero.tmdbRating } ?: latestHero.tmdbRating,
                        budget = details?.budget ?: latestHero.budget,
                        overview = resolvedOverview.ifBlank { latestHero.overview },
                        primaryNetworkLogo = primaryNetworkLogo ?: latestHero.primaryNetworkLogo
                    )
                    mediaRepository.cacheItem(updatedHero)
                    _uiState.value = _uiState.value.copy(
                        heroItem = updatedHero,
                        heroOverviewOverride = resolvedOverview.ifBlank { updatedHero.overview },
                        isHeroTransitioning = false
                    )
                }

                // Fetch trailer key for hero (YouTube)
                try {
                    val trailerKey = mediaRepository.getTrailerKey(item.mediaType, item.id)
                    if (trailerKey != null && _uiState.value.heroItem?.id == item.id) {
                        _uiState.value = _uiState.value.copy(heroTrailerKey = trailerKey)
                        prefetchTrailerUrl(trailerKey)
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {
            }
        }
    }

    private fun prefetchTrailerUrl(trailerKey: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                youTubeExtractor.extractPlaybackSource("https://www.youtube.com/watch?v=$trailerKey")
            }
        }
    }

    private fun scheduleHeroDetailsFetch(item: MediaItem, fastScrolling: Boolean) {
        heroDetailsJob?.cancel()

        // Fetch trailer for new hero item; skip if already loaded for this item (prevents restart mid-play)
        if (_uiState.value.trailerAutoPlay &&
            !(_uiState.value.heroItem?.id == item.id && _uiState.value.heroTrailerKey != null)
        ) {
            _uiState.value = _uiState.value.copy(heroTrailerKey = null)
            viewModelScope.launch(networkDispatcher) {
                try {
                    val trailerKey = mediaRepository.getTrailerKey(item.mediaType, item.id)
                    if (trailerKey != null && _uiState.value.heroItem?.id == item.id) {
                        _uiState.value = _uiState.value.copy(heroTrailerKey = trailerKey)
                        prefetchTrailerUrl(trailerKey)
                    }
                } catch (_: Exception) {}
            }
        }

        heroDetailsJob = viewModelScope.launch(networkDispatcher) {
            val detailsKey = "${item.mediaType}_${item.id}"
            val cachedDetails = heroDetailsCache[detailsKey]
            if (cachedDetails != null) {
                val currentHero = _uiState.value.heroItem
                if (currentHero?.id == item.id && currentHero.mediaType == item.mediaType) {
                    val updatedHero = currentHero.copy(
                        duration = cachedDetails.duration.ifEmpty { currentHero.duration },
                        releaseDate = cachedDetails.releaseDate ?: currentHero.releaseDate,
                        imdbRating = cachedDetails.imdbRating.ifEmpty { currentHero.imdbRating },
                        tmdbRating = cachedDetails.tmdbRating.ifEmpty { currentHero.tmdbRating },
                        budget = cachedDetails.budget ?: currentHero.budget,
                        overview = cachedDetails.overview.ifBlank { currentHero.overview },
                        primaryNetworkLogo = cachedDetails.primaryNetworkLogo ?: currentHero.primaryNetworkLogo
                    )
                    mediaRepository.cacheItem(updatedHero)
                    _uiState.value = _uiState.value.copy(
                        heroItem = updatedHero,
                        heroOverviewOverride = cachedDetails.overview.ifBlank { currentHero.overview },
                        isHeroTransitioning = false
                    )
                }
                return@launch
            }

            // Keep hero metadata (duration/budget/ratings) feeling immediate.
            // Use a tiny settle delay for normal navigation and a short delay
            // while fast-scrolling to avoid redundant network churn.
            val detailsDelayMs = if (fastScrolling) 220L else 60L
            if (detailsDelayMs > 0L) {
                delay(detailsDelayMs)
            }
            val currentHero = _uiState.value.heroItem
            if (currentHero?.id != item.id) return@launch

            try {
                val details = runCatching {
                    if (item.mediaType == MediaType.MOVIE) {
                        mediaRepository.getMovieDetails(item.id)
                    } else {
                        mediaRepository.getTvDetails(item.id)
                    }
                }.getOrNull()
                val primaryNetworkLogo = resolvePrimaryNetworkLogo(item.mediaType, item.id)
                val resolvedOverview = resolveBestOverview(
                    item = item,
                    candidateOverview = details?.overview?.ifBlank { currentHero.overview } ?: currentHero.overview
                )

                val updatedItem = currentHero.copy(
                    duration = details?.duration?.ifEmpty { currentHero.duration } ?: currentHero.duration,
                    releaseDate = details?.releaseDate ?: currentHero.releaseDate,
                    imdbRating = details?.imdbRating?.ifEmpty { currentHero.imdbRating } ?: currentHero.imdbRating,
                    tmdbRating = details?.tmdbRating?.ifEmpty { currentHero.tmdbRating } ?: currentHero.tmdbRating,
                    budget = details?.budget ?: currentHero.budget,
                    overview = resolvedOverview.ifBlank { currentHero.overview },
                    primaryNetworkLogo = primaryNetworkLogo ?: currentHero.primaryNetworkLogo
                )
                heroDetailsCache[detailsKey] = HeroDetailsSnapshot(
                    duration = details?.duration.orEmpty(),
                    releaseDate = details?.releaseDate,
                    imdbRating = details?.imdbRating.orEmpty(),
                    tmdbRating = details?.tmdbRating.orEmpty(),
                    budget = details?.budget,
                    overview = resolvedOverview,
                    primaryNetworkLogo = primaryNetworkLogo
                )
                mediaRepository.cacheItem(updatedItem)
                _uiState.value = _uiState.value.copy(
                    heroItem = updatedItem,
                    heroOverviewOverride = resolvedOverview.ifBlank { updatedItem.overview },
                    isHeroTransitioning = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isHeroTransitioning = false)
            }
        }
    }

    /**
     * Phase 1.3: Ahead-of-focus preloading
     * Call this when focus changes to preload nearby items
     */
    fun onFocusChanged(rowIndex: Int, itemIndex: Int, shouldPrefetch: Boolean = true) {
        currentRowIndex = rowIndex
        currentItemIndex = itemIndex
        lastFocusChangeTime = System.currentTimeMillis()
        if (!shouldPrefetch) {
            prefetchJob?.cancel()
            return
        }

        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(networkDispatcher) {
            delay(FOCUS_PREFETCH_COALESCE_MS)

            val categories = _uiState.value.categories
            if (rowIndex < 0 || rowIndex >= categories.size) return@launch

            val category = categories[rowIndex]

            if (category.items.isEmpty()) return@launch
            if (category.id.startsWith("collection_row_")) return@launch

            // Ensure focused card + next 4 cards get logo priority.
            val startIndex = itemIndex.coerceIn(0, category.items.lastIndex)
            val endIndex = minOf(itemIndex + 4, category.items.lastIndex)
            if (startIndex > endIndex) return@launch

            val focusWindowItems = (startIndex..endIndex)
                .mapNotNull { category.items.getOrNull(it) }
                .filter { isActionableMediaItem(it) }

            val itemsToLoad = focusWindowItems.filter { item ->
                val key = "${item.mediaType}_${item.id}"
                !hasCachedLogo(key) && logoFetchInFlight.add(key)
            }

            if (itemsToLoad.isNotEmpty()) {
                // Fetch logos for focused window
                val logoJobs = itemsToLoad.map { item ->
                    async(networkDispatcher) {
                        val key = "${item.mediaType}_${item.id}"
                        try {
                            val logoUrl = mediaRepository.getLogoUrl(item.mediaType, item.id)
                            if (logoUrl != null) key to logoUrl else null
                        } catch (e: Exception) {
                            null
                        } finally {
                            logoFetchInFlight.remove(key)
                        }
                    }
                }
                val newLogos = logoJobs.awaitAll().filterNotNull().toMap()

                if (newLogos.isNotEmpty()) {
                    if (putCachedLogos(newLogos)) {
                        scheduleLogoCachePublish(highPriority = true)
                    }
                    // Preload actual images
                    preloadLogoImages(newLogos.values.toList())
                }
            }

            // Keep extra backdrop decoding off the active DPAD path; focused
            // cards still request their own images while warmup waits for idle.
            val backdropUrls = focusWindowItems
                .take(incrementalBackdropPrefetchItems + 1)
                .mapNotNull { it.backdrop ?: it.image }
            scheduleIdleBackdropPreload(backdropUrls)
        }
    }

    /**
     * Phase 1.1: Preload logos for category + next 2 categories
     */
    fun preloadLogosForCategory(categoryIndex: Int, prioritizeVisible: Boolean = false) {
        if (prioritizeVisible) {
            preloadCategoryPriorityJob?.cancel()
        } else {
            preloadCategoryJobs.remove(categoryIndex)?.cancel()
        }
        val targetJob = viewModelScope.launch(networkDispatcher) {
            try {
                delay(
                    if (prioritizeVisible) {
                        if (isLowRamDevice) 60L else 30L
                    } else {
                        if (isLowRamDevice) 200L else 100L
                    }
                )
                val categories = _uiState.value.categories
                if (categoryIndex < 0 || categoryIndex >= categories.size) return@launch
                val category = categories[categoryIndex]
                if (category.id.startsWith("collection_row_")) return@launch
                val maxLogoItems = if (prioritizeVisible) prioritizedLogoPrefetchItems else incrementalLogoPrefetchItems

                val itemsToLoad = category.items.take(maxLogoItems).filter { item ->
                    if (!isActionableMediaItem(item)) return@filter false
                    if (isIptvItem(item)) return@filter false  // IPTV items use channel logo directly
                    val key = "${item.mediaType}_${item.id}"
                    !hasCachedLogo(key) && logoFetchInFlight.add(key)
                }

                if (itemsToLoad.isNotEmpty()) {
                    val logoJobs = itemsToLoad.map { item ->
                        async(networkDispatcher) {
                            val key = "${item.mediaType}_${item.id}"
                            try {
                                val logoUrl = mediaRepository.getLogoUrl(item.mediaType, item.id)
                                if (logoUrl != null) key to logoUrl else null
                            } catch (e: Exception) {
                                null
                            } finally {
                                logoFetchInFlight.remove(key)
                            }
                        }
                    }
                    val newLogos = logoJobs.awaitAll().filterNotNull().toMap()
                    if (newLogos.isNotEmpty()) {
                        if (putCachedLogos(newLogos)) {
                            scheduleLogoCachePublish(highPriority = prioritizeVisible)
                        }
                        // Preload actual images
                        preloadLogoImages(newLogos.values.toList())
                    }
                }

                val backdropItems = if (prioritizeVisible) {
                    incrementalBackdropPrefetchItems + 1
                } else {
                    incrementalBackdropPrefetchItems
                }
                val backdropUrls = category.items.take(backdropItems).mapNotNull { it.backdrop ?: it.image }
                scheduleIdleBackdropPreload(backdropUrls)
            } finally {
                if (!prioritizeVisible) {
                    preloadCategoryJobs.remove(categoryIndex)
                }
            }
        }
        if (prioritizeVisible) {
            preloadCategoryPriorityJob = targetJob
        } else {
            preloadCategoryJobs[categoryIndex] = targetJob
        }
    }

    /**
     * Clear hero transition state after animation completes
     */
    fun onHeroTransitionComplete() {
        _uiState.value = _uiState.value.copy(
            previousHeroItem = null,
            previousHeroLogoUrl = null,
            isHeroTransitioning = false
        )
    }

    fun toggleWatchlist(item: MediaItem) {
        viewModelScope.launch {
            try {
                val isInWatchlist = watchlistRepository.isInWatchlist(item.mediaType, item.id)
                val traktConnected = runCatching { traktRepository.hasTrakt() }.getOrDefault(false)
                if (isInWatchlist) {
                    if (traktConnected && !traktRepository.removeFromWatchlist(item.mediaType, item.id)) {
                        throw IllegalStateException("Failed to remove from Trakt watchlist")
                    }
                    watchlistRepository.removeFromWatchlist(item.mediaType, item.id)
                } else {
                    if (traktConnected && !traktRepository.addToWatchlist(item.mediaType, item.id)) {
                        throw IllegalStateException("Failed to add to Trakt watchlist")
                    }
                    watchlistRepository.addToWatchlist(item.mediaType, item.id, item)
                }
                runCatching { cloudSyncRepository.pushToCloud() }
                    .onFailure { error ->
                        AppLogger.recordException(
                            throwable = error,
                            context = mapOf(
                                "error_area" to "Watchlist",
                                "watchlist_phase" to "home_toggle_cloud_push",
                                "media_type" to item.mediaType.name.lowercase(),
                                "trakt_connected" to traktConnected.toString()
                            )
                        )
                    }
                _uiState.value = _uiState.value.copy(
                    toastMessage = if (isInWatchlist) "Removed from watchlist" else "Added to watchlist",
                    toastType = ToastType.SUCCESS
                )
            } catch (e: Exception) {
                AppLogger.recordException(
                    throwable = e,
                    context = mapOf(
                        "error_area" to "Watchlist",
                        "watchlist_phase" to "home_toggle",
                        "media_type" to item.mediaType.name.lowercase()
                    )
                )
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to update watchlist",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun toggleWatched(item: MediaItem) {
        viewModelScope.launch {
            try {
                if (item.mediaType == MediaType.MOVIE) {
                    if (item.isWatched) {
                        traktRepository.markMovieUnwatched(item.id)
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Marked as unwatched",
                            toastType = ToastType.SUCCESS
                        )
                    } else {
                        traktRepository.markMovieWatched(item.id)
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Marked as watched",
                            toastType = ToastType.SUCCESS
                        )
                    }
                } else {
                    val nextEp = item.nextEpisode
                    if (nextEp != null) {
                        // OPTIMISTIC UI UPDATE: Remove from CW and show toast immediately
                        val updatedCategories = _uiState.value.categories.map { category ->
                            if (category.id == "continue_watching") {
                                category.copy(items = category.items.filter { it.id != item.id })
                            } else {
                                category
                            }
                        }.filter { category ->
                            category.id != "continue_watching" || category.items.isNotEmpty()
                        }

                        _uiState.value = _uiState.value.copy(
                            categories = updatedCategories,
                            toastMessage = "S${nextEp.seasonNumber}E${nextEp.episodeNumber} marked as watched",
                            toastType = ToastType.SUCCESS
                        )

                        // Sync to backend after UI update (these may be slow for non-Trakt/non-Cloud profiles)
                        traktRepository.markEpisodeWatched(item.id, nextEp.seasonNumber, nextEp.episodeNumber)
                        watchHistoryRepository.removeFromHistory(item.id, nextEp.seasonNumber, nextEp.episodeNumber)

                        // Save the NEXT episode to CW (local + cloud) so it appears on all devices
                        try {
                            // Handle season boundaries: if this was the last episode of the season, move to next season
                            var followingSeason = nextEp.seasonNumber
                            var followingEpisode = nextEp.episodeNumber + 1
                            val seasonEpisodes = mediaRepository.getSeasonEpisodes(item.id, nextEp.seasonNumber)
                            if (seasonEpisodes != null && followingEpisode > seasonEpisodes.size) {
                                followingSeason = nextEp.seasonNumber + 1
                                followingEpisode = 1
                            }
                            traktRepository.saveLocalContinueWatching(
                                mediaType = MediaType.TV,
                                tmdbId = item.id,
                                title = item.title,
                                posterPath = item.image,
                                backdropPath = item.backdrop,
                                season = followingSeason,
                                episode = followingEpisode,
                                episodeTitle = null,
                                progress = 3,
                                positionSeconds = 0L,
                                durationSeconds = 1L,
                                year = item.year
                            )
                            watchHistoryRepository.saveProgress(
                                mediaType = MediaType.TV,
                                tmdbId = item.id,
                                title = item.title,
                                poster = item.image,
                                backdrop = item.backdrop,
                                season = followingSeason,
                                episode = followingEpisode,
                                episodeTitle = null,
                                progress = 0.01f,
                                duration = 0L,
                                position = 0L
                            )
                            lastContinueWatchingUpdateMs = 0L
                            refreshContinueWatchingOnly(force = true)
                        } catch (_: Exception) {}
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "No episode info available",
                            toastType = ToastType.ERROR
                        )
                    }
                }
                runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
                // Push cloud snapshot so other devices see the watched-status change
                // and the updated Continue Watching entry. Without this, the snapshot
                // (localCW, localWatchedMovies, localWatchedEpisodes, dismissedCW)
                // was never updated — only the Supabase watch_history table was.
                runCatching { cloudSyncRepository.pushToCloud() }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to update watched status",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun markWatched(item: MediaItem) {
        viewModelScope.launch {
            try {
                if (item.mediaType == MediaType.MOVIE) {
                    if (!item.isWatched) {
                        traktRepository.markMovieWatched(item.id)
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Marked as watched",
                            toastType = ToastType.SUCCESS
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Already watched",
                            toastType = ToastType.INFO
                        )
                    }
                } else {
                    val nextEp = item.nextEpisode
                    if (nextEp != null) {
                        // OPTIMISTIC UI UPDATE: Remove from CW and show toast immediately
                        val updatedCategories = _uiState.value.categories.map { category ->
                            if (category.id == "continue_watching") {
                                category.copy(items = category.items.filter { it.id != item.id })
                            } else {
                                category
                            }
                        }.filter { category ->
                            category.id != "continue_watching" || category.items.isNotEmpty()
                        }

                        _uiState.value = _uiState.value.copy(
                            categories = updatedCategories,
                            toastMessage = "S${nextEp.seasonNumber}E${nextEp.episodeNumber} marked as watched",
                            toastType = ToastType.SUCCESS
                        )

                        // Sync to backend after UI update (these may be slow for non-Trakt/non-Cloud profiles)
                        try {
                            traktRepository.markEpisodeWatched(item.id, nextEp.seasonNumber, nextEp.episodeNumber)
                        } catch (_: Exception) {}
                        try {
                            watchHistoryRepository.removeFromHistory(item.id, nextEp.seasonNumber, nextEp.episodeNumber)
                        } catch (_: Exception) {}

                        // Save the NEXT episode to CW (local + cloud) so it appears on all devices
                        try {
                            var followingSeason = nextEp.seasonNumber
                            var followingEpisode = nextEp.episodeNumber + 1
                            val seasonEps = mediaRepository.getSeasonEpisodes(item.id, nextEp.seasonNumber)
                            if (seasonEps != null && followingEpisode > seasonEps.size) {
                                followingSeason = nextEp.seasonNumber + 1
                                followingEpisode = 1
                            }
                            traktRepository.saveLocalContinueWatching(
                                mediaType = MediaType.TV,
                                tmdbId = item.id,
                                title = item.title,
                                posterPath = item.image,
                                backdropPath = item.backdrop,
                                season = followingSeason,
                                episode = followingEpisode,
                                episodeTitle = null,
                                progress = 1,
                                positionSeconds = 0L,
                                durationSeconds = 1L,
                                year = item.year
                            )
                            // Also save to Supabase for cross-device sync
                            watchHistoryRepository.saveProgress(
                                mediaType = MediaType.TV,
                                tmdbId = item.id,
                                title = item.title,
                                poster = item.image,
                                backdrop = item.backdrop,
                                season = followingSeason,
                                episode = followingEpisode,
                                episodeTitle = null,
                                progress = 0.01f,
                                duration = 0L,
                                position = 0L
                            )
                            // Reset throttle so refresh actually runs
                            lastContinueWatchingUpdateMs = 0L
                            refreshContinueWatchingOnly(force = true)
                        } catch (_: Exception) {}
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "No episode info available",
                            toastType = ToastType.ERROR
                        )
                    }
                }
                runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
                // Push cloud snapshot so other devices see watched status + CW update
                runCatching { cloudSyncRepository.pushToCloud() }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to update watched status",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    suspend fun isInWatchlist(item: MediaItem): Boolean {
        return watchlistRepository.isInWatchlist(item.mediaType, item.id)
    }

    fun removeFromContinueWatching(item: MediaItem) {
        viewModelScope.launch {
            try {
                val season = if (item.mediaType == MediaType.TV) item.nextEpisode?.seasonNumber else null
                val episode = if (item.mediaType == MediaType.TV) item.nextEpisode?.episodeNumber else null
                dismissedContinueWatchingKeys.add(continueWatchingKey(item.mediaType, item.id))

                watchHistoryRepository.removeFromHistory(item.id, season, episode)
                traktRepository.deletePlaybackForContent(item.id, item.mediaType)
                traktRepository.removeFromContinueWatchingCache(item.id, null, null)
                traktRepository.dismissContinueWatching(item)
                runCatching { cloudSyncRepository.pushToCloud() }

                val updatedCategories = _uiState.value.categories.map { category ->
                    if (category.id == "continue_watching") {
                        category.copy(items = category.items.filter { it.id != item.id })
                    } else {
                        category
                    }
                }.filter { category ->
                    category.id != "continue_watching" || category.items.isNotEmpty()
                }

                _uiState.value = _uiState.value.copy(
                    categories = updatedCategories,
                    toastMessage = "Removed from Continue Watching",
                    toastType = ToastType.SUCCESS
                )
                runCatching { launcherContinueWatchingRepository.refreshForCurrentProfile() }
                updatedCategories.firstOrNull { it.id == "continue_watching" }?.let { category ->
                    lastContinueWatchingItems = category.items
                    lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                } ?: run {
                    lastContinueWatchingItems = emptyList()
                    lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to remove from Continue Watching",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    // --- App Update Methods ---

    fun checkForAppUpdates(silent: Boolean = false) {
        if (!appUpdateRepository.supportsSelfUpdate()) return

        viewModelScope.launch {
            if (!silent) {
                updateStatusManager.updateStatus(com.arflix.tv.updater.UpdateStatus.Checking)
            }

            val result = appUpdateRepository.getLatestUpdate()
            result.onSuccess { update ->
                val localVer = appUpdateRepository.getInstalledVersionName()
                val isNewer = com.arflix.tv.updater.VersionUtils.isRemoteNewer(update.tag, localVer)

                if (isNewer) {
                    updateStatusManager.updateStatus(com.arflix.tv.updater.UpdateStatus.UpdateAvailable(update))
                } else {
                    if (!silent) {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "You already have the latest version",
                            toastType = ToastType.INFO
                        )
                    }
                    updateStatusManager.reset()
                }
            }.onFailure { error ->
                if (!silent) {
                    _uiState.value = _uiState.value.copy(
                        toastMessage = error.message ?: "Failed to check for updates",
                        toastType = ToastType.ERROR
                    )
                }
                updateStatusManager.reset()
            }
        }
    }

    private var downloadJob: kotlinx.coroutines.Job? = null

    fun downloadAppUpdate() {
        val currentStatus = updateStatusManager.status.value
        val update = when (currentStatus) {
            is com.arflix.tv.updater.UpdateStatus.UpdateAvailable -> currentStatus.update
            is com.arflix.tv.updater.UpdateStatus.Failure -> currentStatus.update
            else -> return
        } ?: return

        if (!appUpdateRepository.supportsSelfUpdate()) return

        downloadJob = viewModelScope.launch {
            updateStatusManager.updateStatus(com.arflix.tv.updater.UpdateStatus.Downloading(0f, update))

            val safeName = update.assetName.replace(FILE_NAME_REGEX, "_")
            val dest = java.io.File(java.io.File(context.cacheDir, "updates"), safeName)

            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                apkDownloader.download(update.assetUrl, dest) { downloaded, total ->
                    val progress = if (total != null && total > 0L) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else null

                    updateStatusManager.updateStatus(com.arflix.tv.updater.UpdateStatus.Downloading(progress, update))
                }
            }

            result.onSuccess { file ->
                updateStatusManager.updateStatus(com.arflix.tv.updater.UpdateStatus.ReadyToInstall(file.absolutePath, update))
                // Automatically prompt install once downloaded
                installAppUpdateOrRequestPermission()
            }.onFailure { error ->
                updateStatusManager.updateStatus(
                    com.arflix.tv.updater.UpdateStatus.Failure(error.message ?: "Download failed", update)
                )
            }
        }
    }

    fun cancelDownloadAppUpdate() {
        downloadJob?.cancel()
        downloadJob = null
        val currentStatus = updateStatusManager.status.value
        if (currentStatus is com.arflix.tv.updater.UpdateStatus.Downloading) {
            updateStatusManager.updateStatus(com.arflix.tv.updater.UpdateStatus.UpdateAvailable(currentStatus.update))
        }
    }

    fun installAppUpdateOrRequestPermission() {
        val currentStatus = updateStatusManager.status.value
        if (currentStatus !is com.arflix.tv.updater.UpdateStatus.ReadyToInstall && currentStatus !is com.arflix.tv.updater.UpdateStatus.Failure) return

        val apkPath = if (currentStatus is com.arflix.tv.updater.UpdateStatus.ReadyToInstall) currentStatus.apkPath else return
        val update = currentStatus.update
        val apkFile = java.io.File(apkPath)

        if (!apkFile.exists()) {
            updateStatusManager.updateStatus(com.arflix.tv.updater.UpdateStatus.Failure("Downloaded file is missing", update))
            return
        }

        if (!com.arflix.tv.updater.ApkInstaller.canRequestPackageInstalls(context)) {
            // If we can't request package installs, we should let the user know, but for now
            // launchInstall handles falling back to Intent.ACTION_VIEW
        }

        val conflictMsg = com.arflix.tv.updater.ApkInstaller.checkSignatureConflict(context, apkFile)
        if (conflictMsg != null) {
            updateStatusManager.updateStatus(com.arflix.tv.updater.UpdateStatus.Failure(conflictMsg, update))
            return
        }

        com.arflix.tv.updater.ApkInstaller.launchInstall(context, apkFile)
        updateStatusManager.updateStatus(com.arflix.tv.updater.UpdateStatus.Installing(update))

        // Mark this release as ignored so it doesn't pop up again if the user cancels the install
        viewModelScope.launch {
            updatePreferences.setIgnoredTag(update.tag)
        }
    }

    fun dismissAppUpdateDialog() {
        _uiState.value = _uiState.value.copy(showAppUpdateDialog = false)
        // We do not reset updateStatusManager here, so the badge remains active
    }

    fun ignoreAppUpdate() {
        val currentStatus = updateStatusManager.status.value
        if (currentStatus is com.arflix.tv.updater.UpdateStatus.UpdateAvailable) {
            updateStatusManager.sessionIgnoredTag = currentStatus.update.tag
            viewModelScope.launch {
                updatePreferences.setIgnoredTag(currentStatus.update.tag)
            }
        }
        _uiState.value = _uiState.value.copy(showAppUpdateDialog = false, hasUpdateBadge = false)
        updateStatusManager.reset()
    }
}
