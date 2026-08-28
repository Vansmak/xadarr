package com.arflix.tv.data.repository.tvremote

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.arflix.tv.data.repository.HA_VOLUME_ENTITY_BY_HOST_KEY
import com.arflix.tv.data.repository.HomeAssistantRepository
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides *where* a volume press should actually go for a given Remote Mode target.
 *
 * A streaming box is often not in its own room's audio path at all. Confirmed on real hardware:
 * the Shield's volume keys move an internal stream that bitstream passthrough makes inert (the
 * on-screen volume bar moves, the speakers don't), and forwarding them over CEC instead
 * (`hdmi_control_volume_control_enabled=1`) made volume dead for every app on the device, because
 * nothing downstream acts on the CEC command. The physical remote only works because its volume
 * buttons fire IR straight at the TV, bypassing Android entirely — a path no app can use.
 *
 * So when the room's speaker is reachable on the network (a Sonos, an AVR, anything Home
 * Assistant exposes as a media_player), driving it directly is the only thing that works. Falls
 * back to key injection on the device itself for rooms where that isn't set up, which is still
 * correct for TVs whose own speakers are the output.
 */
@Singleton
class RemoteVolumeRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeAssistantRepository: HomeAssistantRepository,
    private val tvRemoteService: TvRemoteService,
) {
    /** host -> HA media_player entity_id owning that room's volume. */
    val volumeEntityByHost = context.settingsDataStore.data.map { prefs ->
        parse(prefs[HA_VOLUME_ENTITY_BY_HOST_KEY])
    }

    suspend fun volumeEntityFor(host: String): String? =
        parse(context.settingsDataStore.data.first()[HA_VOLUME_ENTITY_BY_HOST_KEY])[host]?.takeIf { it.isNotBlank() }

    suspend fun setVolumeEntity(host: String, entityId: String?) {
        context.settingsDataStore.edit { prefs ->
            val current = parse(prefs[HA_VOLUME_ENTITY_BY_HOST_KEY]).toMutableMap()
            if (entityId.isNullOrBlank()) current.remove(host) else current[host] = entityId
            prefs[HA_VOLUME_ENTITY_BY_HOST_KEY] = JSONObject(current.toMap()).toString()
        }
    }

    suspend fun volumeUp(host: String): Boolean {
        volumeEntityFor(host)?.let { return homeAssistantRepository.mediaVolumeUp(it) }
        return tvRemoteService.sendVolumeUp(host)
    }

    suspend fun volumeDown(host: String): Boolean {
        volumeEntityFor(host)?.let { return homeAssistantRepository.mediaVolumeDown(it) }
        return tvRemoteService.sendVolumeDown(host)
    }

    suspend fun mute(host: String, muted: Boolean): Boolean {
        volumeEntityFor(host)?.let { return homeAssistantRepository.mediaVolumeMute(it, muted) }
        return tvRemoteService.sendMute(host)
    }

    private fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }.getOrDefault(emptyMap())
    }
}
