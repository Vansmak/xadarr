package com.arflix.tv.music

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

// Synced through the settings blob like the HA keys (see CloudSyncRepository), so logging in on
// one device sets up Music on every device. Only the long-lived token is kept, never the password.
val MA_URL_KEY = stringPreferencesKey("ma_url")
val MA_TOKEN_KEY = stringPreferencesKey("ma_token")
val MA_SERVER_ID_KEY = stringPreferencesKey("ma_server_id")
val MA_USERNAME_KEY = stringPreferencesKey("ma_username")
// Per device on purpose (not synced): each TV remembers which zone it last controlled.
val MA_SELECTED_PLAYER_KEY = stringPreferencesKey("ma_selected_player")

const val MA_DEFAULT_URL = "http://192.168.254.205:8095"

/**
 * Everything in `com.arflix.tv.music` is self-contained on purpose: it depends only on
 * DataStore, OkHttp and Hilt, never on other Xadarr features, so it can be lifted into a
 * standalone Music Assistant remote app for devices that don't run full Xadarr. The Xadarr
 * glue (nav entry, Settings rows, settings-blob sync) lives outside this package.
 *
 * Music Assistant client — a remote control only. Every call is a command to the MA server,
 * which plays on the Sonos speakers itself; nothing here ever opens an audio stream on this
 * device.
 *
 * One WebSocket (`/ws`) is shared by every caller and only held open while something has
 * [acquire]d it (the Music screen). The first message after connecting must be `auth`, and
 * every command carries a `message_id` that its reply echoes back. Events (`player_updated`,
 * `queue_updated`, ...) arrive unprompted on the same socket and are re-emitted on [events].
 */
