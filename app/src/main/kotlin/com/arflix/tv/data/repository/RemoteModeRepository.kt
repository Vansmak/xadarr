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

    // ── Control device ──────────────────────────────────────────────────────
    // Which device the remote's buttons drive. Lives here, next to the playback target, because
    // there are two surfaces onto the same remote — the guide's pill/sheet and the swipe-down
    // panel — and they must agree. Held per-ViewModel previously, so selecting a TV in one left
    // the other still showing the old device.
    //
    // Distinct from `target`: that's the Xadarr instance receiving tune/play, and pointing the
    // buttons at a TV must not move where a channel plays.
    private val _controlDevice = MutableStateFlow<com.arflix.tv.data.repository.tvremote.RemoteDevice?>(null)
    val controlDevice: StateFlow<com.arflix.tv.data.repository.tvremote.RemoteDevice?> = _controlDevice

    /** The last non-local device, so the pill's one-tap toggle can flip back to it. */
    var lastControlDevice: com.arflix.tv.data.repository.tvremote.RemoteDevice? = null
        private set

    fun setControlDevice(device: com.arflix.tv.data.repository.tvremote.RemoteDevice?) {
        if (device != null) lastControlDevice = device
        _controlDevice.value = device
        when {
            // "This device (Local)" has to mean everything happens here — otherwise the guide keeps
            // sending channels to the last target while the panel claims you're back on local.
            device == null -> setTarget(null)
            // Picking an Xadarr device also makes it the playback target: that's a room change.
            device.peer != null -> setTarget(device.peer)
            // A TV or speaker only takes over the buttons; playback stays wherever it was.
            else -> Unit
        }
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
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                // HTTP 200 alone isn't success — WebAppServer's json() helper always returns
                // 200 even for a semantic failure (e.g. handleRemoteTuneChannel's "not_found"
                // when the epgId doesn't match anything on the target's own snapshot), so a
                // caller relying only on isSuccessful gets a false "it worked" toast while
                // nothing actually happened on the target. Confirmed on-device: tune reported
                // success, target never changed channel.
                val responseBody = resp.body?.string().orEmpty()
                val status = runCatching { JSONObject(responseBody).optString("status") }.getOrNull()
                status != "not_found" && status != "error"
            }
        }.getOrDefault(false)
    }
}
