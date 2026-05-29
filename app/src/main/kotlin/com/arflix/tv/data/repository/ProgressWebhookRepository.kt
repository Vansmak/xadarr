package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

val WEBHOOK_ENABLED_KEY = booleanPreferencesKey("webhook_enabled")
val WEBHOOK_URL_KEY = stringPreferencesKey("webhook_url")       // legacy single-URL key
val WEBHOOK_URLS_KEY = stringPreferencesKey("webhook_urls")     // JSON array of WebhookUrlConfig objects
val WEBHOOK_INTERVAL_KEY = stringPreferencesKey("webhook_interval_seconds")
val WEBHOOK_COMPLETION_PERCENT_KEY = stringPreferencesKey("webhook_completion_percent")
val WATCHLIST_API_ENABLED_KEY = booleanPreferencesKey("watchlist_api_enabled")
val WATCHLIST_API_PORT_KEY = stringPreferencesKey("watchlist_api_port")
val SYNC_SERVER_URL_KEY = stringPreferencesKey("sync_server_url")
val USER_TMDB_API_KEY = stringPreferencesKey("user_tmdb_api_key")
val USER_TRAKT_CLIENT_ID = stringPreferencesKey("user_trakt_client_id")
val USER_TRAKT_CLIENT_SECRET = stringPreferencesKey("user_trakt_client_secret")

val ALL_WEBHOOK_EVENTS: Set<String> = linkedSetOf(
    "start", "pause", "resume", "stop", "progress", "watchlist.add", "watchlist.remove"
)

data class WebhookUrlConfig(
    val url: String,
    val events: Set<String> = ALL_WEBHOOK_EVENTS,
)

fun parseWebhookUrlConfigs(jsonString: String): List<WebhookUrlConfig> {
    if (jsonString.isBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(jsonString)
        (0 until arr.length()).mapNotNull { i ->
            when (val el = arr.get(i)) {
                is String -> el.trim().takeIf { it.isNotBlank() }?.let { WebhookUrlConfig(it, ALL_WEBHOOK_EVENTS) }
                is org.json.JSONObject -> {
                    val url = el.optString("url", "").trim()
                    if (url.isBlank()) null
                    else {
                        val evArr = el.optJSONArray("events")
                        val events = if (evArr != null) {
                            (0 until evArr.length())
                                .mapNotNull { j -> evArr.optString(j).trim().takeIf { s -> s.isNotBlank() } }
                                .toSet()
                        } else ALL_WEBHOOK_EVENTS
                        WebhookUrlConfig(url, events)
                    }
                }
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}

fun serializeWebhookUrlConfigs(configs: List<WebhookUrlConfig>): String {
    val arr = org.json.JSONArray()
    configs.forEach { c ->
        arr.put(org.json.JSONObject().apply {
            put("url", c.url)
            put("events", org.json.JSONArray(c.events.toList()))
        })
    }
    return arr.toString()
}

@Singleton
class ProgressWebhookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private var lastFireTimeMs = 0L

    private suspend fun resolveWebhookConfigs(): List<WebhookUrlConfig> {
        val prefs = context.settingsDataStore.data.first()
        val arrayJson = prefs[WEBHOOK_URLS_KEY].orEmpty()
        if (arrayJson.isNotBlank()) return parseWebhookUrlConfigs(arrayJson)
        // Migrate from legacy single-URL key
        val single = prefs[WEBHOOK_URL_KEY].orEmpty().trim()
        return if (single.isNotBlank()) listOf(WebhookUrlConfig(single, ALL_WEBHOOK_EVENTS)) else emptyList()
    }

    private fun postToUrl(url: String, body: String) {
        runCatching {
            val rb = body.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(rb).build()
            okHttpClient.newCall(req).execute().close()
        }
    }

    suspend fun maybeFireWebhook(
        event: String,
        mediaType: MediaType,
        tmdbId: Int,
        title: String,
        season: Int?,
        episode: Int?,
        positionSeconds: Long,
        durationSeconds: Long,
        progressPercent: Int,
        serverItemId: String? = null
    ) {
        val prefs = context.settingsDataStore.data.first()
        if (prefs[WEBHOOK_ENABLED_KEY] != true) return
        val configs = resolveWebhookConfigs()
        val matchingUrls = configs.filter { event in it.events }.map { it.url }
        if (matchingUrls.isEmpty()) return
        val intervalSeconds = prefs[WEBHOOK_INTERVAL_KEY]?.toIntOrNull() ?: 30

        val now = System.currentTimeMillis()
        val isLifecycleEvent = event == "start" || event == "pause" || event == "stop"
        if (!isLifecycleEvent && now - lastFireTimeMs < intervalSeconds * 1000L) return
        lastFireTimeMs = now

        val payload = JSONObject().apply {
            put("event", event)
            put("media_type", if (mediaType == MediaType.MOVIE) "movie" else "tv")
            put("tmdb_id", tmdbId)
            put("title", title)
            if (season != null) put("season", season)
            if (episode != null) put("episode", episode)
            put("position_seconds", positionSeconds)
            put("duration_seconds", durationSeconds)
            put("progress_percent", progressPercent)
            if (!serverItemId.isNullOrBlank()) put("server_item_id", serverItemId)
        }.toString()

        matchingUrls.forEach { url -> postToUrl(url, payload) }
    }

    suspend fun fireWatchlistEvent(
        event: String,
        title: String,
        tmdbId: Int,
        mediaType: MediaType,
        year: String? = null
    ) {
        val prefs = context.settingsDataStore.data.first()
        if (prefs[WEBHOOK_ENABLED_KEY] != true) return
        val configs = resolveWebhookConfigs()
        val matchingUrls = configs.filter { event in it.events }.map { it.url }
        if (matchingUrls.isEmpty()) return

        val payload = JSONObject().apply {
            put("event", event)
            put("title", title)
            put("tmdb_id", tmdbId)
            put("media_type", if (mediaType == MediaType.MOVIE) "movie" else "tv")
            if (!year.isNullOrBlank()) put("year", year)
        }.toString()

        matchingUrls.forEach { url -> postToUrl(url, payload) }
    }
}
