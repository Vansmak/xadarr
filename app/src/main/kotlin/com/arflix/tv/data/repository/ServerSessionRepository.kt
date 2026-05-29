package com.arflix.tv.data.repository

import android.net.Uri
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
        }
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
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().close()
    }
}
