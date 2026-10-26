package com.arflix.tv.music

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// The same DataStore keys full Xadarr keeps its Home Assistant login under (synced in the settings
// blob); Xadarr Music copies them in at sign-in. Declared here so this package stays standalone.
val MUSIC_HA_URL_KEY = stringPreferencesKey("ha_url")
val MUSIC_HA_TOKEN_KEY = stringPreferencesKey("ha_token")

/**
 * A room's TV, through Home Assistant: `script.xadarr_music_tv_on` wakes the room's TV and opens
 * Xadarr Music there (it shows what's playing by itself); `script.xadarr_music_tv_off` turns it
 * off. The scripts hold the room -> TV mapping (HA's Android TV Remote entities), and their
 * `room` field's options are the rooms that have a TV, so the app reads that list from HA's
 * `/api/services` instead of keeping its own copy.
 *
 * Everything is a no-op when Home Assistant isn't set up or the scripts don't exist.
 */
@Singleton
class MusicTvRooms @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedRooms: Set<String>? = null

    /** Rooms that have a TV (lower-cased MA names), from the HA script's field options. */
    suspend fun roomsWithTv(refresh: Boolean = false): Set<String> {
        if (!refresh) cachedRooms?.let { return it }
        val rooms = withContext(Dispatchers.IO) {
            val (url, token) = login() ?: return@withContext null
            runCatching {
                val req = Request.Builder().url("$url/api/services").header("Authorization", "Bearer $token").get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val domains = JSONArray(resp.body?.string().orEmpty())
                    val script = (0 until domains.length()).mapNotNull { domains.optJSONObject(it) }
                        .firstOrNull { it.optString("domain") == "script" } ?: return@use emptySet()
                    val options = script.optJSONObject("services")?.optJSONObject(SCRIPT_ON)
                        ?.optJSONObject("fields")?.optJSONObject("room")
                        ?.optJSONObject("selector")?.optJSONObject("select")?.optJSONArray("options")
                        ?: return@use emptySet()
                    (0 until options.length()).mapNotNull { i ->
                        // Options may be plain strings or {label, value}.
                        (options.opt(i) as? JSONObject)?.optString("value") ?: options.optString(i)
                    }.filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
                }
            }.onFailure { Log.w(TAG, "services failed: ${it.message}") }.getOrNull()
        }
        if (rooms != null) cachedRooms = rooms
        return rooms ?: cachedRooms.orEmpty()
    }

    suspend fun hasTv(room: String): Boolean = room.lowercase() in roomsWithTv()

    suspend fun tvOn(room: String): Boolean = runScript(SCRIPT_ON, room)
    suspend fun tvOff(room: String): Boolean = runScript(SCRIPT_OFF, room)

    /** `script.turn_on` returns at once; the script itself waits for the TV to wake. */
    private suspend fun runScript(script: String, room: String): Boolean = withContext(Dispatchers.IO) {
        val (url, token) = login() ?: return@withContext false
        val body = JSONObject().put("entity_id", "script.$script").put("variables", JSONObject().put("room", room))
        runCatching {
            val req = Request.Builder().url("$url/api/services/script/turn_on")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.onFailure { Log.w(TAG, "$script($room) failed: ${it.message}") }.getOrDefault(false)
    }

    private suspend fun login(): Pair<String, String>? {
        val prefs = context.settingsDataStore.data.first()
        var url = prefs[MUSIC_HA_URL_KEY]?.trim()?.trimEnd('/').orEmpty()
        val token = prefs[MUSIC_HA_TOKEN_KEY]?.trim().orEmpty()
        if (url.isBlank() || token.isBlank()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        return url to token
    }

    companion object {
        private const val TAG = "MusicTvRooms"
        const val SCRIPT_ON = "xadarr_music_tv_on"
        const val SCRIPT_OFF = "xadarr_music_tv_off"
    }
}
