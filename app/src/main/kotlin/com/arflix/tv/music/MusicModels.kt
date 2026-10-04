package com.arflix.tv.music

import org.json.JSONArray
import org.json.JSONObject

/** A Music Assistant player (a Sonos zone, a group, an AirPlay speaker...). */
data class MaPlayer(
    val id: String,
    val name: String,
    val state: String,          // "playing" | "paused" | "idle" | "unknown"
    val volume: Int?,
    val groupVolume: Int?,
    val groupMembers: List<String>,
    val activeSource: String?,
    val visible: Boolean,
    val nowTitle: String?,
    val nowArtist: String?,
    val nowImageUrl: String?,
    val provider: String = "",
    /** The leader this speaker is grouped under, or null when it plays on its own. */
    val syncedTo: String? = null,
) {
    val isPlaying get() = state == "playing"
    val isGroupLeader get() = groupMembers.size > 1
    /** The volume to show/adjust: the group's when this player leads a group. */
    val effectiveVolume get() = (if (isGroupLeader) groupVolume else null) ?: volume
}

data class MaQueueItem(
    val id: String,
    val name: String,
    val artist: String?,
    val album: String?,
    val durationSec: Int,
    val imageUrl: String?,
    val quality: String?,
    val uri: String? = null,
)

data class MaQueue(
    val id: String,
    val state: String,
    val shuffle: Boolean,
    val repeat: String,          // "off" | "one" | "all"
    val currentIndex: Int?,
    val elapsedSec: Double,
    val current: MaQueueItem?,
)

/** Anything browsable/playable: a playlist, album, track, radio station or a browse folder. */
data class MaMediaItem(
    val uri: String,
    val name: String,
    val subtitle: String?,
    val mediaType: String,
    val imageUrl: String?,
    /** Set for browse folders: the path to pass back to `music/browse`. */
    val browsePath: String?,
) {
    val isFolder get() = browsePath != null && mediaType == "folder"
}

/** One song in a guide lineup (a playlist's tracks, or a zone's live queue). */
data class MaTrack(
    val name: String,
    val artist: String?,
    val album: String?,
    val durationSec: Int,
    val imageUrl: String?,
    val uri: String? = null,
    /** Set for songs from a zone's live queue: the item to jump to there. */
    val queueItemId: String? = null,
)

/** A playlist's opening songs plus its full size, for the guide row. */
data class MaPlaylistTracks(val tracks: List<MaTrack>, val count: Int, val totalSec: Long)

/**
 * What a zone is playing right now, for the guide: the queue from the current song on.
 * [currentStartMillis] is when the current song started, on this device's clock.
 */
data class MaLineup(
    val queueId: String,
    val zoneName: String,
    val sourceUri: String?,
    val tracks: List<MaTrack>,
    val currentStartMillis: Long,
)

internal object MaParse {

    fun player(o: JSONObject, repo: MusicAssistantRepository): MaPlayer {
        val media = o.optJSONObject("current_media")
        val syncedTo = o.optString("synced_to").takeIf { it.isNotBlank() && it != "null" }
        return MaPlayer(
            id = o.optString("player_id"),
            name = o.optString("name").ifBlank { o.optString("display_name") }.ifBlank { o.optString("player_id") },
            state = o.optString("playback_state", o.optString("state", "idle")),
            volume = o.optIntOrNull("volume_level"),
            groupVolume = o.optIntOrNull("group_volume"),
            groupMembers = o.optJSONArray("group_members").strings(),
            activeSource = o.optString("active_source").takeIf { it.isNotBlank() && it != "null" },
            // Members of a synced group are controlled through their leader, so they're hidden
            // from the zone list (they'd just mirror it); same for disabled/hidden players.
            // Sonos only (Joe, 2026-10-03: "all I want is sonos") -- MA also lists TVs, phones
            // and other universal players that aren't speakers.
            visible = o.optString("provider") == "sonos" &&
                o.optBoolean("available", true) &&
                o.optBoolean("enabled", true) &&
                !o.optBoolean("hide_in_ui", false) &&
                syncedTo == null,
            nowTitle = media?.optString("title")?.takeIf { it.isNotBlank() },
            nowArtist = media?.optString("artist")?.takeIf { it.isNotBlank() },
            nowImageUrl = repo.absoluteUrl(media?.optString("image_url")?.takeIf { it.isNotBlank() && it != "null" }),
            provider = o.optString("provider"),
            syncedTo = syncedTo,
        )
    }

    fun queue(o: JSONObject, repo: MusicAssistantRepository): MaQueue = MaQueue(
        id = o.optString("queue_id"),
        state = o.optString("state", "idle"),
        shuffle = o.optBoolean("shuffle_enabled"),
        repeat = o.optString("repeat_mode", "off"),
        currentIndex = o.optIntOrNull("current_index"),
        elapsedSec = o.optDouble("elapsed_time", 0.0).takeUnless { it.isNaN() } ?: 0.0,
        current = o.optJSONObject("current_item")?.let { queueItem(it, repo) },
    )

