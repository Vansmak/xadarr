package com.arflix.tv.data.repository.tvremote

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.arflix.tv.data.repository.DPadKey
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
 * Sends a control to whichever device should actually receive it.
 *
 * Keys, power, input and transport all follow the *selected device* — pick the LG TV and its own
 * buttons apply. Volume is the one exception that stays separately mappable per device, because a
 * streaming box frequently isn't in its own room's audio path: confirmed on real hardware that the
 * Shield's volume keys move an internal stream that bitstream passthrough makes inert (the on-screen
 * bar moves, the Sonos doesn't), that forwarding them over CEC made volume dead for *every* app on
 * the device, and that Home Assistant's own controls for the same Shield are equally inert. The
 * physical remote only works because its volume buttons fire IR straight at the TV, a path no app
 * can use. So the Shield gets the Sonos mapped as its speaker; devices that own their own audio
 * need no mapping.
 */
@Singleton
class RemoteVolumeRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeAssistantRepository: HomeAssistantRepository,
    private val tvRemoteService: TvRemoteService,
) {
    /**
     * Per-device overrides. Only the speaker is mapped now — power and input used to be mapped too,
     * but selecting the device that owns them says the same thing more directly.
     */
    data class DeviceProfile(val volumeEntity: String? = null) {
        val isEmpty: Boolean get() = volumeEntity == null
    }

    suspend fun profileFor(deviceId: String): DeviceProfile = profiles()[deviceId] ?: DeviceProfile()

    suspend fun setProfile(deviceId: String, profile: DeviceProfile) {
        context.settingsDataStore.edit { prefs ->
            val all = parseProfiles(prefs[HA_DEVICE_PROFILE_BY_HOST_KEY]).toMutableMap()
            if (profile.isEmpty) all.remove(deviceId) else all[deviceId] = profile
            prefs[HA_DEVICE_PROFILE_BY_HOST_KEY] = JSONObject(
                all.mapValues { (_, p) -> JSONObject().apply { p.volumeEntity?.let { put("volume", it) } } }
            ).toString()
        }
    }

    // ── Capabilities ────────────────────────────────────────────────────────

    /**
     * The speaker a device's volume buttons should drive: an explicit mapping first, then the
     * device's own volume entity, and finally key injection on an Xadarr device itself.
     */
    private suspend fun volumeTarget(device: RemoteDevice): String? =
        profileFor(device.id).volumeEntity ?: device.volumeEntity

    suspend fun volumeUp(device: RemoteDevice): Boolean =
        volumeTarget(device)?.let { homeAssistantRepository.mediaVolumeUp(it) }
            ?: device.peer?.let { tvRemoteService.sendVolumeUp(it.host) }
            ?: false

    suspend fun volumeDown(device: RemoteDevice): Boolean =
        volumeTarget(device)?.let { homeAssistantRepository.mediaVolumeDown(it) }
            ?: device.peer?.let { tvRemoteService.sendVolumeDown(it.host) }
            ?: false

    suspend fun mute(device: RemoteDevice, muted: Boolean): Boolean =
        volumeTarget(device)?.let { homeAssistantRepository.mediaVolumeMute(it, muted) }
            ?: device.peer?.let { tvRemoteService.sendMute(it.host) }
            ?: false

    /**
     * Power for the selected device. When Home Assistant owns it we read real state first and send
     * an explicit turn_on/turn_off — the thing an IR remote could never do, and why blind macros
     * used to drift out of sync with reality.
     */
    suspend fun togglePower(device: RemoteDevice): Boolean =
        device.powerEntity?.let { homeAssistantRepository.mediaTogglePower(it) }
            ?: device.peer?.let { tvRemoteService.sendPower(it.host) }
            ?: false

    suspend fun selectInput(device: RemoteDevice, source: String): Boolean =
        device.inputEntity?.let { homeAssistantRepository.mediaSelectSource(it, source) } ?: false

    /**
     * Routes a key to the selected device. Returns false when the device can't take keys (a Sonos),
     * so the caller can leave the D-pad out of the UI entirely rather than offering dead buttons.
     */
    suspend fun sendKey(device: RemoteDevice, key: DPadKey): Boolean {
        val entity = device.dpadEntity ?: return false
        return when (device.keyTransport) {
            KeyTransport.WEBOSTV -> webosButtonFor(key)?.let { homeAssistantRepository.webosButton(entity, it) } ?: false
            KeyTransport.ANDROIDTV_REMOTE -> androidCommandFor(key)?.let { homeAssistantRepository.remoteSendCommand(entity, it) } ?: false
            else -> false
        }
    }

    // Button/command vocabularies differ per integration, so they're mapped rather than shared.
    private fun webosButtonFor(key: DPadKey): String? = when (key) {
        DPadKey.UP -> "UP"
        DPadKey.DOWN -> "DOWN"
        DPadKey.LEFT -> "LEFT"
        DPadKey.RIGHT -> "RIGHT"
        DPadKey.CENTER -> "ENTER"
        DPadKey.BACK -> "BACK"
        DPadKey.HOME -> "HOME"
        DPadKey.MENU -> "MENU"
        DPadKey.EXIT -> "EXIT"
        DPadKey.PLAY_PAUSE -> "PLAY"
        DPadKey.STOP -> "STOP"
        DPadKey.REWIND -> "REWIND"
        DPadKey.FAST_FORWARD -> "FASTFORWARD"
        DPadKey.VOLUME_UP, DPadKey.VOLUME_DOWN -> null // volume goes through the mapped speaker
    }

    private fun androidCommandFor(key: DPadKey): String? = when (key) {
        DPadKey.UP -> "DPAD_UP"
        DPadKey.DOWN -> "DPAD_DOWN"
        DPadKey.LEFT -> "DPAD_LEFT"
        DPadKey.RIGHT -> "DPAD_RIGHT"
        DPadKey.CENTER -> "DPAD_CENTER"
        DPadKey.BACK -> "BACK"
        DPadKey.HOME -> "HOME"
        DPadKey.MENU -> "MENU"
        DPadKey.EXIT -> "BACK"
        DPadKey.PLAY_PAUSE -> "MEDIA_PLAY_PAUSE"
        DPadKey.STOP -> "MEDIA_STOP"
        DPadKey.REWIND -> "MEDIA_REWIND"
        DPadKey.FAST_FORWARD -> "MEDIA_FAST_FORWARD"
        DPadKey.VOLUME_UP, DPadKey.VOLUME_DOWN -> null
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    private suspend fun profiles(): Map<String, DeviceProfile> {
        val prefs = context.settingsDataStore.data.first()
        val stored = parseProfiles(prefs[HA_DEVICE_PROFILE_BY_HOST_KEY])
        if (stored.isNotEmpty()) return stored
        // Migrate the earlier volume-only mapping so a configured speaker isn't silently lost.
        return parseLegacyVolumeOnly(prefs[HA_VOLUME_ENTITY_BY_HOST_KEY])
    }

    private fun parseProfiles(raw: String?): Map<String, DeviceProfile> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().mapNotNull { key ->
                val p = obj.optJSONObject(key) ?: return@mapNotNull null
                key to DeviceProfile(volumeEntity = p.optString("volume").takeIf { it.isNotBlank() })
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun parseLegacyVolumeOnly(raw: String?): Map<String, DeviceProfile> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().mapNotNull { host ->
                obj.optString(host).takeIf { it.isNotBlank() }
                    // Legacy entries were keyed by bare host; device ids are now prefixed.
                    ?.let { "xadarr:$host" to DeviceProfile(volumeEntity = it) }
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
