package com.arflix.tv.data.repository

import android.content.Context
import android.util.Log
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

data class AppNotification(
    val id: String,
    val source: String,
    val title: String,
    val message: String?,
    val type: String,
)

@Singleton
class NotificationPollManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "NotificationPollManager"
    private val http get() = OkHttpProvider.client

    private val _events = MutableSharedFlow<AppNotification>(replay = 0, extraBufferCapacity = 4)
    val notificationEvents: SharedFlow<AppNotification> = _events.asSharedFlow()

    private var lastSeenTimestamp: String? = null
    private var pollJob: Job? = null
    private var sessionStartedAtMs = 0L

    fun startPolling() {
        if (pollJob?.isActive == true) return
        sessionStartedAtMs = System.currentTimeMillis()
        pollJob = scope.launch {
            while (true) {
                poll()
                delay(60_000L)
            }
        }
    }

    fun refreshOnResume() {
        scope.launch { poll() }
    }

    // Called directly by WebAppServer when a /api/notify POST arrives on-device.
    fun receiveDirectNotification(notification: AppNotification) {
        _events.tryEmit(notification)
    }

    private suspend fun poll() = withContext(Dispatchers.IO) {
        val base = serverBase().ifBlank { return@withContext }
        try {
            val urlBuilder = StringBuilder("$base/api/notify/recent?limit=10")
            lastSeenTimestamp?.let { urlBuilder.append("&since=${it}") }

            val req = Request.Builder().url(urlBuilder.toString()).get().build()
            val body = http.newCall(req).execute().use { it.body?.string() ?: "[]" }
            val arr = JSONArray(body)

            if (arr.length() == 0) return@withContext

            // arr is newest-first; update cursor to the most recent timestamp
            val newest = arr.getJSONObject(0).optString("timestamp").ifBlank { null }
            if (newest != null) lastSeenTimestamp = newest

            val sessionWarm = System.currentTimeMillis() - sessionStartedAtMs > 5_000L
            if (sessionWarm) {
                // emit in chronological order (oldest first so toasts don't flash backwards)
                for (i in arr.length() - 1 downTo 0) {
                    val obj = arr.getJSONObject(i)
                    _events.tryEmit(
                        AppNotification(
                            id      = obj.optString("id"),
                            source  = obj.optString("source").ifBlank { "Unknown" },
                            title   = obj.optString("title").ifBlank { "Unknown" },
                            message = obj.optString("message").takeIf { it.isNotBlank() },
                            type    = obj.optString("type").ifBlank { "info" },
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "poll failed: ${e.message}")
        }
    }

    private suspend fun serverBase(): String {
        val prefs = context.settingsDataStore.data.first()
        return prefs[SYNC_SERVER_URL_KEY]?.trimEnd('/').orEmpty()
    }
}
