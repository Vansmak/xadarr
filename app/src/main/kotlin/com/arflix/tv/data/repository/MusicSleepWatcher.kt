package com.arflix.tv.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.arflix.tv.music.MusicAssistantRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TV turned off -> the music in its room stops too (Joe, 2026-10-12: "music keeps playing on
 * Sonos speaker even after turning device off"). On screen-off, if this TV's room speaker (the
 * one learned in the guide, see TvViewModel.musicRoomZoneId) is playing music, pause it; if the
 * room is only grouped into another room's music, take it out of the group so the other room
 * keeps playing. TV boxes only -- a phone's screen going off must not stop anything.
 */
@Singleton
class MusicSleepWatcher @Inject constructor(
    private val musicAssistantRepository: MusicAssistantRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register(context: Context) {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                val pending = goAsync()
                scope.launch {
                    try {
                        withTimeoutOrNull(9_000L) { stopRoomMusic(app) }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private suspend fun stopRoomMusic(context: Context) {
        val roomId = context.getSharedPreferences("music_room", Context.MODE_PRIVATE).getString("zone_id", null)
            ?: runCatching { musicAssistantRepository.selectedPlayerId() }.getOrNull()
            ?: return
        val speakers = runCatching { musicAssistantRepository.speakers() }.getOrDefault(emptyList())
        val room = speakers.firstOrNull { it.id == roomId } ?: return
        if (room.isTvAudio) return // the TV's own sound, not music
        val lineups = runCatching { musicAssistantRepository.playingLineups(limit = 1) }.getOrDefault(emptyList())
        val playing = lineups.filter { !it.paused }
        when {
            playing.any { it.queueId == roomId } -> {
                musicAssistantRepository.send("player_queues/pause", JSONObject().put("queue_id", roomId))
                Log.i(TAG, "screen off: paused ${room.name}")
            }
            room.syncedTo != null && playing.any { it.queueId == room.syncedTo } -> {
                musicAssistantRepository.setGrouped(room.syncedTo, roomId, false)
                Log.i(TAG, "screen off: took ${room.name} out of its group")
            }
        }
    }

    private companion object {
        const val TAG = "MusicSleepWatcher"
    }
}
