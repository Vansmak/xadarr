package com.xadarr.music

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.arflix.tv.music.MA_SERVER_ID_KEY
import com.arflix.tv.music.MA_TOKEN_KEY
import com.arflix.tv.music.MA_URL_KEY
import com.arflix.tv.music.MA_USERNAME_KEY
import com.arflix.tv.util.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Joe's Xadarr sync server (Episeerr). */
const val DEFAULT_SYNC_URL = "http://192.168.254.205:5002"

/**
 * Borrows the Music Assistant sign-in Xadarr already synced, so no password is typed on a TV.
 * Xadarr's settings blob carries `ma_url`/`ma_token`/... at its root (CloudSyncRepository's
 * export), served by the sync server at `/api/integration/xadarr/settings`.
 * Returns null on success, else a readable error.
 */
suspend fun importMusicAssistantFromXadarr(context: Context, rawSyncUrl: String): String? = withContext(Dispatchers.IO) {
    var base = rawSyncUrl.trim().trimEnd('/')
    if (base.isBlank()) return@withContext "Enter the Xadarr sync server"
    if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://$base"
    val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    val root = try {
        client.newCall(Request.Builder().url("$base/api/integration/xadarr/settings").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext "Sync server answered ${resp.code}"
            JSONObject(resp.body?.string().orEmpty())
        }
    } catch (e: Exception) {
        return@withContext "Can't reach $base"
    }
    val url = root.optString("ma_url")
    val token = root.optString("ma_token")
    if (url.isBlank() || token.isBlank()) return@withContext "Xadarr isn't signed in to Music Assistant"
    context.settingsDataStore.edit { prefs ->
        prefs[MA_URL_KEY] = url
        prefs[MA_TOKEN_KEY] = token
        root.optString("ma_server_id").takeIf { it.isNotBlank() }?.let { prefs[MA_SERVER_ID_KEY] = it }
        root.optString("ma_username").takeIf { it.isNotBlank() }?.let { prefs[MA_USERNAME_KEY] = it }
    }
    null
}
