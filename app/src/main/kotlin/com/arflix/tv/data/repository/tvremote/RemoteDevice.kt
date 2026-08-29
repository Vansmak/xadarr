package com.arflix.tv.data.repository.tvremote

import com.arflix.tv.data.repository.HomeAssistantRepository
import com.arflix.tv.data.repository.HomeAssistantRepository.Companion.PAUSE
import com.arflix.tv.data.repository.HomeAssistantRepository.Companion.PLAY
import com.arflix.tv.data.repository.HomeAssistantRepository.Companion.SELECT_SOURCE
import com.arflix.tv.data.repository.HomeAssistantRepository.Companion.TURN_OFF
import com.arflix.tv.data.repository.HomeAssistantRepository.Companion.TURN_ON
import com.arflix.tv.data.repository.HomeAssistantRepository.Companion.VOLUME_MUTE
import com.arflix.tv.data.repository.HomeAssistantRepository.Companion.VOLUME_SET
import com.arflix.tv.data.repository.HomeAssistantRepository.Companion.VOLUME_STEP
import com.arflix.tv.data.repository.LanPeer

/** What a remote can do to a device — drives which controls the panel renders. */
enum class RemoteCapability { DPAD, TRANSPORT, VOLUME, POWER, INPUT, TEXT, XADARR }

/** How key presses reach a device; each integration wants a different service. */
enum class KeyTransport { XADARR, WEBOSTV, ANDROIDTV_REMOTE }

/**
 * One physical thing the remote can drive — an Xadarr instance, a TV, or a speaker.
 *
 * Selecting a device reconfigures the whole remote for it, rather than the remote being fixed to
 * one device with individually re-pointed capabilities. So driving the Shield and then jumping to
 * the LG TV to dismiss a firmware popup is a device switch, not a settings change.
 *
 * Each capability carries its own entity because one physical device is usually several HA
 * entities: an Android TV has a `remote` entity that takes key presses and a `media_player` that
 * reports volume, and the `cast` integration registers a third for the same box. They're merged
 * here by `device_id` so the picker lists the device once.
 */
