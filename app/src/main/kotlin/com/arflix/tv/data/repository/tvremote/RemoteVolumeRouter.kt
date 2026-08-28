package com.arflix.tv.data.repository.tvremote

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.arflix.tv.data.repository.HA_DEVICE_PROFILE_BY_HOST_KEY
import com.arflix.tv.data.repository.HA_VOLUME_ENTITY_BY_HOST_KEY
import com.arflix.tv.data.repository.HomeAssistantRepository
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which device actually handles each capability for a given Remote Mode target.
 *
 * Power, volume and input in one room are often three different devices, so each is routed
 * independently. A streaming box in particular is frequently not in its own room's audio path at
 * all: confirmed on real hardware that the Shield's volume keys move an internal stream that
 * bitstream passthrough makes inert (the on-screen bar moves, the Sonos doesn't), that forwarding
 * them over CEC instead made volume dead for *every* app on the device, and that Home Assistant's
 * own controls for the same Shield are equally inert. The physical remote only works because its
 * volume buttons fire IR straight at the TV, a path no app can use — so routing to the room's real
 * speaker is the fix, not a workaround.
 *
 * Anything left unset falls back to the device itself via [TvRemoteService], which stays correct
 * for a TV whose own speakers are the output.
 */
@Singleton
class RemoteVolumeRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeAssistantRepository: HomeAssistantRepository,
    private val tvRemoteService: TvRemoteService,
) {
    data class DeviceProfile(
        val volumeEntity: String? = null,
        val powerEntity: String? = null,
        val inputEntity: String? = null,
    ) {
        val isEmpty: Boolean get() = volumeEntity == null && powerEntity == null && inputEntity == null
    }

    suspend fun profileFor(host: String): DeviceProfile = profiles()[host] ?: DeviceProfile()

    suspend fun setProfile(host: String, profile: DeviceProfile) {
        context.settingsDataStore.edit { prefs ->
            val all = parseProfiles(prefs[HA_DEVICE_PROFILE_BY_HOST_KEY]).toMutableMap()
            if (profile.isEmpty) all.remove(host) else all[host] = profile
            prefs[HA_DEVICE_PROFILE_BY_HOST_KEY] = JSONObject(
                all.mapValues { (_, p) ->
                    JSONObject().apply {
                        p.volumeEntity?.let { put("volume", it) }
                        p.powerEntity?.let { put("power", it) }
                        p.inputEntity?.let { put("input", it) }
                    }
                }
            ).toString()
        }
    }

    // ── Capabilities ────────────────────────────────────────────────────────

    suspend fun volumeUp(host: String): Boolean =
        profileFor(host).volumeEntity?.let { homeAssistantRepository.mediaVolumeUp(it) }
            ?: tvRemoteService.sendVolumeUp(host)

    suspend fun volumeDown(host: String): Boolean =
        profileFor(host).volumeEntity?.let { homeAssistantRepository.mediaVolumeDown(it) }
            ?: tvRemoteService.sendVolumeDown(host)

    suspend fun mute(host: String, muted: Boolean): Boolean =
        profileFor(host).volumeEntity?.let { homeAssistantRepository.mediaVolumeMute(it, muted) }
            ?: tvRemoteService.sendMute(host)

    /**
     * Toggles power for the room. When an HA entity owns power we can read real state first and
     * send turn_on/turn_off explicitly — the thing an IR remote could never do, and why this can
     * be reliable where Harmony-style blind macros drifted out of sync.
     */
    suspend fun togglePower(host: String): Boolean =
        profileFor(host).powerEntity?.let { homeAssistantRepository.mediaTogglePower(it) }
            ?: tvRemoteService.sendPower(host)

    /** Selectable inputs for this target's configured input device; empty when none is set. */
    suspend fun inputs(host: String): List<String> =
        profileFor(host).inputEntity?.let { homeAssistantRepository.mediaSourceList(it) } ?: emptyList()

    suspend fun selectInput(host: String, source: String): Boolean =
        profileFor(host).inputEntity?.let { homeAssistantRepository.mediaSelectSource(it, source) } ?: false

    // ── Storage ─────────────────────────────────────────────────────────────

    private suspend fun profiles(): Map<String, DeviceProfile> {
        val prefs = context.settingsDataStore.data.first()
        val stored = parseProfiles(prefs[HA_DEVICE_PROFILE_BY_HOST_KEY])
        if (stored.isNotEmpty()) return stored
        // Migrate the earlier volume-only mapping so an already-configured speaker isn't lost.
        return parseLegacyVolumeOnly(prefs[HA_VOLUME_ENTITY_BY_HOST_KEY])
    }

    private fun parseProfiles(raw: String?): Map<String, DeviceProfile> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().mapNotNull { host ->
                val p = obj.optJSONObject(host) ?: return@mapNotNull null
                host to DeviceProfile(
                    volumeEntity = p.optString("volume").takeIf { it.isNotBlank() },
                    powerEntity = p.optString("power").takeIf { it.isNotBlank() },
                    inputEntity = p.optString("input").takeIf { it.isNotBlank() },
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun parseLegacyVolumeOnly(raw: String?): Map<String, DeviceProfile> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().mapNotNull { host ->
                obj.optString(host).takeIf { it.isNotBlank() }?.let { host to DeviceProfile(volumeEntity = it) }
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
