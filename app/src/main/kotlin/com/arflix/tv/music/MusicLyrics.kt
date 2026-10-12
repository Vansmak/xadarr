package com.arflix.tv.music

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** One lyric line; [timeMs] is null for plain (unsynced) lyrics. */
data class LyricLine(val timeMs: Long?, val text: String)

/** Lyrics for one track. [synced] means every line has a timestamp (an .lrc file). */
data class MusicLyrics(val lines: List<LyricLine>, val synced: Boolean) {
    /** Index of the line playing at [positionMs], or -1 before the first one. */
    fun currentIndex(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if ((lines[mid].timeMs ?: 0L) <= positionMs) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }
}

/**
 * Song lyrics for the Music screen's Lyrics tab and the screensaver. Music Assistant's own
 * metadata first (its LRCLIB/other lyrics providers fill `metadata.lrc_lyrics` / `lyrics` when
 * enabled), then LRCLIB directly -- free, no key: exact match by artist/title/album/duration,
 * then a search. Results (including "none") are cached per track for the session.
 *
 * Self-contained on purpose: this package is also compiled into the standalone Xadarr Music app.
 */
object MusicLyricsSource {
    private const val TAG = "MusicLyrics"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private object None
    private val cache = LruCache<String, Any>(64)

    fun key(item: MaQueueItem): String =
        item.uri ?: listOf(item.artist, item.name, item.album, item.durationSec).joinToString("|")

    suspend fun lyricsFor(track: MaTrack): MusicLyrics? = lyricsFor(
        MaQueueItem(id = "", name = track.name, artist = track.artist, album = track.album,
            durationSec = track.durationSec, imageUrl = track.imageUrl, quality = null, uri = track.uri)
    )

    suspend fun lyricsFor(item: MaQueueItem): MusicLyrics? {
        val k = key(item)
        cache.get(k)?.let { return it as? MusicLyrics }
        val found = fromMetadata(item) ?: runCatching { fromLrclib(item) }
            .onFailure { Log.w(TAG, "lrclib lookup failed for ${item.name}: ${it.message}") }
            .getOrNull()
        cache.put(k, found ?: None)
        return found
    }

    private fun fromMetadata(item: MaQueueItem): MusicLyrics? =
        item.lrcLyrics?.let(::parseLrc) ?: item.plainLyrics?.let(::parsePlain)

    private suspend fun fromLrclib(item: MaQueueItem): MusicLyrics? = withContext(Dispatchers.IO) {
        val artist = item.artist?.split(", ")?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val title = item.name.takeIf { it.isNotBlank() } ?: return@withContext null
        val exact = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .apply {
                item.album?.let { addQueryParameter("album_name", it) }
                if (item.durationSec > 0) addQueryParameter("duration", item.durationSec.toString())
            }.build()
        getJson(exact)?.let { o -> fromLrclibRecord(o as? JSONObject)?.let { return@withContext it } }
        val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .build()
        val results = getJson(search) as? JSONArray ?: return@withContext null
        // Prefer synced lyrics, then the closest duration.
        val records = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filterNot { it.optBoolean("instrumental") }
            .sortedWith(compareBy(
                { it.optString("syncedLyrics").isBlank() || it.isNull("syncedLyrics") },
                { if (item.durationSec > 0) abs(it.optDouble("duration", 0.0) - item.durationSec) else 0.0 },
            ))
        records.firstNotNullOfOrNull { fromLrclibRecord(it) }
    }

    private fun fromLrclibRecord(o: JSONObject?): MusicLyrics? {
        o ?: return null
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && !o.isNull("syncedLyrics") }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && !o.isNull("plainLyrics") }
        return synced?.let(::parseLrc) ?: plain?.let(::parsePlain)
    }

    private fun getJson(url: okhttp3.HttpUrl): Any? {
        val req = Request.Builder().url(url).header("User-Agent", "Xadarr Music (https://github.com/Vansmak/xadarr)").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string()?.trim().orEmpty()
            return when {
                body.startsWith("{") -> JSONObject(body)
                body.startsWith("[") -> JSONArray(body)
                else -> null
            }
        }
    }

    private val lrcTime = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parseLrc(text: String): MusicLyrics? {
        val lines = mutableListOf<LyricLine>()
        text.lineSequence().forEach { raw ->
            val stamps = lrcTime.findAll(raw).toList()
            if (stamps.isEmpty()) return@forEach
            val words = raw.substring(stamps.last().range.last + 1).trim()
            stamps.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                lines += LyricLine(min * 60_000 + sec * 1000 + ms, words)
            }
        }
        if (lines.none { it.text.isNotBlank() }) return parsePlain(text)
        return MusicLyrics(lines.sortedBy { it.timeMs }, synced = true)
    }

    fun parsePlain(text: String): MusicLyrics? {
        val lines = text.lines().map { LyricLine(null, it.trim()) }.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
        return if (lines.isEmpty()) null else MusicLyrics(lines, synced = false)
    }
}