data class RemoteDevice(
    val id: String,
    val name: String,
    val area: String? = null,
    /** Set when this is an Xadarr instance — also the tune/play target. */
    val peer: LanPeer? = null,
    val dpadEntity: String? = null,
    val keyTransport: KeyTransport? = null,
    val volumeEntity: String? = null,
    val inputEntity: String? = null,
    val powerEntity: String? = null,
    val transportEntity: String? = null,
    /** Cached so the INPUT chips don't need a second round trip. */
    val sources: List<String> = emptyList(),
    /** Set when an HA device was folded into this one, so it isn't also listed separately. */
    val mergedHaId: String? = null,
) {
    val isXadarr: Boolean get() = peer != null

    val displayName: String
        get() = if (area != null && !name.contains(area, ignoreCase = true)) "$name · $area" else name

    val capabilities: Set<RemoteCapability>
        get() = buildSet {
            if (peer != null) {
                add(RemoteCapability.DPAD)
                add(RemoteCapability.TRANSPORT)
                add(RemoteCapability.TEXT)
                add(RemoteCapability.XADARR)
                // An Xadarr device always offers volume: it routes to a mapped speaker when one is
                // set, and falls back to key injection on the device itself otherwise.
                add(RemoteCapability.VOLUME)
                add(RemoteCapability.POWER)
            }
            if (dpadEntity != null) add(RemoteCapability.DPAD)
            if (volumeEntity != null) add(RemoteCapability.VOLUME)
            if (inputEntity != null && sources.isNotEmpty()) add(RemoteCapability.INPUT)
            if (powerEntity != null) add(RemoteCapability.POWER)
            if (transportEntity != null) add(RemoteCapability.TRANSPORT)
        }

    fun supports(capability: RemoteCapability): Boolean = capability in capabilities

    /**
     * Folds a Home Assistant device into this Xadarr one — same physical box, two discovery
     * mechanisms. Keys keep using the Xadarr peer, which we pair with by IP over the Android TV
     * Remote Service (exact, and already system-level); HA contributes the things it alone knows
     * about, like the room's inputs and a power entity.
     */
    fun mergedWith(ha: RemoteDevice): RemoteDevice = copy(
        area = area ?: ha.area,
        dpadEntity = dpadEntity ?: ha.dpadEntity,
        keyTransport = keyTransport ?: ha.keyTransport,
        // A streaming box deliberately does NOT inherit an HA volume entity. Whatever HA reports
        // for a Shield is the same internal volume that bitstream passthrough makes inert, so
        // adopting it would leave the Speaker row saying "This device" while silently routing to
        // something that changes nothing. Better to leave it unset and let the mapping decide.
        volumeEntity = if (peer != null) volumeEntity else volumeEntity ?: ha.volumeEntity,
        inputEntity = inputEntity ?: ha.inputEntity,
        powerEntity = powerEntity ?: ha.powerEntity,
        transportEntity = transportEntity ?: ha.transportEntity,
        sources = sources.ifEmpty { ha.sources },
        mergedHaId = mergedHaId ?: ha.id,
    )

    companion object {
        /**
         * Whether an Xadarr peer and an HA device are the same physical thing.
         *
         * Matched on normalised names because there's no shared identifier: Xadarr discovers peers
         * by IP, while HA doesn't expose the androidtv_remote host through /api/states. So
         * "SHIELD Android TV" and "SHIELD" collapse to one entry. Deliberately containment rather
         * than equality, since the two sources name the same box at different lengths.
         */
        fun sameDevice(xadarrName: String, haName: String): Boolean {
            fun norm(v: String) = v.lowercase().filter { it.isLetterOrDigit() }
            val a = norm(xadarrName)
            val b = norm(haName)
            if (a.isBlank() || b.isBlank()) return false
            // Guard against a short name matching everything ("tv" inside "officetv").
            if (minOf(a.length, b.length) < 4) return a == b
            return a.contains(b) || b.contains(a)
        }

        /**
         * One entry per physical device: an Xadarr peer absorbs *every* HA device that is the same
         * box. There are usually several — the `cast` and `androidtv_remote` integrations each
         * register their own device for one Shield — so folding only the first left the other
         * still listed separately, which is exactly the duplicate this is meant to remove.
         */
        fun merge(xadarrDevices: List<RemoteDevice>, haDevices: List<RemoteDevice>): List<RemoteDevice> {
            val absorbed = mutableSetOf<String>()
            val merged = xadarrDevices.map { xadarr ->
                val twins = haDevices.filter { sameDevice(xadarr.name, it.name) }
                absorbed += twins.map { it.id }
                twins.fold(xadarr) { acc, twin -> acc.mergedWith(twin) }
            }
            return merged + haDevices.filterNot { it.id in absorbed }
        }

        fun fromPeer(peer: LanPeer): RemoteDevice = RemoteDevice(
            id = "xadarr:${peer.host}",
            name = peer.displayName,
            peer = peer,
        )

        /**
         * Groups HA entities into one device each, unioning their capabilities.
         *
         * Entities without a `device_id` stand alone (keyed by entity_id). Anything that ends up
         * with no capability at all is dropped — a `cast`-only entity, for instance, reports no
         * volume and takes no keys, so it would be a dead choice in the picker.
         */
        fun fromHaEntities(entities: List<HomeAssistantRepository.HaControlEntity>): List<RemoteDevice> =
            entities.groupBy { it.deviceId ?: it.entityId }
                .map { (groupKey, group) -> buildDevice(groupKey, group) }
                .filter { it.capabilities.isNotEmpty() }
                .let { dedupe(it) }

        /**
         * Folds HA devices that are the same physical thing.
         *
         * Grouping by `device_id` isn't enough: the `cast` and `androidtv_remote` integrations
         * register *separate devices* for one TV, not merely separate entities, so every TV would
         * otherwise appear twice. Matched on name within the same area, and the fold unions their
         * capabilities — typically the cast half brings volume and the androidtv_remote half
         * brings the D-pad.
         */
        private fun dedupe(devices: List<RemoteDevice>): List<RemoteDevice> {
            val out = mutableListOf<RemoteDevice>()
            devices.forEach { candidate ->
                val existing = out.indexOfFirst {
                    sameDevice(it.name, candidate.name) &&
                        (it.area == null || candidate.area == null || it.area == candidate.area)
                }
                if (existing >= 0) out[existing] = out[existing].mergedWith(candidate) else out.add(candidate)
            }
            return out
        }

        private fun buildDevice(
            groupKey: String,
            group: List<HomeAssistantRepository.HaControlEntity>,
        ): RemoteDevice {
            val keyEntity = group.firstOrNull { entity ->
                when (entity.integration) {
                    // androidtv_remote takes keys on its `remote` entity, not its media_player.
                    "androidtv_remote" -> entity.domain == "remote"
                    "webostv" -> entity.domain == "media_player"
                    else -> false
                }
            }
            val transport = when (keyEntity?.integration) {
                "androidtv_remote" -> KeyTransport.ANDROIDTV_REMOTE
                "webostv" -> KeyTransport.WEBOSTV
                else -> null
            }

            val players = group.filter { it.domain == "media_player" }
            val volume = players.firstOrNull {
                it.hasVolumeLevel ||
                    (it.supportedFeatures and (VOLUME_SET or VOLUME_STEP or VOLUME_MUTE)) != 0
            }
            val isTv = group.any { it.deviceClass == "tv" }
            // Only TVs get an input picker. An Android box's source_list is its installed app
            // packages (com.xadarr.tv and 30 friends), and a Sonos's is favourites/stations —
            // neither is an "input", and both would just be noise in that row.
            val input = if (!isTv) null else players.firstOrNull {
                it.sourceList.isNotEmpty() && (it.supportedFeatures and SELECT_SOURCE) != 0
            }
            val power = group.firstOrNull { (it.supportedFeatures and (TURN_ON or TURN_OFF)) != 0 }
            // No transport row on a TV. You navigate a TV — D-pad, inputs, power — you don't
            // scrub it, so play/pause/rewind are dead weight there. Speakers and streaming boxes
            // are the things whose playback you actually drive.
            val transportEntity = if (isTv) null else {
                players.firstOrNull { (it.supportedFeatures and (PLAY or PAUSE)) != 0 }
            }

            // Prefer the name of whichever entity the user is most likely to recognise, and never
            // the bare entity_id if a friendly name exists anywhere in the group.
            val named = keyEntity ?: volume ?: input ?: power ?: group.first()

            return RemoteDevice(
                id = groupKey,
                name = named.name,
                area = group.firstNotNullOfOrNull { it.area },
                dpadEntity = keyEntity?.entityId,
                keyTransport = transport,
                volumeEntity = volume?.entityId,
                inputEntity = input?.entityId,
                powerEntity = power?.entityId,
                transportEntity = transportEntity?.entityId,
                sources = input?.sourceList ?: emptyList(),
            )
        }
    }
}
