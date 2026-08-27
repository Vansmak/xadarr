package com.arflix.tv.data.repository

import com.arflix.tv.data.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

enum class ActivityCategory { PREMIERING, DOWNLOADED, WATCHED, GAME_DAY }

data class ActivityFeedItem(
    val id: String,
    val category: ActivityCategory,
    val title: String,
    val subtitle: String,
    val timestampMs: Long,
    val imageUrl: String?,
    val mediaType: MediaType?,
    val resolvedTmdbId: Int?,
)

data class ActivityFeedSnapshot(
    val premiering: List<ActivityFeedItem>,
    val downloaded: List<ActivityFeedItem>,
    val watched: List<ActivityFeedItem>,
    val gameDay: List<ActivityFeedItem>,
    val generatedAtMs: Long,
)

private const val PREMIERING_WINDOW_DAYS = 14
// "Just happened" framing for a glance-widget — tighter than the in-app Home tab's 21-day
// New Episode window, which is tuned for "haven't caught up in a while," not "recent activity."
private const val DOWNLOADED_WINDOW_MS = 4L * 24 * 60 * 60 * 1000

/**
 * Aggregates "what's happening in the media stack" across Sonarr/Episeerr/Game-Day for the
 * home-screen widget (see widget/WidgetRefreshWorker.kt) — pure data, no Glance/WorkManager
 * awareness, so it's independently reusable (e.g. an in-app Activity screen later).
 *
 * "Downloaded" deliberately does NOT use EpiseerrRepository.getRecentEpiseerrEvents() —
 * that hits /api/media/history, which only exists on xadarr-server's own local event log
 * (see xadarr-server/server.py's get_history()); Episeerr itself has no equivalent endpoint
 * (its own /api/recent-activity is an unfinished stub — confirmed live, always returns empty
 * arrays). SonarrRepository.getAllSeries()'s lastEpisodeAddedEpochMs is the same signal
 * already powering the in-app Home tab's "New Episode" badge, confirmed working against
 * Joe's live Episeerr-as-sync-server setup, so it's reused here instead.
 */
