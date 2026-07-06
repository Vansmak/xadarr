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

@Singleton
class FrigateRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class FrigateCamera(
        val name: String,
        val displayName: String,
        val snapshotUrl: String,
        val streamUrl: String
    )

    data class FrigateEvent(
        val id: String,
        val camera: String,
        val label: String,
        val startTimeMs: Long,
        val thumbnailUrl: String,
        val clipUrl: String
    )

    suspend fun baseUrl(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[FRIGATE_URL_KEY]?.trimEnd('/').orEmpty()
    }

    suspend fun getCameras(): List<FrigateCamera> = withContext(Dispatchers.IO) {
        val url = baseUrl()
        Log.d("Cameras", "baseUrl=$url")
        if (url.isBlank()) return@withContext emptyList()
        runCatching {
            val request = Request.Builder().url("$url/api/config").build()
            val body = OkHttpProvider.client.newCall(request).execute().use { it.body?.string() }
                ?: return@withContext emptyList()
            val cameras = JSONObject(body).optJSONObject("cameras") ?: return@withContext emptyList()
            val go2rtcBase = runCatching {
                val uri = java.net.URI(url)
                "http://${uri.host}:1984"
            }.getOrDefault("http://192.168.254.205:1984")
            cameras.keys().asSequence().sorted().mapNotNull { name ->
                cameras.optJSONObject(name) ?: return@mapNotNull null
                Log.d("Cameras", "camera=$name go2rtc=$go2rtcBase")
                FrigateCamera(
                    name = name,
                    displayName = name.replace('_', ' ')
                        .split(' ')
                        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } },
                    snapshotUrl = "$url/api/$name/latest.jpg",
                    streamUrl = "$go2rtcBase/api/stream.m3u8?src=$name"
                )
            }.toList()
        }.getOrElse {
            Log.e("Cameras", "getCameras failed: ${it.message}", it)
            emptyList()
        }
    }

    suspend fun getRecentEvents(limit: Int = 20): List<FrigateEvent> = withContext(Dispatchers.IO) {
        val url = baseUrl()
        if (url.isBlank()) return@withContext emptyList()
        runCatching {
            val request = Request.Builder()
                .url("$url/api/events?limit=$limit&has_clip=1")
                .build()
            val body = OkHttpProvider.client.newCall(request).execute().use { it.body?.string() }
                ?: return@withContext emptyList()
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val camera = obj.optString("camera").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FrigateEvent(
                    id = id,
                    camera = camera,
                    label = obj.optString("label").ifBlank { "Motion" },
                    startTimeMs = (obj.optDouble("start_time") * 1000).toLong(),
                    thumbnailUrl = "$url/api/events/$id/thumbnail.jpg",
                    clipUrl = "$url/api/events/$id/clip.mp4"
                )
            }
        }.getOrElse { emptyList() }
    }
}
