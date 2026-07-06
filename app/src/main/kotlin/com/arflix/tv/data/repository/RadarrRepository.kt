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
