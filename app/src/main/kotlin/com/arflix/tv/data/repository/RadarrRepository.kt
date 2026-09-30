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

// Full-library summary — one row per Radarr movie regardless of file
// presence. Used by the "All Movies" library browser. Radarr already returns
// tmdbId natively, so no external-id resolution is needed (unlike Sonarr).
data class RadarrMovieSummary(
    val movieId: Int,
    val title: String,
    val tmdbId: Int?,
    val year: Int?,
    val status: String,
    val monitored: Boolean,
    val hasFile: Boolean,
    val poster: String?,
    val assignedRule: String?,
)

@Singleton
// Xadarr's two synthetic movie guide channels (Episeerr /api/radarr/guide-schedule).
// "Watch Now" slots carry real start/end times (a linear back-to-back schedule shared by
// every device); "Premiering" items only have a release date.
data class MovieGuideSlot(
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val overview: String,
    val startMs: Long,
    val endMs: Long,
    val fanart: String? = null,
)

data class MoviePremiere(
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val overview: String,
    val releaseDate: String,  // yyyy-MM-dd, may be blank
    val fanart: String? = null,
)

data class MovieGuide(
    val watchNow: List<MovieGuideSlot> = emptyList(),
    val premiering: List<MoviePremiere> = emptyList(),
)

class RadarrRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val http get() = OkHttpProvider.client
    private val tag = "RadarrRepository"

    private suspend fun syncBase(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SYNC_SERVER_URL_KEY]?.trimEnd('/').orEmpty()
    }

    suspend fun isConfigured(): Boolean = syncBase().isNotBlank()

    suspend fun getMovieGuide(forceRefresh: Boolean = false): MovieGuide = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext MovieGuide() }
        try {
            val query = if (forceRefresh) "?refresh=1" else ""
            val req = Request.Builder().url("$base/api/radarr/guide-schedule$query").get().build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext MovieGuide()
                resp.body?.string() ?: "{}"
            }
            val root = JSONObject(body)
            fun JSONObject.yearOrNull() = optInt("year", 0).takeIf { it > 0 }
            val slots = root.optJSONArray("watchNow")
            val prem = root.optJSONArray("premiering")
            MovieGuide(
                watchNow = buildList {
                    for (i in 0 until (slots?.length() ?: 0)) {
                        val m = slots!!.optJSONObject(i) ?: continue
                        add(MovieGuideSlot(
                            tmdbId = m.optInt("tmdbId"),
                            title = m.optString("title"),
                            year = m.yearOrNull(),
                            overview = m.optString("overview"),
                            startMs = m.optLong("startMs"),
                            endMs = m.optLong("endMs"),
                            fanart = m.optString("fanart").takeIf { it.isNotBlank() },
                        ))
                    }
                },
                premiering = buildList {
                    for (i in 0 until (prem?.length() ?: 0)) {
                        val m = prem!!.optJSONObject(i) ?: continue
                        add(MoviePremiere(
                            tmdbId = m.optInt("tmdbId"),
                            title = m.optString("title"),
                            year = m.yearOrNull(),
                            overview = m.optString("overview"),
                            releaseDate = m.optString("releaseDate"),
                            fanart = m.optString("fanart").takeIf { it.isNotBlank() },
                        ))
                    }
                },
            )
        } catch (e: Exception) {
            Log.d(tag, "getMovieGuide failed: ${e.message}")
            MovieGuide()
        }
    }

    suspend fun getAllMovies(): List<RadarrMovieSummary> = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext emptyList() }
        try {
            val req = Request.Builder().url("$base/api/radarr/movies").get().build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string() ?: "{}"
            }
            val root = JSONObject(body)
            if (!root.optBoolean("success", false)) return@withContext emptyList()
            val arr = root.optJSONArray("movies") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    add(
                        RadarrMovieSummary(
                            movieId = m.optInt("id"),
                            title = m.optString("title"),
                            tmdbId = m.optInt("tmdbId", -1).takeIf { it > 0 },
                            year = m.optInt("year", -1).takeIf { it > 0 },
                            status = m.optString("status"),
                            monitored = m.optBoolean("monitored", false),
                            hasFile = m.optBoolean("hasFile", false),
                            poster = m.optString("poster").takeIf { it.isNotBlank() },
                            assignedRule = m.optString("assigned_rule").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "getAllMovies failed: ${e.message}")
            emptyList()
        }
    }


    suspend fun deleteMovie(movieId: Int): Boolean = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext false }
        try {
            val req = Request.Builder()
                .url("$base/api/radarr/movie/$movieId")
                .delete()
                .build()
            val respBody = http.newCall(req).execute().use { it.body?.string() ?: "{}" }
            JSONObject(respBody).optBoolean("success", false)
        } catch (e: Exception) {
            Log.d(tag, "deleteMovie failed: ${e.message}")
            false
        }
    }

    suspend fun assignRuleToMovie(movieId: Int, ruleName: String): Boolean = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext false }
        try {
            val payload = JSONObject().apply {
                put("movie_id", movieId)
                put("rule_name", ruleName)
            }.toString()
            val body = payload.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$base/api/movie-rules/assign")
                .post(body)
                .build()
            val respBody = http.newCall(req).execute().use { it.body?.string() ?: "{}" }
            JSONObject(respBody).optBoolean("success", false)
        } catch (e: Exception) {
            Log.d(tag, "assignRuleToMovie failed: ${e.message}")
            false
        }
    }
}
