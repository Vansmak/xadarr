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
import java.net.URI
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeolinkRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class NeolinkCamera(
        val name: String,
        val displayName: String,
        val snapshotUrl: String,
        val streamUrl: String
    )

    data class NeolinkEvent(
        val id: String,
        val camera: String,
        val label: String,
        val startTimeMs: Long,
        val thumbnailUrl: String,
        val clipUrl: String
    )

    // The Neolink URL setting supports embedding the web-UI login as
    // userinfo, e.g. http://admin:mypassword@192.168.x.x:8655 — keeps the
    // native settings UI to a single field like the old Frigate URL was.
    private data class NeolinkConn(val base: String, val username: String, val password: String)

    private var cachedToken: String? = null
    private var tokenFetchedAt: Long = 0L

    private suspend fun connection(): NeolinkConn? {
        val prefs = context.settingsDataStore.data.first()
        val raw = prefs[NEOLINK_URL_KEY]?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            val uri = URI(raw)
            val userInfo = uri.userInfo?.split(":", limit = 2)
            val base = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            NeolinkConn(
                base = base,
                username = userInfo?.getOrNull(0).orEmpty(),
                password = userInfo?.getOrNull(1).orEmpty(),
            )
        }.getOrNull()
    }

    private suspend fun serverUrl(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SYNC_SERVER_URL_KEY]?.trimEnd('/').orEmpty()
    }

    suspend fun isConfigured(): Boolean = connection() != null

    private fun login(conn: NeolinkConn): String? {
        return runCatching {
            val json = JSONObject()
                .put("username", conn.username)
                .put("password", conn.password)
                .toString()
            val body = json.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("${conn.base}/api/auth/login").post(body).build()
            val respBody = OkHttpProvider.client.newCall(req).execute().use { it.body?.string() }
            respBody?.let { JSONObject(it).optString("token").takeIf(String::isNotBlank) }
        }.getOrNull().also {
            if (it != null) {
                cachedToken = it
                tokenFetchedAt = System.currentTimeMillis()
            }
        }
    }

    private fun token(conn: NeolinkConn, forceRefresh: Boolean = false): String? {
        val stale = System.currentTimeMillis() - tokenFetchedAt > 3_600_000L
        if (forceRefresh || cachedToken == null || stale) return login(conn)
        return cachedToken
    }

    private fun authedGet(conn: NeolinkConn, path: String): String? {
        var tok = token(conn) ?: return null
        var req = Request.Builder().url("${conn.base}$path").header("Authorization", "Bearer $tok").build()
        var resp = OkHttpProvider.client.newCall(req).execute()
        if (resp.code == 401) {
            resp.close()
            tok = token(conn, forceRefresh = true) ?: return null
            req = Request.Builder().url("${conn.base}$path").header("Authorization", "Bearer $tok").build()
            resp = OkHttpProvider.client.newCall(req).execute()
        }
        return resp.use { it.body?.string() }
    }

    suspend fun getCameras(): List<NeolinkCamera> = withContext(Dispatchers.IO) {
        val conn = connection() ?: return@withContext emptyList()
        val server = serverUrl()
        runCatching {
            val body = authedGet(conn, "/api/cameras") ?: return@withContext emptyList()
            val array = JSONArray(body)
            val rtspHost = URI(conn.base).host
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NeolinkCamera(
                    name = name,
                    displayName = name.replace('_', ' ')
                        .split(' ')
                        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } },
                    snapshotUrl = if (server.isNotBlank()) "$server/api/cameras/snapshot/$name" else "",
                    // Straight to Neolink's own RTSP restream, no go2rtc in front.
                    // go2rtc was tried for two different reasons and failed both:
                    // (1) it doesn't fix mainStream's on-device corruption — its own
                    // HEVC-to-HLS repackaging introduces "Invalid NAL unit" errors,
                    // confirmed via direct ffmpeg decode, independent of the device;
                    // (2) the original "nothing plays" bug it was added for was
                    // actually just Media3 defaulting to UDP RTP transport, fixed
                    // directly in CameraMediaSourceFactory below with no extra
                    // container needed. subStream (H264, 640x360) has decoded clean
                    // in every test all session — mainStream (4K H265) is the one
                    // still genuinely broken, likely a Media3 RTSP/HEVC bug.
                    streamUrl = "rtsp://$rtspHost:8654/$name/subStream",
                )
            }
        }.getOrElse {
            Log.e("Cameras", "getCameras failed: ${it.message}", it)
            emptyList()
        }
    }

    suspend fun getRecentEvents(limit: Int = 20): List<NeolinkEvent> = withContext(Dispatchers.IO) {
        val conn = connection() ?: return@withContext emptyList()
        val server = serverUrl()
        runCatching {
            val body = authedGet(conn, "/api/events?limit=$limit") ?: return@withContext emptyList()
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val camera = obj.optString("camera").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = obj.optJSONArray("labels")?.optString(0)?.takeIf { it.isNotBlank() } ?: "Motion"
                val startMs = runCatching { Instant.parse(obj.optString("start")).toEpochMilli() }.getOrDefault(0L)
                NeolinkEvent(
                    id = id,
                    camera = camera,
                    label = label,
                    startTimeMs = startMs,
                    // Neolink has no HTTP asset endpoint for these — served by xadarr-server
                    // instead, which reads them straight off the bind-mounted recordings dir.
                    thumbnailUrl = if (server.isNotBlank()) "$server/api/cameras/events/$id/thumb.jpg" else "",
                    clipUrl = if (server.isNotBlank()) "$server/api/cameras/events/$id/clip.mp4" else "",
                )
            }
        }.getOrElse { emptyList() }
    }
}
