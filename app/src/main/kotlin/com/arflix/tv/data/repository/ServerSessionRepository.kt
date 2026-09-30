package com.arflix.tv.data.repository

import android.net.Uri
import android.util.Log
import com.arflix.tv.data.model.MediaType
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerSessionRepository @Inject constructor(
    private val homeServerRepository: HomeServerRepository,
    private val okHttpClient: OkHttpClient
) {
    // One UUID per logical play session, reset on each new item load.
    private var playSessionId: String = UUID.randomUUID().toString()

    fun resetSession() {
        playSessionId = UUID.randomUUID().toString()
    }

    suspend fun reportStart(
        serverItemId: String,
        mediaType: MediaType,
        positionMs: Long,
        durationMs: Long
    ) = report("start", serverItemId, mediaType, positionMs, durationMs)

    suspend fun reportProgress(
        serverItemId: String,
        mediaType: MediaType,
        positionMs: Long,
        durationMs: Long,
        isPaused: Boolean
    ) = report(if (isPaused) "pause" else "progress", serverItemId, mediaType, positionMs, durationMs)

    suspend fun reportStop(
        serverItemId: String,
        mediaType: MediaType,
        positionMs: Long,
        durationMs: Long
    ) = report("stop", serverItemId, mediaType, positionMs, durationMs)

    // Explicit mark-watched on the home server when Xadarr's own watched threshold is crossed.
    // Plex/Jellyfin only auto-mark from a timeline/stop report past their *own* threshold (~90%),
    // so with Xadarr's threshold set lower (or credits skipped) the server never flipped the
    // episode to watched — the "last episode never gets marked" gap.
    suspend fun markWatched(serverItemId: String) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val connection = runCatching { homeServerRepository.currentConnection() }.getOrNull()
            ?: return@withContext
        if (!connection.isUsable) return@withContext
        runCatching {
            when (connection.serverKind) {
                HomeServerKind.PLEX -> homeServerRepository.scrobblePlex(connection, serverItemId, watched = true)
                HomeServerKind.JELLYFIN, HomeServerKind.EMBY -> {
                    if (connection.userId.isBlank()) return@runCatching
                    val url = connection.serverUrl.trimEnd('/') +
                        "/Users/${Uri.encode(connection.userId)}/PlayedItems/${Uri.encode(serverItemId)}" +
                        "?api_key=" + Uri.encode(connection.accessToken)
                    val request = Request.Builder().url(url)
                        .post(ByteArray(0).toRequestBody(null)).build()
                    okHttpClient.newCall(request).execute().close()
                }
                HomeServerKind.UNKNOWN -> Unit
            }
        }.onFailure { Log.w(TAG, "markWatched failed for $serverItemId: ${it.message}") }
    }

    private suspend fun report(
        event: String,
        serverItemId: String,
        mediaType: MediaType,
        positionMs: Long,
        durationMs: Long
    ) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val connection = runCatching { homeServerRepository.currentConnection() }.getOrNull()
            ?: return@withContext
        if (!connection.isUsable) return@withContext

        runCatching {
            when (connection.serverKind) {
                HomeServerKind.JELLYFIN, HomeServerKind.EMBY -> reportJellyfin(
                    event, serverItemId, connection, positionMs, durationMs
                )
                HomeServerKind.PLEX -> reportPlex(
                    event, serverItemId, connection, positionMs, durationMs
                )
                HomeServerKind.UNKNOWN -> Unit
            }
        }.onFailure { Log.w(TAG, "$event report failed for $serverItemId: ${it.message}") }
    }

    private fun reportJellyfin(
        event: String,
        itemId: String,
        connection: HomeServerConnection,
        positionMs: Long,
        durationMs: Long
    ) {
        val ticks = positionMs * 10_000L
        val path = when (event) {
            "start"    -> "/Sessions/Playing"
            "stop"     -> "/Sessions/Playing/Stopped"
            else       -> "/Sessions/Playing/Progress"
        }
        val payload = JSONObject().apply {
            put("ItemId", itemId)
            put("SessionId", playSessionId)
            put("PositionTicks", ticks)
            if (durationMs > 0) put("RunTimeTicks", durationMs * 10_000L)
            if (event == "pause") put("IsPaused", true)
            if (event == "progress") put("IsPaused", false)
        }
        val url = connection.serverUrl.trimEnd('/') + path +
            "?api_key=" + Uri.encode(connection.accessToken)
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        okHttpClient.newCall(request).execute().close()
    }

    private fun reportPlex(
        event: String,
        ratingKey: String,
        connection: HomeServerConnection,
        positionMs: Long,
        durationMs: Long
    ) {
        val state = when (event) {
            "start"    -> "playing"
            "pause"    -> "paused"
            "stop"     -> "stopped"
            else       -> "playing"
        }
        val url = Uri.parse(connection.serverUrl.trimEnd('/') + "/:/timeline")
            .buildUpon()
            .appendQueryParameter("ratingKey", ratingKey)
            .appendQueryParameter("key", "/library/metadata/$ratingKey")
            .appendQueryParameter("state", state)
            .appendQueryParameter("time", positionMs.toString())
            .appendQueryParameter("duration", durationMs.toString())
            .appendQueryParameter("identifier", "com.plexapp.plugins.library")
            .appendQueryParameter("X-Plex-Token", connection.accessToken)
            .build()
            .toString()
        val request = Request.Builder().url(url).get()
            .apply { homeServerRepository.plexClientHeaders(connection).forEach { (k, v) -> header(k, v) } }
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            // Was fire-and-forget with the status ignored, which is how every report being
            // rejected (400, missing client identifier) went unnoticed.
            if (!resp.isSuccessful) Log.w(TAG, "Plex timeline $state for $ratingKey -> HTTP ${resp.code}")
        }
    }

    private companion object {
        const val TAG = "ServerSession"
    }
}
