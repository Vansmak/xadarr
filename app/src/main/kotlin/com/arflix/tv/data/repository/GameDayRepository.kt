package com.arflix.tv.data.repository

import android.content.Context
import android.util.Log
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class GameDayEvent(
    val key: String,
    val league: String,
    val matchup: String,
    val teams: List<String>,
    val startTimeUtc: String?,
    val state: String,
    val channelNumber: Double?,
    val channelName: String?,
)

/**
 * Reads Joe's episeerr_custom-only "Game-Day Events" integration
 * (integrations/events.py) — a watchlist team/UFC broadcast, auto-resolved
 * to a Dispatcharr channel where possible. Not part of upstream Episeerr or
 * xadarr-server, so this hits the sync server directly at the same flat
 * `/api/integration/events/today` path events.py registers, reusing the
 * already-configured SYNC_SERVER_URL_KEY (same as EpiseerrRepository).
 *
 * On any sync server without this integration (xadarr-server, community
 * Episeerr, or none configured) the request 404s or fails outright and this
 * silently returns an empty list — same silent-disable convention as every
 * other Episeerr-gated feature.
 */
@Singleton
class GameDayRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val http get() = OkHttpProvider.client
    private val tag = "GameDayRepository"

    private suspend fun syncBase(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SYNC_SERVER_URL_KEY]?.trimEnd('/').orEmpty()
    }

    suspend fun getTodayEvents(): List<GameDayEvent> = withContext(Dispatchers.IO) {
        val base = syncBase().ifBlank { return@withContext emptyList() }
        try {
            val req = Request.Builder().url("$base/api/integration/events/today").get().build()
            val body = http.newCall(req).execute().use { it.body?.string() ?: "{}" }
            val arr = JSONObject(body).optJSONArray("events") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val teamsArr = o.optJSONArray("teams")
                GameDayEvent(
                    key = o.optString("key"),
                    league = o.optString("league"),
                    matchup = o.optString("matchup"),
                    teams = teamsArr?.let { t -> (0 until t.length()).map { idx -> t.getString(idx) } } ?: emptyList(),
                    startTimeUtc = o.optString("start_time").ifBlank { null },
                    state = o.optString("state"),
                    channelNumber = if (o.isNull("channel_number")) null else o.optDouble("channel_number").takeIf { !it.isNaN() },
                    channelName = o.optString("channel_name").ifBlank { null },
                )
            }
        } catch (e: Exception) {
            Log.d(tag, "getTodayEvents failed: ${e.message}")
            emptyList()
        }
    }
}
