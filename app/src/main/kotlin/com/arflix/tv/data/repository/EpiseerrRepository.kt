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
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class EpiseerrPendingItem(
    val id: String,
    val seriesId: Int?,
    val title: String,
    val tmdbId: String?,
    val tvdbId: String?,
    val poster: String?,
)

data class EpiseerrRule(
    val name: String,
    val displayName: String,
    val seriesCount: Int,
)

// Latest watch event per series from Episeerr's 7-day rolling activity log — cross-service
// (fed by Tautulli/Jellyfin webhooks into Episeerr, not Plex's own per-item counters), so
// this reflects real watch progress regardless of which server actually played it. No Plex
// ratingKey here (Episeerr only knows Sonarr's world) — matched by title against the live
// Plex library on the client side. See PlexLibraryScreen.kt.
data class EpiseerrRecentlyWatched(
    val seriesTitle: String,
    val season: Int?,
    val episode: Int?,
    val timestamp: Long,
    val backdropUrl: String?,
)

@Singleton
class EpiseerrRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val http get() = OkHttpProvider.client
    private val tag = "EpiseerrRepository"

    // All Episeerr calls go through xadarr-server's proxy endpoints.
    // Only SYNC_SERVER_URL_KEY is needed — no separate Episeerr URL in the app.
    private suspend fun syncBase(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SYNC_SERVER_URL_KEY]?.trimEnd('/').orEmpty()
    }

    suspend fun isConfigured(): Boolean = syncBase().isNotBlank()

    suspend fun getPendingItems(): List<EpiseerrPendingItem> = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext emptyList() }
        try {
            val req = Request.Builder().url("$base/api/episeerr/pending").get().build()
            val body = http.newCall(req).execute().use { it.body?.string() ?: "[]" }
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                EpiseerrPendingItem(
                    id       = obj.optString("id"),
                    seriesId = obj.optInt("seriesId").takeIf { it != 0 },
                    title    = obj.optString("title"),
                    tmdbId   = obj.optString("tmdbId").ifBlank { null },
                    tvdbId   = obj.optString("tvdbId").ifBlank { null },
                    poster   = obj.optString("poster").ifBlank { null },
                )
            }
        } catch (e: Exception) {
            Log.d(tag, "getPendingItems failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getRules(): List<EpiseerrRule> = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext emptyList() }
        try {
            val req = Request.Builder().url("$base/api/episeerr/rules").get().build()
            val body = http.newCall(req).execute().use { it.body?.string() ?: "{}" }
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("rules") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                EpiseerrRule(
                    name        = r.optString("name"),
                    displayName = r.optString("display_name").ifBlank { r.optString("name") },
                    seriesCount = r.optInt("series_count", 0),
                )
            }
        } catch (e: Exception) {
            Log.d(tag, "getRules failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun assignRule(tmdbId: String, ruleName: String): Boolean = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext false }
        try {
            val payload = JSONObject().apply {
                put("tmdb_id", tmdbId)
                put("rule_name", ruleName)
            }.toString()
            val body = payload.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$base/api/episeerr/assign")
                .post(body)
                .build()
            val resp = http.newCall(req).execute()
            val respBody = resp.use { it.body?.string() ?: "{}" }
            JSONObject(respBody).optBoolean("success", false)
        } catch (e: Exception) {
            Log.d(tag, "assignRule failed: ${e.message}")
            false
        }
    }

    // Direct assign for an already-tracked series (library browser) — unlike
    // assignRule(), this does not require the series to be in the Episeerr
    // pending-request queue.
    suspend fun assignRuleToSeries(seriesId: Int, ruleName: String): Boolean = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext false }
        try {
            val payload = JSONObject().apply {
                put("series_id", seriesId)
                put("rule_name", ruleName)
            }.toString()
            val body = payload.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$base/api/episeerr/assign-series")
                .post(body)
                .build()
            val resp = http.newCall(req).execute()
            val respBody = resp.use { it.body?.string() ?: "{}" }
            JSONObject(respBody).optBoolean("success", false)
        } catch (e: Exception) {
            Log.d(tag, "assignRuleToSeries failed: ${e.message}")
            false
        }
    }

    suspend fun getRecentlyWatched(): List<EpiseerrRecentlyWatched> = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext emptyList() }
        try {
            val req = Request.Builder().url("$base/api/episeerr/recently-watched").get().build()
            val body = http.newCall(req).execute().use { it.body?.string() ?: "[]" }
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                EpiseerrRecentlyWatched(
                    seriesTitle = obj.optString("seriesTitle"),
                    season      = obj.optInt("season").takeIf { obj.has("season") && !obj.isNull("season") },
                    episode     = obj.optInt("episode").takeIf { obj.has("episode") && !obj.isNull("episode") },
                    timestamp   = obj.optLong("timestamp"),
                    backdropUrl = obj.optString("backdropUrl").ifBlank { null },
                )
            }
        } catch (e: Exception) {
            Log.d(tag, "getRecentlyWatched failed: ${e.message}")
            emptyList()
        }
    }

    // Episeerr's own dashboard "Quick Links" (settings_db.quick_links) — Sonarr/Radarr/
    // Prowlarr/Dispatcharr/etc. shortcuts Joe already maintains there. Reused as the source
    // for Xadarr's mobile Bookmarks instead of asking him to re-enter the same URLs.
    suspend fun getQuickLinks(): List<Bookmark> = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext emptyList() }
        try {
            val req = Request.Builder().url("$base/api/episeerr/quick-links").get().build()
            val body = http.newCall(req).execute().use { it.body?.string() ?: "{}" }
            val arr = JSONObject(body).optJSONArray("links") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val link = arr.getJSONObject(i)
                val name = link.optString("name").trim()
                val url = link.optString("url").trim()
                // Older Episeerr rows store a Font Awesome class (e.g. "fas fa-link") here
                // instead of an image URL — only usable as a Coil image source when it's
                // actually a URL, so anything else falls back to the text-initial tile.
                val icon = link.optString("icon").trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
                if (name.isBlank() || url.isBlank()) null else Bookmark(name, url, icon)
            }
        } catch (e: Exception) {
            Log.d(tag, "getQuickLinks failed: ${e.message}")
            emptyList()
        }
    }

    /** Returns recent episeerr activity events from xadarr-server history for toast notifications. */
    suspend fun getRecentEpiseerrEvents(sinceTimestamp: String?): List<JSONObject> = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext emptyList() }
        try {
            val req = Request.Builder()
                .url("$base/api/media/history?limit=20")
                .get().build()
            val body = http.newCall(req).execute().use { it.body?.string() ?: "[]" }
            val arr = JSONArray(body)
            val result = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                if (entry.optString("source") == "episeerr") {
                    if (sinceTimestamp == null || entry.optString("timestamp") > sinceTimestamp) {
                        result.add(entry)
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.d(tag, "getRecentEpiseerrEvents failed: ${e.message}")
            emptyList()
        }
    }
}
