package com.arflix.tv.data.repository

import android.content.Context
import android.util.Log
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/** A single stream from Dispatcharr's full raw provider catalog — not necessarily part of the curated guide. */
data class RawProviderStream(
    val id: String,
    val name: String,
    val streamUrl: String,
    val group: String,
    val tvgId: String?,
    val logo: String?,
)

/**
 * Searches Dispatcharr's full, uncurated provider catalog (every stream ingested from every
 * IPTV provider, including groups the daily-lineup maintenance script never maps) via a proxy
 * route on Episeerr (`/api/integration/dispatcharr/streams/search`). Episeerr holds Dispatcharr's
 * API credentials — Xadarr never talks to Dispatcharr directly. Silently unavailable if Episeerr
 * isn't configured or the Dispatcharr Bridge addon isn't installed, matching the rest of the
 * Dispatcharr integration's "hidden until configured" behavior.
 */
@Singleton
class DispatcharrCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val http get() = OkHttpProvider.client
    private val tag = "DispatcharrCatalogRepo"

    suspend fun isAvailable(): Boolean {
        val prefs = context.settingsDataStore.data.first()
        val episeerrUrl = prefs[EPISEERR_URL_KEY]?.trim().orEmpty()
        val bridgeInstalled = prefs[GROUP_BLACKLIST_ENABLED_KEY] ?: false
        return episeerrUrl.isNotBlank() && bridgeInstalled
    }

    suspend fun search(query: String, limit: Int = 40): List<RawProviderStream> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        val prefs = context.settingsDataStore.data.first()
        val base = prefs[EPISEERR_URL_KEY]?.trimEnd('/').orEmpty().ifBlank { return@withContext emptyList() }
        try {
            val url = "$base/api/integration/dispatcharr/streams/search".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("q", query.trim())
                ?.addQueryParameter("limit", limit.toString())
                ?.build() ?: return@withContext emptyList()
            val req = Request.Builder().url(url).get().build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                resp.body?.string() ?: "{}"
            }
            val arr = org.json.JSONObject(body).optJSONArray("results") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val streamUrl = obj.optString("url").ifBlank { return@mapNotNull null }
                RawProviderStream(
                    id        = "raw:${obj.optString("id")}",
                    name      = obj.optString("name").ifBlank { "Unknown" },
                    streamUrl = streamUrl,
                    group     = obj.optString("group").ifBlank { "Provider Search" },
                    tvgId     = obj.optString("tvg_id").ifBlank { null },
                    logo      = obj.optString("logo").ifBlank { null },
                )
            }
        } catch (e: Exception) {
            Log.d(tag, "search failed: ${e.message}")
            emptyList()
        }
    }
}