@Singleton
class ActivityFeedRepository @Inject constructor(
    private val sonarrRepository: SonarrRepository,
    private val episeerrRepository: EpiseerrRepository,
    private val gameDayRepository: GameDayRepository,
    private val mediaRepository: MediaRepository,
) {
    suspend fun getSnapshot(limitPerCategory: Int = 3): ActivityFeedSnapshot = coroutineScope {
        val premiering = async { loadPremiering(limitPerCategory) }
        val downloaded = async { loadDownloaded(limitPerCategory) }
        val watched = async { loadWatched(limitPerCategory) }
        val gameDay = async { loadGameDay() }
        ActivityFeedSnapshot(
            premiering = premiering.await(),
            downloaded = downloaded.await(),
            watched = watched.await(),
            gameDay = gameDay.await(),
            generatedAtMs = System.currentTimeMillis(),
        )
    }

    private suspend fun loadPremiering(limit: Int): List<ActivityFeedItem> {
        val calendar = runCatching { sonarrRepository.getCalendar(daysAhead = PREMIERING_WINDOW_DAYS) }
            .getOrDefault(emptyList())
        val nearest = calendar
            .groupBy { it.seriesId }
            .mapNotNull { (_, entries) -> entries.minByOrNull { it.airDate } }
            .sortedBy { it.airDate }
            .take(limit)
        return nearest.map { e ->
            val tmdbId = runCatching { mediaRepository.resolveTmdbId(e.title, null, MediaType.TV) }.getOrNull()
            ActivityFeedItem(
                id = "premiering:${e.seriesId}:${e.season}:${e.episode}",
                category = ActivityCategory.PREMIERING,
                title = e.title,
                subtitle = "S${e.season} · E${e.episode} · ${formatRelativeDate(e.airDate)}",
                timestampMs = parseIsoToMillis(e.airDate) ?: 0L,
                imageUrl = e.poster,
                mediaType = MediaType.TV,
                resolvedTmdbId = tmdbId,
            )
        }
    }

    private suspend fun loadDownloaded(limit: Int): List<ActivityFeedItem> {
        val cutoff = System.currentTimeMillis() - DOWNLOADED_WINDOW_MS
        val allSeries = runCatching { sonarrRepository.getAllSeries() }.getOrDefault(emptyList())
        val recent = allSeries
            .filter { (it.lastEpisodeAddedEpochMs ?: 0L) >= cutoff }
            .sortedByDescending { it.lastEpisodeAddedEpochMs }
            .take(limit)
        return recent.map { s ->
            val tmdbId = runCatching { mediaRepository.resolveTmdbId(s.title, s.year, MediaType.TV) }.getOrNull()
            val ts = s.lastEpisodeAddedEpochMs ?: 0L
            ActivityFeedItem(
                id = "downloaded:${s.seriesId}:$ts",
                category = ActivityCategory.DOWNLOADED,
                title = s.title,
                subtitle = "New episode · ${formatRelativeTimeAgo(ts)}",
                timestampMs = ts,
                imageUrl = s.poster,
                mediaType = MediaType.TV,
                resolvedTmdbId = tmdbId,
            )
        }
    }

    private suspend fun loadWatched(limit: Int): List<ActivityFeedItem> {
        val recentlyWatched = runCatching { episeerrRepository.getRecentlyWatched() }.getOrDefault(emptyList())
        return recentlyWatched.take(limit).map { w ->
            val tmdbId = runCatching { mediaRepository.resolveTmdbId(w.seriesTitle, null, MediaType.TV) }.getOrNull()
            val ts = w.timestamp * 1000L
            val episodeLabel = listOfNotNull(w.season?.let { "S$it" }, w.episode?.let { "E$it" }).joinToString(" · ")
            ActivityFeedItem(
                id = "watched:${w.seriesTitle}:${w.season}:${w.episode}",
                category = ActivityCategory.WATCHED,
                title = w.seriesTitle,
                subtitle = listOf(episodeLabel, formatRelativeTimeAgo(ts)).filter { it.isNotBlank() }.joinToString(" · "),
                timestampMs = ts,
                imageUrl = w.backdropUrl,
                mediaType = MediaType.TV,
                resolvedTmdbId = tmdbId,
            )
        }
    }

    private suspend fun loadGameDay(): List<ActivityFeedItem> {
        val events = runCatching { gameDayRepository.getTodayEvents() }.getOrDefault(emptyList())
        // Live/upcoming games first, finished ones last — a glance-widget cares most about
        // "what can I watch right now."
        return events
            .sortedWith(compareBy({ it.state == "post" }, { it.startTimeUtc.orEmpty() }))
            .map { g ->
                ActivityFeedItem(
                    id = "game:${g.key}",
                    category = ActivityCategory.GAME_DAY,
                    title = g.matchup,
                    subtitle = listOfNotNull(
                        g.league.uppercase(),
                        g.channelName,
                        if (g.state == "post") "Final" else formatGameTime(g.startTimeUtc),
                    ).joinToString(" · "),
                    timestampMs = parseIsoToMillis(g.startTimeUtc.orEmpty()) ?: 0L,
                    imageUrl = null,
                    mediaType = null,
                    resolvedTmdbId = null,
                )
            }
    }
}

private fun parseIsoToMillis(iso: String): Long? = runCatching {
    java.time.Instant.parse(if (iso.endsWith("Z") || iso.contains("+")) iso else "${iso}Z").toEpochMilli()
}.getOrNull()

private fun formatRelativeDate(iso: String): String = runCatching {
    val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
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
        in 2..6 -> java.text.SimpleDateFormat("EEEE", java.util.Locale.US).format(date)
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(date)
    }
}.getOrDefault(iso)

private fun formatRelativeTimeAgo(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val diffMs = System.currentTimeMillis() - epochMs
    val minutes = diffMs / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}

private fun formatGameTime(startTimeUtc: String?): String = runCatching {
    if (startTimeUtc.isNullOrBlank()) return "TBD"
    val instant = java.time.Instant.parse(startTimeUtc)
    val local = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
    java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US).format(local)
}.getOrDefault("TBD")