    fun queueItem(o: JSONObject, repo: MusicAssistantRepository): MaQueueItem {
        val media = o.optJSONObject("media_item")
        return MaQueueItem(
            id = o.optString("queue_item_id"),
            name = media?.optString("name")?.takeIf { it.isNotBlank() } ?: o.optString("name"),
            artist = media?.let { artists(it) },
            album = media?.optJSONObject("album")?.optString("name")?.takeIf { it.isNotBlank() },
            durationSec = o.optInt("duration", media?.optInt("duration", 0) ?: 0),
            imageUrl = repo.imageUrl(o.optJSONObject("image")) ?: media?.let { itemImage(it, repo) },
            quality = o.optJSONObject("streamdetails")?.optJSONObject("audio_format")?.let(::quality),
            uri = media?.optString("uri")?.takeIf { it.isNotBlank() },
        )
    }

    fun mediaItem(o: JSONObject, repo: MusicAssistantRepository): MaMediaItem? {
        val uri = o.optString("uri")
        val type = o.optString("media_type")
        val path = o.optString("path").takeIf { type == "folder" && it.isNotBlank() }
        if (uri.isBlank() && path == null) return null
        val subtitle = artists(o)
            ?: o.optJSONObject("album")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: o.optString("owner").takeIf { it.isNotBlank() }
        return MaMediaItem(
            uri = uri,
            name = o.optString("name").ifBlank { o.optString("label") }.ifBlank { uri },
            subtitle = subtitle,
            mediaType = type,
            imageUrl = itemImage(o, repo),
            browsePath = path,
        )
    }

    fun mediaItems(arr: JSONArray?, repo: MusicAssistantRepository): List<MaMediaItem> =
        (0 until (arr?.length() ?: 0)).mapNotNull { i -> arr?.optJSONObject(i)?.let { mediaItem(it, repo) } }

    private fun itemImage(o: JSONObject, repo: MusicAssistantRepository): String? {
        repo.imageUrl(o.optJSONObject("image"))?.let { return it }
        val images = o.optJSONObject("metadata")?.optJSONArray("images")
        if (images != null) {
            for (i in 0 until images.length()) {
                val img = images.optJSONObject(i) ?: continue
                if (img.optString("type", "thumb") == "thumb") repo.imageUrl(img)?.let { return it }
            }
        }
        return o.optJSONObject("album")?.let { itemImage(it, repo) }
    }

    /** A track media item, as returned by playlist_tracks or a queue item's `media_item`. */
    fun track(o: JSONObject, repo: MusicAssistantRepository, fallbackName: String? = null, fallbackImage: String? = null): MaTrack =
        MaTrack(
            name = o.optString("name").takeIf { it.isNotBlank() } ?: fallbackName.orEmpty(),
            artist = artists(o),
            album = o.optJSONObject("album")?.optString("name")?.takeIf { it.isNotBlank() },
            durationSec = o.optInt("duration", 0),
            imageUrl = fallbackImage ?: itemImage(o, repo),
            uri = o.optString("uri").takeIf { it.isNotBlank() },
        )

    private fun artists(o: JSONObject): String? {
        val arr = o.optJSONArray("artists") ?: return null
        val names = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        return names.joinToString(", ").takeIf { it.isNotBlank() }
    }

    /** "FLAC 24/44.1", or "OGG 320 kbps" for lossy streams. */
    fun quality(f: JSONObject): String? {
        val type = f.optString("content_type").substringAfterLast('.').uppercase().takeIf { it.isNotBlank() && it != "UNKNOWN" }
            ?: return null
        val lossless = type in setOf("FLAC", "ALAC", "WAV", "AIFF", "PCM", "WAVPACK", "DSF")
        return if (lossless) {
            val rate = f.optInt("sample_rate", 0)
            val bits = f.optInt("bit_depth", 0)
            if (rate > 0 && bits > 0) {
                val khz = rate / 1000.0
                val khzText = if (khz % 1.0 == 0.0) khz.toInt().toString() else "%.1f".format(khz)
                "$type $bits/$khzText"
            } else type
        } else {
            val kbps = f.optInt("bit_rate", 0).let { if (it > 10_000) it / 1000 else it }
            if (kbps > 0) "$type $kbps kbps" else type
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() }?.toInt() else null

    private fun JSONArray?.strings(): List<String> =
        (0 until (this?.length() ?: 0)).mapNotNull { this?.optString(it)?.takeIf { s -> s.isNotBlank() } }
}
