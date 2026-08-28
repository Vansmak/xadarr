package com.arflix.tv.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * REST-only Home Assistant client. No websocket connection is kept at runtime -
 * relevant entities are read house-wide via /api/states (no area scoping) and
 * controlled through /api/services/<domain>/<service>, exactly like the HA
 * REST docs describe.
 */
@Singleton
class HomeAssistantRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class HaEntity(
        val entityId: String,
        val name: String,
        val domain: String,
        val state: String,
        val brightnessPct: Int? = null,
        val fanPercentage: Int? = null,
        val deviceClass: String? = null,
        val targetTemperature: Double? = null,
        val currentTemperature: Double? = null,
        val hvacModes: List<String> = emptyList()
    )

    private data class HaConfig(val baseUrl: String, val token: String)

    private suspend fun config(): HaConfig {
        val prefs = context.settingsDataStore.data.first()
        return HaConfig(
            baseUrl = prefs[HA_URL_KEY]?.trim()?.trimEnd('/').orEmpty(),
            token = prefs[HA_TOKEN_KEY]?.trim().orEmpty()
        )
    }

    suspend fun isConfigured(): Boolean {
        val cfg = config()
        return cfg.baseUrl.isNotBlank() && cfg.token.isNotBlank()
    }

    sealed class HaResult {
        data class Success(val entities: List<HaEntity>) : HaResult()
        data class Error(val message: String) : HaResult()
        data object NotConfigured : HaResult()
    }

    // House-wide — lights, fans, AC (climate), and window contacts only.
    // Grouped by domain by the caller (SmartHomeScreen).
    suspend fun getAllEntities(): HaResult = withContext(Dispatchers.IO) {
        val cfg = config()
        if (cfg.baseUrl.isBlank() || cfg.token.isBlank()) return@withContext HaResult.NotConfigured
        try {
            val statesReq = Request.Builder()
                .url("${cfg.baseUrl}/api/states")
                .header("Authorization", "Bearer ${cfg.token}")
                .header("Cache-Control", "no-cache")
                .build()
            val statesBody = OkHttpProvider.client.newCall(statesReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val reason = when (resp.code) {
                        401 -> "Unauthorized (401) — check your Home Assistant token"
                        403 -> "Forbidden (403) — token lacks permission"
                        else -> "Home Assistant returned HTTP ${resp.code}"
                    }
                    return@withContext HaResult.Error(reason)
                }
                resp.body?.string()
            } ?: return@withContext HaResult.Error("Empty response from Home Assistant")

            val relevantDomains = setOf("light", "fan", "climate", "binary_sensor")
            val statesArr = JSONArray(statesBody)
            val entities = (0 until statesArr.length()).mapNotNull { i ->
                val obj = statesArr.optJSONObject(i) ?: return@mapNotNull null
                val entityId = obj.optString("entity_id")
                val domain = entityId.substringBefore(".", "")
                if (domain !in relevantDomains) return@mapNotNull null

                val attrs = obj.optJSONObject("attributes") ?: JSONObject()
                val deviceClass = attrs.optString("device_class").takeIf { it.isNotBlank() }
                // Only window contacts are useful here; skip door/motion/battery/etc.
                if (domain == "binary_sensor" && deviceClass != "window") return@mapNotNull null

                HaEntity(
                    entityId = entityId,
                    name = attrs.optString("friendly_name").ifBlank { entityId },
                    domain = domain,
                    state = obj.optString("state"),
                    brightnessPct = attrs.optInt("brightness", -1).takeIf { it >= 0 }?.let { (it * 100 + 127) / 255 },
                    fanPercentage = attrs.opt("percentage").toIntOrNullFlexible(),
                    deviceClass = deviceClass,
                    targetTemperature = attrs.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() },
                    currentTemperature = attrs.optDouble("current_temperature", Double.NaN).takeIf { !it.isNaN() },
                    hvacModes = attrs.optJSONArray("hvac_modes")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    } ?: emptyList()
                )
            }.sortedBy { "${domainSortKey(it.domain)}_${it.name}" }
            HaResult.Success(entities)
        } catch (e: Exception) {
            Log.e("HomeAssistant", "getAllEntities failed: ${e.message}", e)
            HaResult.Error(e.message ?: "Network error reaching Home Assistant")
        }
    }

    // ── Exposed-entity allow-list ───────────────────────────────────────────
    // Which entities show up on the Smart Home screen. Empty set = show all
    // (default, backward-compatible). Synced across devices via the settings
    // blob (see CloudSyncRepository), same as HA_URL/HA_TOKEN.

    suspend fun getExposedEntityIds(): Set<String> {
        val raw = context.settingsDataStore.data.first()[HA_EXPOSED_ENTITIES_KEY].orEmpty()
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
        }.getOrDefault(emptySet())
    }

    suspend fun setExposedEntityIds(ids: Set<String>) {
        val json = JSONArray(ids.toList()).toString()
        context.settingsDataStore.edit { it[HA_EXPOSED_ENTITIES_KEY] = json }
    }

    private fun domainSortKey(domain: String): Int = when (domain) {
        "light" -> 0
        "fan" -> 1
        "climate" -> 2
        "binary_sensor" -> 3
        else -> 9
    }

    private fun Any?.toIntOrNullFlexible(): Int? = when (this) {
        is Number -> this.toInt()
        is String -> this.toIntOrNull()
        else -> null
    }

    suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        extraData: JSONObject = JSONObject()
    ): Boolean = withContext(Dispatchers.IO) {
        val cfg = config()
        if (cfg.baseUrl.isBlank() || cfg.token.isBlank()) return@withContext false
        runCatching {
            extraData.put("entity_id", entityId)
            val body = extraData.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${cfg.baseUrl}/api/services/$domain/$service")
                .header("Authorization", "Bearer ${cfg.token}")
                .post(body)
                .build()
            OkHttpProvider.client.newCall(req).execute().use { it.isSuccessful }
        }.getOrElse {
            Log.e("HomeAssistant", "callService $domain.$service failed: ${it.message}", it)
            false
        }
    }

    suspend fun toggleLight(entityId: String, turnOn: Boolean, brightnessPct: Int? = null): Boolean {
        val data = JSONObject()
        if (turnOn && brightnessPct != null) data.put("brightness_pct", brightnessPct)
        return callService("light", if (turnOn) "turn_on" else "turn_off", entityId, data)
    }

    suspend fun setFan(entityId: String, turnOn: Boolean, percentage: Int? = null): Boolean {
        if (!turnOn) return callService("fan", "turn_off", entityId)
        val data = JSONObject()
        if (percentage != null) data.put("percentage", percentage)
        return callService("fan", "turn_on", entityId, data)
    }

    suspend fun setClimateMode(entityId: String, hvacMode: String): Boolean =
        callService("climate", "set_hvac_mode", entityId, JSONObject().put("hvac_mode", hvacMode))

    /**
     * Volume for a media_player entity — the only path that actually reaches a soundbar/speaker
     * fed by the TV rather than by the streaming box.
     *
     * Confirmed on real hardware that a Shield cannot control its own room's Sonos: its volume
     * keys move an internal stream that's inert under bitstream passthrough (OSD moves, nothing
     * audible), and enabling `hdmi_control_volume_control_enabled` to forward them over CEC made
     * volume stop working entirely for every app, because nothing downstream picks the CEC
     * command up. The physical remote only works because it fires IR straight at the TV. So for
     * rooms where the speaker is on the network, driving it through Home Assistant is the fix,
     * not a workaround — see [TvRemoteService] for the key-injection path this supersedes.
     */
    // HA MediaPlayerEntityFeature bits, for spotting entities that can change volume.
    private companion object {
        const val VOLUME_SET = 4
        const val VOLUME_MUTE = 8
        const val VOLUME_STEP = 1024
    }

    /**
     * entity_id -> area name, via the template API.
     *
     * Areas aren't in `/api/states` at all (they live in the entity/device registry), but the
     * picker badly needs them: one house can have several near-identical media_player names
     * ("Office TV 2" vs "Office TV 3", two different LG TVs), because `cast` and
     * `androidtv_remote` each register their own entity for the same physical TV. Without the
     * area the choice is a guess. Failure is non-fatal — labels just fall back to bare names.
     */
    private suspend fun areaNames(): Map<String, String> = withContext(Dispatchers.IO) {
        val cfg = config()
        if (cfg.baseUrl.isBlank() || cfg.token.isBlank()) return@withContext emptyMap()
        runCatching {
            val template = "{% for e in states.media_player %}{{ e.entity_id }}|{{ area_name(e.entity_id) }}\n{% endfor %}"
            val body = JSONObject().put("template", template).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${cfg.baseUrl}/api/template")
                .header("Authorization", "Bearer ${cfg.token}")
                .post(body)
                .build()
            OkHttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyMap()
                resp.body?.string().orEmpty().lineSequence().mapNotNull { line ->
                    val parts = line.split("|", limit = 2)
                    if (parts.size != 2) return@mapNotNull null
                    val area = parts[1].trim()
                    if (area.isBlank() || area == "None") null else parts[0].trim() to area
                }.toMap()
            }
        }.getOrElse { emptyMap() }
    }

    /**
     * Speakers/receivers that can act as a room's volume target. Deliberately a separate fetch
     * from [getAllEntities] rather than widening its `relevantDomains`: that set scopes the Smart
     * Home screen to lights/fans/AC/windows, and adding media_player there would dump every TV
     * and speaker into it.
     */
    suspend fun getMediaPlayers(): List<HaEntity> = withContext(Dispatchers.IO) {
        val cfg = config()
        if (cfg.baseUrl.isBlank() || cfg.token.isBlank()) return@withContext emptyList()
        runCatching {
            val req = Request.Builder()
                .url("${cfg.baseUrl}/api/states")
                .header("Authorization", "Bearer ${cfg.token}")
                .header("Cache-Control", "no-cache")
                .build()
            val body = OkHttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList() else resp.body?.string()
            } ?: return@withContext emptyList()

            val areas = areaNames()
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val entityId = obj.optString("entity_id")
                if (entityId.substringBefore(".", "") != "media_player") return@mapNotNull null
                val attrs = obj.optJSONObject("attributes") ?: JSONObject()
                // Accept anything that can change volume at all, by either route. Filtering on
                // `volume_level` alone silently dropped every Android TV: HA's Android TV Remote
                // integration supports step-based volume (VOLUME_STEP) with no absolute level to
                // report, which is precisely the case for a TV using its own speakers.
                val features = attrs.optInt("supported_features", 0)
                val supportsVolume = attrs.has("volume_level") ||
                    (features and (VOLUME_SET or VOLUME_STEP or VOLUME_MUTE)) != 0
                if (!supportsVolume) return@mapNotNull null
                // Area-qualified so near-identical names stay distinguishable in the picker.
                val friendly = attrs.optString("friendly_name").ifBlank { entityId }
                val area = areas[entityId]
                HaEntity(
                    entityId = entityId,
                    name = if (area != null && !friendly.contains(area, ignoreCase = true)) "$friendly · $area" else friendly,
                    domain = "media_player",
                    state = obj.optString("state"),
                    deviceClass = attrs.optString("device_class").takeIf { it.isNotBlank() },
                )
            }.sortedBy { it.name }
        }.getOrElse {
            Log.e("HomeAssistant", "getMediaPlayers failed: ${it.message}", it)
            emptyList()
        }
    }

    suspend fun mediaVolumeUp(entityId: String): Boolean =
        callService("media_player", "volume_up", entityId)

    suspend fun mediaVolumeDown(entityId: String): Boolean =
        callService("media_player", "volume_down", entityId)

    suspend fun mediaVolumeMute(entityId: String, muted: Boolean): Boolean =
        callService("media_player", "volume_mute", entityId, JSONObject().put("is_volume_muted", muted))

    suspend fun mediaTurnOn(entityId: String): Boolean = callService("media_player", "turn_on", entityId)

    suspend fun mediaTurnOff(entityId: String): Boolean = callService("media_player", "turn_off", entityId)

    /** Current state string for one entity ("on"/"off"/"playing"/"unavailable"/…), or null. */
    suspend fun entityState(entityId: String): String? = withContext(Dispatchers.IO) {
        val cfg = config()
        if (cfg.baseUrl.isBlank() || cfg.token.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder()
                .url("${cfg.baseUrl}/api/states/$entityId")
                .header("Authorization", "Bearer ${cfg.token}")
                .header("Cache-Control", "no-cache")
                .build()
            OkHttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else JSONObject(resp.body?.string().orEmpty()).optString("state").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** Power toggle that respects real state rather than guessing — the thing IR remotes never could. */
    suspend fun mediaTogglePower(entityId: String): Boolean {
        val on = entityState(entityId)?.let { it != "off" && it != "unavailable" && it != "standby" } ?: false
        return if (on) mediaTurnOff(entityId) else mediaTurnOn(entityId)
    }

    /** Selectable inputs for an entity, from its `source_list` attribute; empty if it has none. */
    suspend fun mediaSourceList(entityId: String): List<String> = withContext(Dispatchers.IO) {
        val cfg = config()
        if (cfg.baseUrl.isBlank() || cfg.token.isBlank()) return@withContext emptyList()
        runCatching {
            val req = Request.Builder()
                .url("${cfg.baseUrl}/api/states/$entityId")
                .header("Authorization", "Bearer ${cfg.token}")
                .header("Cache-Control", "no-cache")
                .build()
            OkHttpProvider.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val attrs = JSONObject(resp.body?.string().orEmpty()).optJSONObject("attributes")
                val arr = attrs?.optJSONArray("source_list") ?: return@use emptyList<String>()
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }
        }.getOrElse { emptyList() }
    }

    suspend fun mediaSelectSource(entityId: String, source: String): Boolean =
        callService("media_player", "select_source", entityId, JSONObject().put("source", source))
}
