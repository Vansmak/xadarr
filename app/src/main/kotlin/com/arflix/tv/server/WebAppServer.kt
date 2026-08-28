package com.arflix.tv.server

import android.content.Context
import android.util.Log
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.AppNotification
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.DEVICE_NAME_KEY
import com.arflix.tv.data.repository.DPadKey
import com.arflix.tv.data.repository.HomeServerRepository
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.NotificationPollManager
import com.arflix.tv.data.repository.RemoteCommand
import com.arflix.tv.data.repository.RemoteCommandBus
import com.arflix.tv.data.repository.SYNC_SERVER_URL_KEY
import com.arflix.tv.data.repository.WATCHLIST_API_ENABLED_KEY
import com.arflix.tv.data.repository.WATCHLIST_API_PORT_KEY
import com.arflix.tv.data.repository.WEBHOOK_ENABLED_KEY
import com.arflix.tv.data.repository.WEBHOOK_INTERVAL_KEY
import com.arflix.tv.data.repository.WEBHOOK_URL_KEY
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.util.Constants
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.datastore.preferences.core.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebAppServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val iptvRepository: IptvRepository,
    private val watchlistRepository: WatchlistRepository,
    private val homeServerRepository: HomeServerRepository,
    private val playerStateHolder: PlayerStateHolder,
    private val tmdbApi: TmdbApi,
    private val cloudSyncRepository: CloudSyncRepository,
    private val notificationPollManager: NotificationPollManager,
    private val remoteCommandBus: RemoteCommandBus,
) {
    companion object {
        const val DEFAULT_PORT = 7979
        private const val TAG = "WebAppServer"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var broadcastJob: Job? = null
    private var server: NanoHTTPD? = null

    // SSE client streams — broadcast player state to all connected EventSource clients
    private val sseClients = Collections.synchronizedSet(LinkedHashSet<PipedOutputStream>())

    fun start(port: Int = DEFAULT_PORT) {
        stop()
        val self = this
        val nano = object : NanoHTTPD(port) {
            override fun serve(session: IHTTPSession): Response = self.serveHttp(session)
        }
        try {
            nano.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = nano
            Log.i(TAG, "Started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start on port $port: ${e.message}")
            return
        }
        broadcastJob = scope.launch {
            playerStateHolder.state.collect { state ->
                broadcastSse(stateToJson(state).toString())
            }
        }
    }

    fun stop() {
        broadcastJob?.cancel()
        broadcastJob = null
        sseClients.toList().forEach { runCatching { it.close() } }
        sseClients.clear()
        server?.stop()
        server = null
        Log.i(TAG, "Stopped")
    }

    fun isRunning(): Boolean = server?.isAlive == true
    fun currentPort(): Int = server?.listeningPort ?: DEFAULT_PORT

    // ── HTTP routing ──────────────────────────────────────────────────────────

    private fun serveHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri.substringBefore("?")
        val method = session.method
        return try {
            when {
                method == NanoHTTPD.Method.OPTIONS ->
                    corsOk()
                method == NanoHTTPD.Method.GET && uri == "/" ->
                    serveAsset("web/index.html", "text/html; charset=utf-8")
                method == NanoHTTPD.Method.GET && uri.startsWith("/assets/") ->
                    serveAsset("web/${uri.removePrefix("/assets/")}", mimeFor(uri))
                method == NanoHTTPD.Method.GET && uri == "/watchlist" ->
                    serveWatchlistLegacy()
                // Settings
                method == NanoHTTPD.Method.GET && uri == "/api/settings" ->
                    runBlocking { handleGetSettings() }
                method == NanoHTTPD.Method.POST && uri == "/api/settings" ->
                    runBlocking { handlePostSettings(session) }
                // IPTV
                method == NanoHTTPD.Method.GET && uri == "/api/iptv/channels" ->
                    runBlocking { handleGetChannels() }
                method == NanoHTTPD.Method.GET && uri == "/api/iptv/epg" ->
                    runBlocking { handleGetEpg() }
                // Media
                method == NanoHTTPD.Method.GET && uri == "/api/media/search" ->
                    runBlocking { handleSearch(session.parameters["q"]?.firstOrNull() ?: "") }
                method == NanoHTTPD.Method.GET && uri == "/api/media/trending" ->
                    runBlocking { handleTrending() }
                method == NanoHTTPD.Method.GET && uri == "/api/media/watchlist" ->
                    handleGetWatchlistItems()
                method == NanoHTTPD.Method.POST && uri == "/api/media/watchlist" ->
                    runBlocking { handleAddToWatchlist(session) }
                method == NanoHTTPD.Method.DELETE && uri.startsWith("/api/media/watchlist/") ->
                    runBlocking { handleRemoveFromWatchlist(uri) }
                method == NanoHTTPD.Method.GET && uri == "/api/media/home" ->
                    runBlocking { handleGetHome() }
                // Player state
                method == NanoHTTPD.Method.GET && uri == "/api/player/state" ->
                    handleGetPlayerState()
                // SSE stream for real-time player state
                method == NanoHTTPD.Method.GET && uri == "/api/player/events" ->
                    handleSseStream()
                // Notifications — direct push from Sonarr, Radarr, etc.
                method == NanoHTTPD.Method.POST && uri == "/api/notify" ->
                    handleNotify(session)
                // Device sync endpoints (LAN peer-to-peer)
                method == NanoHTTPD.Method.GET && uri == "/api/sync/status" ->
                    runBlocking { handleSyncStatus() }
                // Remote Mode (LAN peer-to-peer playback dispatch — see RemoteCommandBus)
                method == NanoHTTPD.Method.POST && uri == "/api/remote/tune-channel" ->
                    runBlocking { handleRemoteTuneChannel(session) }
                method == NanoHTTPD.Method.POST && uri == "/api/remote/play-title" ->
                    handleRemotePlayTitle(session)
                method == NanoHTTPD.Method.POST && uri == "/api/remote/dpad" ->
                    handleRemoteDpad(session)
                method == NanoHTTPD.Method.POST && uri == "/api/remote/text" ->
                    handleRemoteText(session)
                method == NanoHTTPD.Method.GET && uri == "/api/sync/snapshot" ->
                    runBlocking { handleGetSnapshot() }
                method == NanoHTTPD.Method.PUT && uri == "/api/sync/snapshot" ->
                    runBlocking { handlePutSnapshot(session) }
                else ->
                    notFound()
            }.also { addCors(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Handler error ${session.uri}", e)
            json(JSONObject().put("error", e.message ?: "Error"))
                .also { it.status = NanoHTTPD.Response.Status.INTERNAL_ERROR; addCors(it) }
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private suspend fun handleGetSettings(): NanoHTTPD.Response {
        val prefs = context.settingsDataStore.data.first()
        return json(JSONObject().apply {
            put("webhook_enabled", prefs[WEBHOOK_ENABLED_KEY] ?: false)
            put("webhook_url", prefs[WEBHOOK_URL_KEY] ?: "")
            put("webhook_interval_seconds", prefs[WEBHOOK_INTERVAL_KEY] ?: "30")
            put("watchlist_api_enabled", prefs[WATCHLIST_API_ENABLED_KEY] ?: false)
            put("watchlist_api_port", prefs[WATCHLIST_API_PORT_KEY] ?: DEFAULT_PORT.toString())
            put("sync_server_url", prefs[SYNC_SERVER_URL_KEY] ?: "")
        })
    }

    private suspend fun handlePostSettings(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val raw = bodyMap["postData"] ?: return json(JSONObject().put("error", "empty body"))
        val body = JSONObject(raw)
        context.settingsDataStore.edit { prefs ->
            if (body.has("webhook_enabled")) prefs[WEBHOOK_ENABLED_KEY] = body.getBoolean("webhook_enabled")
            if (body.has("webhook_url")) prefs[WEBHOOK_URL_KEY] = body.getString("webhook_url")
            if (body.has("webhook_interval_seconds")) prefs[WEBHOOK_INTERVAL_KEY] = body.getString("webhook_interval_seconds")
            if (body.has("watchlist_api_port")) prefs[WATCHLIST_API_PORT_KEY] = body.getString("watchlist_api_port")
            if (body.has("sync_server_url")) prefs[SYNC_SERVER_URL_KEY] = body.getString("sync_server_url")
        }
        return json(JSONObject().put("status", "ok"))
    }

    private suspend fun handleGetChannels(): NanoHTTPD.Response {
        val snap = iptvRepository.getMemoryCachedSnapshot()
        val arr = JSONArray()
        snap?.channels?.forEach { ch ->
            arr.put(JSONObject().apply {
                put("id", ch.id)
                put("name", ch.name)
                put("streamUrl", ch.streamUrl)
                put("group", ch.group)
                put("logo", ch.logo ?: "")
                put("epgId", ch.epgId ?: "")
            })
        }
        val groups = snap?.groupOrder?.ifEmpty { snap.grouped.keys.toList() } ?: emptyList()
        return json(JSONObject().put("channels", arr).put("groups", JSONArray(groups)))
    }

    private suspend fun handleGetEpg(): NanoHTTPD.Response {
        val snap = iptvRepository.getMemoryCachedSnapshot()
        val obj = JSONObject()
        snap?.nowNext?.forEach { (id, nn) ->
            val entry = JSONObject()
            nn.now?.let { p -> entry.put("now", JSONObject().put("title", p.title).put("startUtcMillis", p.startUtcMillis).put("endUtcMillis", p.endUtcMillis)) }
            nn.next?.let { p -> entry.put("next", JSONObject().put("title", p.title).put("startUtcMillis", p.startUtcMillis).put("endUtcMillis", p.endUtcMillis)) }
            obj.put(id, entry)
        }
        return json(obj)
    }

    private fun handleGetWatchlistItems(): NanoHTTPD.Response {
        val arr = JSONArray()
        watchlistRepository.getCachedItems().forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("mediaType", if (item.mediaType == MediaType.TV) "show" else "movie")
                put("year", item.year)
                put("image", item.image ?: "")
                put("overview", item.overview ?: "")
                put("progress", item.progress)
            })
        }
        return json(arr)
    }

    private suspend fun handleGetHome(): NanoHTTPD.Response {
        val items = runCatching { homeServerRepository.fetchResumeItems() }.getOrDefault(emptyList())
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("serverItemId", item.serverItemId)
                put("tmdbId", item.tmdbId)
                put("title", item.title)
                put("mediaType", if (item.mediaType == MediaType.TV) "show" else "movie")
                put("progress", item.progress)
                put("resumePositionMs", item.resumePositionMs)
                put("durationMs", item.durationMs)
                put("season", item.season ?: JSONObject.NULL)
                put("episode", item.episode ?: JSONObject.NULL)
                put("episodeTitle", item.episodeTitle ?: "")
                put("imageUrl", item.imageUrl)
                put("serverName", item.serverName)
            })
        }
        return json(arr)
    }

    private fun handleGetPlayerState(): NanoHTTPD.Response =
        json(stateToJson(playerStateHolder.state.value))

    private suspend fun handleSearch(query: String): NanoHTTPD.Response {
        if (query.isBlank()) return json(JSONArray())
        val inWatchlistIds = watchlistRepository.getCachedItems()
            .associate { "${it.mediaType.name.lowercase()}:${it.id}" to true }
        val results = runCatching {
            tmdbApi.searchMulti(Constants.TMDB_API_KEY, query).results
        }.getOrDefault(emptyList())
        val arr = JSONArray()
        for (item in results) {
            val type = item.mediaType ?: continue
            if (type != "movie" && type != "tv") continue
            val id = item.id
            val title = item.title ?: item.name ?: continue
            val year = (item.releaseDate ?: item.firstAirDate)?.take(4) ?: ""
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w300$it" } ?: ""
            val inWatchlist = inWatchlistIds.containsKey("${if (type == "tv") "tv" else "movie"}:$id")
            arr.put(JSONObject().apply {
                put("id", id)
                put("title", title)
                put("mediaType", if (type == "tv") "show" else "movie")
                put("year", year)
                put("image", poster)
                put("overview", item.overview ?: "")
                put("rating", item.voteAverage)
                put("inWatchlist", inWatchlist)
            })
        }
        return json(arr)
    }

    private suspend fun handleTrending(): NanoHTTPD.Response {
        val inWatchlistIds = watchlistRepository.getCachedItems()
            .associate { "${it.mediaType.name.lowercase()}:${it.id}" to true }
        val movies = runCatching { tmdbApi.getTrendingMovies(Constants.TMDB_API_KEY).results }.getOrDefault(emptyList())
        val tv = runCatching { tmdbApi.getTrendingTv(Constants.TMDB_API_KEY).results }.getOrDefault(emptyList())
        val results = (movies.map { it.copy(mediaType = it.mediaType ?: "movie") } +
                       tv.map { it.copy(mediaType = it.mediaType ?: "tv") })
            .sortedByDescending { it.popularity }
        val arr = JSONArray()
        for (item in results) {
            val type = item.mediaType ?: continue
            if (type != "movie" && type != "tv") continue
            val title = item.title ?: item.name ?: continue
            val year = (item.releaseDate ?: item.firstAirDate)?.take(4) ?: ""
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w300$it" } ?: ""
            val inWatchlist = inWatchlistIds.containsKey("${if (type == "tv") "tv" else "movie"}:${item.id}")
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("title", title)
                put("mediaType", if (type == "tv") "show" else "movie")
                put("year", year)
                put("image", poster)
                put("overview", item.overview ?: "")
                put("rating", item.voteAverage)
                put("inWatchlist", inWatchlist)
            })
        }
        return json(arr)
    }

    private suspend fun handleAddToWatchlist(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val raw = bodyMap["postData"] ?: return json(JSONObject().put("error", "empty body"))
        val body = JSONObject(raw)
        val id = body.optInt("id")
        val typeStr = body.optString("mediaType")
        val mediaType = if (typeStr == "show" || typeStr == "tv") MediaType.TV else MediaType.MOVIE
        val mediaItem = MediaItem(
            id = id,
            title = body.optString("title"),
            mediaType = mediaType,
            image = body.optString("image"),
            overview = body.optString("overview"),
            year = body.optString("year"),
        )
        val plexPushOk = watchlistRepository.addToWatchlist(mediaType, id, mediaItem)
        return json(JSONObject().put("status", "added").put("plexPushOk", plexPushOk))
    }

    private suspend fun handleRemoveFromWatchlist(uri: String): NanoHTTPD.Response {
        // URI: /api/media/watchlist/{type}/{id}
        val parts = uri.removePrefix("/api/media/watchlist/").split("/")
        if (parts.size < 2) return json(JSONObject().put("error", "bad path"))
        val mediaType = if (parts[0] == "show" || parts[0] == "tv") MediaType.TV else MediaType.MOVIE
        val id = parts[1].toIntOrNull() ?: return json(JSONObject().put("error", "bad id"))
        watchlistRepository.removeFromWatchlist(mediaType, id)
        return json(JSONObject().put("status", "removed"))
    }

    // ── Notification handler ──────────────────────────────────────────────────

    private fun handleNotify(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val raw = bodyMap["postData"]?.takeIf { it.isNotBlank() }
            ?: return json(JSONObject().put("ok", false).put("error", "empty body"))
        val body = runCatching { JSONObject(raw) }.getOrNull()
            ?: return json(JSONObject().put("ok", false).put("error", "invalid json"))

        val notification = parseArrWebhook(body) ?: run {
            val title = body.optString("title").trim()
            if (title.isBlank()) return json(JSONObject().put("ok", false).put("error", "title required"))
            AppNotification(
                id      = UUID.randomUUID().toString(),
                source  = body.optString("source").trim().ifBlank { "Unknown" },
                title   = title,
                message = body.optString("message").trim().takeIf { it.isNotBlank() },
                type    = body.optString("type").trim().ifBlank { "info" },
            )
        }
        notificationPollManager.receiveDirectNotification(notification)
        return json(JSONObject().put("ok", true).put("id", notification.id))
    }

    private fun parseArrWebhook(data: JSONObject): AppNotification? {
        val eventType = data.optString("eventType").ifBlank { return null }
        val typeMap = mapOf(
            "Grab" to "grab", "Download" to "ready",
            "HealthIssue" to "error", "HealthRestored" to "info",
            "ApplicationUpdate" to "info", "Test" to "info",
            "MovieAdded" to "info", "SeriesAdd" to "info",
        )
        val notifType = typeMap[eventType] ?: "info"
        return when {
            data.has("series") -> {
                val series = data.optJSONObject("series")
                val title = series?.optString("title") ?: "Unknown"
                val episodes = data.optJSONArray("episodes")
                val message = if (episodes != null && episodes.length() > 0) {
                    val ep = episodes.getJSONObject(0)
                    val s = ep.optInt("seasonNumber"); val e = ep.optInt("episodeNumber")
                    val epTitle = ep.optString("title")
                    "S${s.toString().padStart(2,'0')}E${e.toString().padStart(2,'0')}" +
                        if (epTitle.isNotBlank()) " – $epTitle" else ""
                } else eventType
                AppNotification(UUID.randomUUID().toString(), "Sonarr", title, message, notifType)
            }
            data.has("movie") -> {
                val movie = data.optJSONObject("movie")
                val title = movie?.optString("title") ?: "Unknown"
                AppNotification(UUID.randomUUID().toString(), "Radarr", title, eventType, notifType)
            }
            else -> null
        }
    }

    // ── Sync handlers ─────────────────────────────────────────────────────────

    private suspend fun handleSyncStatus(): NanoHTTPD.Response {
        val prefs = context.settingsDataStore.data.first()
        val deviceName = prefs[DEVICE_NAME_KEY]?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
        return json(JSONObject().put("status", "ok").put("app", "xadarr").put("deviceName", deviceName))
    }

    // ── Remote Mode handlers ─────────────────────────────────────────────────
    // Each resolves anything needing THIS device's own local state, then hands off to
    // RemoteCommandBus rather than touching a specific screen — the app may be sitting on
    // any screen, or backgrounded, when a command arrives.

    private suspend fun handleRemoteTuneChannel(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val raw = bodyMap["postData"] ?: return json(JSONObject().put("status", "error").put("message", "empty body"))
        val epgId = JSONObject(raw).optString("epgId").trim()
        if (epgId.isBlank()) return json(JSONObject().put("status", "error").put("message", "epgId required"))
        val snapshot = iptvRepository.getMemoryCachedSnapshot() ?: iptvRepository.getCachedSnapshotOrNull()
        val channel = snapshot?.channels?.firstOrNull { it.epgId?.equals(epgId, ignoreCase = true) == true }
        if (channel == null) return json(JSONObject().put("status", "not_found"))
        remoteCommandBus.emit(RemoteCommand.TuneChannel(channel.id))
        return json(JSONObject().put("status", "tuned").put("name", channel.name))
    }

    private fun handleRemotePlayTitle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val raw = bodyMap["postData"] ?: return json(JSONObject().put("status", "error").put("message", "empty body"))
        val body = JSONObject(raw)
        val typeStr = body.optString("mediaType")
        val mediaType = if (typeStr == "show" || typeStr == "tv") MediaType.TV else MediaType.MOVIE
        val tmdbId = body.optInt("tmdbId", -1)
        if (tmdbId <= 0) return json(JSONObject().put("status", "error").put("message", "tmdbId required"))
        val season = body.optInt("season", -1).takeIf { it >= 0 }
        val episode = body.optInt("episode", -1).takeIf { it >= 0 }
        remoteCommandBus.emit(RemoteCommand.PlayTitle(mediaType, tmdbId, season, episode))
        return json(JSONObject().put("status", "queued"))
    }

    private fun handleRemoteDpad(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val raw = bodyMap["postData"] ?: return json(JSONObject().put("status", "error").put("message", "empty body"))
        val key = runCatching { DPadKey.valueOf(JSONObject(raw).optString("key").uppercase()) }.getOrNull()
            ?: return json(JSONObject().put("status", "error").put("message", "unknown key"))
        remoteCommandBus.emit(RemoteCommand.DPad(key))
        return json(JSONObject().put("status", "ok"))
    }

    private fun handleRemoteText(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val raw = bodyMap["postData"] ?: return json(JSONObject().put("status", "error").put("message", "empty body"))
        val text = JSONObject(raw).optString("text")
        remoteCommandBus.emit(RemoteCommand.TypeText(text))
        return json(JSONObject().put("status", "ok"))
    }

    private suspend fun handleGetSnapshot(): NanoHTTPD.Response {
        val payload = runCatching { cloudSyncRepository.buildCloudSnapshotJson() }
            .getOrElse { return json(JSONObject().put("error", it.message ?: "build failed")) }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json", payload
        )
    }

    private suspend fun handlePutSnapshot(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val payload = bodyMap["postData"]?.takeIf { it.isNotBlank() }
            ?: return json(JSONObject().put("error", "empty body"))
        val ok = cloudSyncRepository.applyIncomingSnapshot(payload)
        return json(JSONObject().put("status", if (ok) "applied" else "failed"))
    }

    // ── SSE ───────────────────────────────────────────────────────────────────

    private fun handleSseStream(): NanoHTTPD.Response {
        val out = PipedOutputStream()
        val input = PipedInputStream(out, 65_536)
        sseClients.add(out)

        // Heartbeat + cleanup coroutine for this connection
        scope.launch(Dispatchers.IO) {
            try {
                // Send current state immediately on connect
                out.write("data: ${stateToJson(playerStateHolder.state.value)}\n\n".toByteArray())
                out.flush()
                // Keep alive with periodic heartbeats until client disconnects
                while (true) {
                    delay(25_000)
                    out.write(": ping\n\n".toByteArray())
                    out.flush()
                }
            } catch (_: IOException) {
                // Client disconnected
            } finally {
                sseClients.remove(out)
                runCatching { out.close() }
            }
        }

        return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "text/event-stream", input).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("X-Accel-Buffering", "no")
            addCors(this)
        }
    }

    private fun broadcastSse(payload: String) {
        val data = "data: $payload\n\n".toByteArray()
        synchronized(sseClients) { sseClients.toList() }.forEach { out ->
            runCatching { out.write(data); out.flush() }
                .onFailure { sseClients.remove(out) }
        }
    }

    // ── Legacy watchlist ──────────────────────────────────────────────────────

    private fun serveWatchlistLegacy(): NanoHTTPD.Response {
        val arr = JSONArray()
        watchlistRepository.getCachedItems().forEach { item ->
            arr.put(JSONObject().apply {
                put("title", item.title)
                put("tmdb_id", item.id)
                put("media_type", if (item.mediaType == MediaType.TV) "show" else "movie")
                put("year", item.year.toIntOrNull() ?: 0)
                put("poster_path", item.image ?: "")
            })
        }
        return json(arr)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun stateToJson(state: PlayerStateHolder.State) = JSONObject().apply {
        put("isPlaying", state.isPlaying)
        put("isPaused", state.isPaused)
        put("title", state.title)
        put("episodeTitle", state.episodeTitle ?: "")
        put("overview", state.overview ?: "")
        put("posterUrl", state.posterUrl ?: "")
        put("positionMs", state.positionMs)
        put("durationMs", state.durationMs)
        put("streamUrl", state.streamUrl ?: "")
        put("isLive", state.isLive)
    }

    private fun serveAsset(path: String, mimeType: String): NanoHTTPD.Response = try {
        val bytes = context.assets.open(path).use { it.readBytes() }
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mimeType,
            ByteArrayInputStream(bytes), bytes.size.toLong()
        ).also { it.addHeader("Cache-Control", "no-cache") }
    } catch (_: IOException) { notFound() }

    private fun json(obj: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", obj.toString())

    private fun json(arr: JSONArray): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", arr.toString())

    private fun notFound(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found")

    private fun corsOk(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "")

    private fun addCors(r: NanoHTTPD.Response) {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
    }

    private fun mimeFor(uri: String) = when {
        uri.endsWith(".js") -> "application/javascript"
        uri.endsWith(".css") -> "text/css"
        uri.endsWith(".html") -> "text/html; charset=utf-8"
        uri.endsWith(".png") -> "image/png"
        uri.endsWith(".jpg") || uri.endsWith(".jpeg") -> "image/jpeg"
        uri.endsWith(".svg") -> "image/svg+xml"
        uri.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }
}