@Singleton
class MusicAssistantRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class ConnectionState { NOT_CONFIGURED, CONNECTING, CONNECTED, OFFLINE, AUTH_FAILED }

    data class Event(val name: String, val objectId: String?, val data: Any?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(ConnectionState.NOT_CONFIGURED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private val nextId = AtomicInteger(1)
    private val users = AtomicInteger(0)
    private var socket: WebSocket? = null
    private var connectJob: Job? = null
    @Volatile private var baseUrl: String = ""

    val isConfigured: Flow<Boolean> = context.settingsDataStore.data
        .map { !it[MA_URL_KEY].isNullOrBlank() && !it[MA_TOKEN_KEY].isNullOrBlank() }
        .distinctUntilChanged()

    fun baseUrl(): String = baseUrl

    // ── Login ────────────────────────────────────────────────────────────────

    /**
     * Logs in with username/password, swaps the 30-day session token for a long-lived one
     * (`auth/token/create`) and stores that. Returns null on success, else a readable error.
     */
    suspend fun login(rawUrl: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        try {
            val info = httpGet("$url/info") ?: return@withContext "Can't reach Music Assistant at $url"
            val serverId = info.optString("server_id")
            val loginBody = JSONObject()
                .put("credentials", JSONObject().put("username", username).put("password", password))
                .put("device_name", "Xadarr (${android.os.Build.MODEL})")
            val login = httpPost("$url/auth/login", loginBody, token = null)
            if (login == null || !login.optBoolean("success")) {
                return@withContext login?.optString("error")?.takeIf { it.isNotBlank() } ?: "Wrong username or password"
            }
            val sessionToken = login.optString("token")
            val tokenReply = httpPost(
                "$url/api",
                JSONObject().put("command", "auth/token/create")
                    .put("args", JSONObject().put("name", "Xadarr (${android.os.Build.MODEL})")),
                token = sessionToken,
            )
            // /api returns the command result directly — here a bare JSON string.
            val longLived = tokenReply?.optString("__raw")?.trim('"')?.takeIf { it.isNotBlank() } ?: sessionToken
            context.settingsDataStore.edit {
                it[MA_URL_KEY] = url
                it[MA_TOKEN_KEY] = longLived
                it[MA_SERVER_ID_KEY] = serverId
                it[MA_USERNAME_KEY] = username
            }
            if (users.get() > 0) reconnectNow()
            null
        } catch (e: Exception) {
            Log.w(TAG, "login failed", e)
            e.message ?: "Login failed"
        }
    }

    suspend fun saveUrl(rawUrl: String) {
        context.settingsDataStore.edit { it[MA_URL_KEY] = normalizeUrl(rawUrl) }
    }

    suspend fun logout() {
        context.settingsDataStore.edit {
            it.remove(MA_TOKEN_KEY)
            it.remove(MA_SERVER_ID_KEY)
            it.remove(MA_USERNAME_KEY)
        }
        disconnect()
        _state.value = ConnectionState.NOT_CONFIGURED
    }

    suspend fun selectedPlayerId(): String? =
        context.settingsDataStore.data.first()[MA_SELECTED_PLAYER_KEY]?.takeIf { it.isNotBlank() }

    suspend fun saveSelectedPlayerId(id: String) {
        context.settingsDataStore.edit { it[MA_SELECTED_PLAYER_KEY] = id }
    }

    // ── Connection lifecycle ────────────────────────────────────────────────

    fun acquire() {
        if (users.incrementAndGet() == 1) startConnectLoop()
    }

    fun release() {
        if (users.decrementAndGet() <= 0) {
            users.set(0)
            connectJob?.cancel()
            connectJob = null
            disconnect()
        }
    }

    fun reconnectNow() {
        disconnect()
        connectJob?.cancel()
        startConnectLoop()
    }

    private fun startConnectLoop() {
        connectJob = scope.launch {
            val backoffMs = longArrayOf(2_000, 5_000, 15_000, 30_000)
            var attempt = 0
            while (users.get() > 0) {
                val result = connectOnce()
                if (result == ConnectionState.NOT_CONFIGURED || result == ConnectionState.AUTH_FAILED) {
                    _state.value = result
                    // Wait for the stored URL/token to change (a new login) instead of hammering
                    // the server; drop(1) skips the current value, which is the one that just failed.
                    context.settingsDataStore.data
                        .map { it[MA_URL_KEY] to it[MA_TOKEN_KEY] }
                        .distinctUntilChanged()
                        .drop(1)
                        .first()
                    continue
                }
                if (result == ConnectionState.CONNECTED) attempt = 0
                // connectOnce returns once the socket has closed; back off and retry.
                if (users.get() <= 0) break
                _state.value = ConnectionState.OFFLINE
                delay(backoffMs[attempt.coerceAtMost(backoffMs.size - 1)])
                attempt++
            }
        }
    }

    /** Connects, authenticates and suspends until the socket closes. */
    private suspend fun connectOnce(): ConnectionState {
        val prefs = context.settingsDataStore.data.first()
        val url = prefs[MA_URL_KEY]?.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
        val token = prefs[MA_TOKEN_KEY]?.takeIf { it.isNotBlank() }
        if (url == null || token == null) return ConnectionState.NOT_CONFIGURED
        baseUrl = url
        _state.value = ConnectionState.CONNECTING

        // Don't hand our token to a different server that happens to answer at this address.
        val expectedServerId = prefs[MA_SERVER_ID_KEY].orEmpty()
        val info = runCatching { httpGet("$url/info") }.getOrNull() ?: return ConnectionState.OFFLINE
        if (expectedServerId.isNotBlank() && info.optString("server_id") != expectedServerId) {
            Log.w(TAG, "server_id mismatch at $url — refusing to send token")
            return ConnectionState.AUTH_FAILED
        }

        val closed = CompletableDeferred<Unit>()
        val wsUrl = url.replaceFirst("http", "ws") + "/ws"
        val ws = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure: ${t.message}")
                failPending(t)
                closed.complete(Unit)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                failPending(IllegalStateException("closed"))
                closed.complete(Unit)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })
        socket = ws

        val authOk = try {
            val reply = sendRaw("auth", JSONObject().put("token", token), timeoutMs = 8_000)
            (reply as? JSONObject)?.optBoolean("authenticated") == true
        } catch (e: Exception) {
            Log.w(TAG, "auth failed: ${e.message}")
            if (closed.isCompleted) return ConnectionState.OFFLINE
            false
        }
        if (!authOk) {
            ws.close(1000, null)
            socket = null
            return ConnectionState.AUTH_FAILED
        }
        _state.value = ConnectionState.CONNECTED
        _events.tryEmit(Event(EVENT_CONNECTED, null, null))
        closed.await()
        socket = null
        return ConnectionState.CONNECTED
    }

    private fun disconnect() {
        socket?.close(1000, null)
        socket = null
        failPending(IllegalStateException("disconnected"))
    }

    private fun failPending(t: Throwable) {
        pending.values.forEach { it.completeExceptionally(t) }
        pending.clear()
    }

    private fun handleMessage(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = msg.optString("message_id").takeIf { it.isNotBlank() }
        if (id != null) {
            val waiter = pending.remove(id) ?: return
            if (msg.has("error_code") || msg.has("error")) {
                waiter.completeExceptionally(
                    IllegalStateException(msg.optString("details").ifBlank { msg.optString("error", "error ${msg.opt("error_code")}") })
                )
            } else {
                waiter.complete(msg.opt("result").takeUnless { it == JSONObject.NULL })
            }
            return
        }
        val event = msg.optString("event").takeIf { it.isNotBlank() } ?: return
        _events.tryEmit(
            Event(event, msg.optString("object_id").takeIf { it.isNotBlank() }, msg.opt("data").takeUnless { it == JSONObject.NULL })
        )
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    /** Runs an MA command over the socket and returns its `result` (JSONObject/JSONArray/primitive/null). */
    suspend fun command(name: String, args: JSONObject = JSONObject()): Any? {
        if (_state.value != ConnectionState.CONNECTED) throw IllegalStateException("Not connected")
        return sendRaw(name, args)
    }

    /** Fire-and-forget variant for controls; failures are logged, not thrown. */
    fun fire(name: String, args: JSONObject) {
        scope.launch {
            runCatching { command(name, args) }.onFailure { Log.w(TAG, "$name failed: ${it.message}") }
        }
    }

    private suspend fun sendRaw(name: String, args: JSONObject, timeoutMs: Long = 15_000): Any? {
        val ws = socket ?: throw IllegalStateException("No socket")
        val id = nextId.getAndIncrement().toString()
        val waiter = CompletableDeferred<Any?>()
        pending[id] = waiter
        val sent = ws.send(JSONObject().put("message_id", id).put("command", name).put("args", args).toString())
        if (!sent) {
            pending.remove(id)
            throw IllegalStateException("send failed")
        }
        return try {
            withTimeout(timeoutMs) { waiter.await() }
        } finally {
            pending.remove(id)
        }
    }

    // ── Images ───────────────────────────────────────────────────────────────

    /**
     * Turns an MA image reference into a URL Coil can load. Remotely-accessible images
     * (Spotify's CDN, etc.) are used directly; everything else goes through MA's imageproxy.
     */
    fun imageUrl(image: JSONObject?, size: Int = 500): String? {
        if (image == null) return null
        val path = image.optString("path")
        if (image.optBoolean("remotely_accessible") && path.startsWith("http")) return path
        val proxyId = image.optString("proxy_id")
        if (proxyId.isNotBlank()) return "$baseUrl/imageproxy/$proxyId?size=$size"
        if (path.isBlank()) return null
        val provider = image.optString("provider")
        return "$baseUrl/imageproxy?path=${android.net.Uri.encode(path)}&provider=${android.net.Uri.encode(provider)}&size=$size"
    }

    /** Absolute-izes a URL the server already built (e.g. Player.current_media.image_url). */
    fun absoluteUrl(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http") -> url
        else -> baseUrl + (if (url.startsWith("/")) url else "/$url")
    }

    // ── HTTP helpers (login/info only) ───────────────────────────────────────

    private fun httpGet(url: String): JSONObject? {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()?.let { JSONObject(it) }
        }
    }

    private fun httpPost(url: String, body: JSONObject, token: String?): JSONObject? {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 && text.isBlank()) return null
            when {
                text.startsWith("{") -> JSONObject(text)
                text.startsWith("[") -> JSONObject().put("__raw", JSONArray(text))
                text.isNotBlank() && resp.isSuccessful -> JSONObject().put("__raw", text)
                else -> null
            }
        }
    }

    private fun normalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isNotBlank() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u
    }

    companion object {
        private const val TAG = "MusicAssistant"
        /** Synthetic event emitted after every (re)connect so callers can re-fetch state. */
        const val EVENT_CONNECTED = "__connected"
    }
}
