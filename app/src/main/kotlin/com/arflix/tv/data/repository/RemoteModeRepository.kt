package com.arflix.tv.data.repository

import android.content.Context
import com.arflix.tv.data.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sending side of Remote Mode — v1 deliberately keeps the target in memory only (not
 * persisted across process death): reopening the app should land back in local mode, not
 * silently stay pointed at a device from last time.
 */
@Singleton
class RemoteModeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val _target = MutableStateFlow<LanPeer?>(null)
    val target: StateFlow<LanPeer?> = _target

    fun setTarget(peer: LanPeer?) {
        _target.value = peer
    }

    suspend fun sendTuneChannel(epgId: String): Boolean = post("/api/remote/tune-channel", JSONObject().put("epgId", epgId))

    suspend fun sendPlayTitle(mediaType: MediaType, tmdbId: Int, season: Int?, episode: Int?): Boolean =
        post(
            "/api/remote/play-title",
            JSONObject().apply {
                put("mediaType", if (mediaType == MediaType.TV) "show" else "movie")
                put("tmdbId", tmdbId)
                season?.let { put("season", it) }
                episode?.let { put("episode", it) }
            },
        )

    suspend fun sendDpad(key: DPadKey): Boolean =
        post("/api/remote/dpad", JSONObject().put("key", key.name.lowercase()))

    suspend fun sendText(text: String): Boolean =
        post("/api/remote/text", JSONObject().put("text", text))

    private suspend fun post(path: String, body: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val peer = _target.value ?: return@withContext false
        runCatching {
            val requestBody = body.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("${peer.baseUrl}$path").post(requestBody).build()
            okHttpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
