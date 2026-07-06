package com.arflix.tv.data.repository

import android.content.Context
import android.util.Log
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class SonarrEpisodeStatus {
    AVAILABLE,     // has file on disk — ready to play
    QUEUED,        // downloading right now
    MISSING,       // monitored, aired, no file — can trigger search
    MONITORED,     // monitored, hasn't aired yet
    UNMONITORED,   // Sonarr knows it but isn't tracking it
}

data class SonarrEpisodeInfo(
    val status: SonarrEpisodeStatus,
    val downloadProgress: Float = 0f,  // 0-100 when QUEUED
)

// Full-library summary — unlike SonarrEpisodeInfo (per-episode, for an already
// TMDB-resolved show), this is one row per Sonarr series regardless of whether
// it has any files or a TMDB match yet. Used by the "All Shows" library browser.
// One upcoming episode from Sonarr's calendar - backs the Home "Upcoming" row.
data class SonarrCalendarEntry(
    val seriesId: Int,
    val tvdbId: Int?,
    val title: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String,
    val airDate: String,     // ISO 8601
    val poster: String?,     // full remote URL, ready to use as-is
)

data class SonarrSeriesSummary(
    val seriesId: Int,
    val title: String,
    val tvdbId: Int?,
    val year: Int?,
    val status: String,           // "continuing" / "ended" / "upcoming"
    val monitored: Boolean,
    val episodeFileCount: Int,
    val totalEpisodeCount: Int,
    val poster: String?,
    val assignedRule: String?,
)

@Singleton
class SonarrRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val http get() = OkHttpProvider.client
    private val tag = "SonarrRepository"

    private suspend fun syncBase(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SYNC_SERVER_URL_KEY]?.trimEnd('/').orEmpty()
    }

    suspend fun isConfigured(): Boolean = syncBase().isNotBlank()

    suspend fun getEpisodeStatuses(
        tvdbId: String,
        season: Int,
    ): Map<Int, SonarrEpisodeInfo> = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext emptyMap() }
        try {
            val url = "$base/api/sonarr/series-status?tvdbId=$tvdbId&season=$season"
            val req = Request.Builder().url(url).get().build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyMap()
                resp.body?.string() ?: "{}"
            }
            val root = JSONObject(body)
            val episodes = root.optJSONObject("episodes") ?: return@withContext emptyMap()
            buildMap {
                episodes.keys().forEach { key ->
                    val epNum = key.toIntOrNull() ?: return@forEach
                    val ep = episodes.optJSONObject(key) ?: return@forEach
                    val status = when (ep.optString("status")) {
                        "available"   -> SonarrEpisodeStatus.AVAILABLE
                        "queued"      -> SonarrEpisodeStatus.QUEUED
                        "missing"     -> SonarrEpisodeStatus.MISSING
                        "monitored"   -> SonarrEpisodeStatus.MONITORED
                        "unmonitored" -> SonarrEpisodeStatus.UNMONITORED
                        else          -> return@forEach
                    }
                    val progress = ep.optDouble("progress", 0.0).toFloat()
                    put(epNum, SonarrEpisodeInfo(status, progress))
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "getEpisodeStatuses failed: ${e.message}")
            emptyMap()
        }
    }

    suspend fun triggerEpisodeSearch(
        tvdbId: String,
        season: Int,
        episode: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext false }
        try {
            val payload = JSONObject().apply {
                put("tvdbId", tvdbId)
                put("season", season)
                put("episode", episode)
            }.toString()
            val body = payload.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$base/api/sonarr/episode-search")
                .post(body)
                .build()
            val resp = http.newCall(req).execute()
            val respBody = resp.use { it.body?.string() ?: "{}" }
            JSONObject(respBody).optBoolean("success", false)
        } catch (e: Exception) {
            Log.d(tag, "triggerEpisodeSearch failed: ${e.message}")
            false
        }
    }

    suspend fun deleteEpisodeFile(
        tvdbId: String,
        season: Int,
        episode: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext false }
        try {
            val payload = JSONObject().apply {
                put("tvdbId", tvdbId)
                put("season", season)
                put("episode", episode)
            }.toString()
            val body = payload.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$base/api/sonarr/episode-delete")
                .post(body)
                .build()
            val resp = http.newCall(req).execute()
            val respBody = resp.use { it.body?.string() ?: "{}" }
            JSONObject(respBody).optBoolean("success", false)
        } catch (e: Exception) {
            Log.d(tag, "deleteEpisodeFile failed: ${e.message}")
            false
        }
    }

    // Full, unfiltered Sonarr series list — regardless of file presence or
    // ended/continuing status. Backs the "All Shows" library browser.
    suspend fun getAllSeries(): List<SonarrSeriesSummary> = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext emptyList() }
        try {
            val req = Request.Builder().url("$base/api/sonarr/series").get().build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string() ?: "{}"
            }
            val root = JSONObject(body)
            if (!root.optBoolean("success", false)) return@withContext emptyList()
            val arr = root.optJSONArray("series") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    add(
                        SonarrSeriesSummary(
                            seriesId = s.optInt("id"),
                            title = s.optString("title"),
                            tvdbId = s.optInt("tvdbId", -1).takeIf { it > 0 },
                            year = s.optInt("year", -1).takeIf { it > 0 },
                            status = s.optString("status"),
                            monitored = s.optBoolean("monitored", false),
                            episodeFileCount = s.optInt("episodeFileCount", 0),
                            totalEpisodeCount = s.optInt("totalEpisodeCount", 0),
                            poster = s.optString("poster").takeIf { it.isNotBlank() },
                            assignedRule = s.optString("assigned_rule").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "getAllSeries failed: ${e.message}")
            emptyList()
        }
    }


    suspend fun getCalendar(daysAhead: Int = 180): List<SonarrCalendarEntry> = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext emptyList() }
        try {
            val url = "$base/api/sonarr/calendar?days=$daysAhead"
            val req = Request.Builder().url(url).get().build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string() ?: "{}"
            }
            val arr = JSONObject(body).optJSONArray("episodes") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    add(
                        SonarrCalendarEntry(
                            seriesId = e.optInt("seriesId"),
                            tvdbId = e.optInt("tvdbId", -1).takeIf { it > 0 },
                            title = e.optString("title"),
                            season = e.optInt("season"),
                            episode = e.optInt("episode"),
                            episodeTitle = e.optString("episodeTitle"),
                            airDate = e.optString("airDate"),
                            poster = e.optString("poster").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "getCalendar failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteSeries(seriesId: Int): Boolean = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext false }
        try {
            val req = Request.Builder()
                .url("$base/api/sonarr/series/$seriesId")
                .delete()
                .build()
            val respBody = http.newCall(req).execute().use { it.body?.string() ?: "{}" }
            JSONObject(respBody).optBoolean("success", false)
        } catch (e: Exception) {
            Log.d(tag, "deleteSeries failed: ${e.message}")
            false
        }
    }
}
