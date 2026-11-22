package com.arflix.tv.music

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import com.arflix.tv.ui.glass.GlassPalette
import com.arflix.tv.ui.glass.GlassMode
import com.arflix.tv.ui.glass.SunClock
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.random.Random
import androidx.compose.ui.text.font.FontStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.animation.core.animateFloat

/** Idle time on the Music screen, while music plays, before the screensaver takes over. */
const val MUSIC_AMBIENT_AFTER_MS = 60_000L

// Music keeps the screen awake while it plays, so the screensaver (not the TV's sleep timer)
// takes over. After an hour untouched, one quiet "turns off soon" line; no press within a
// minute and the TV may sleep as usual -- the music carries on on the Sonos (Joe, 2026-10-05:
// "a keep awake every so often ... but not too naggy").
const val MUSIC_AWAKE_CHECK_MS = 60 * 60_000L
const val MUSIC_AWAKE_GRACE_MS = 60_000L

/** Holds the screen on while [enabled]; released when it flips off or the screen leaves. */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * The current cover, blurred and darkened, filling the screen behind the Music screen.
 * Blurred by decoding it tiny (24px) and stretching it, which looks the same on every Android
 * version -- Modifier.blur only works on Android 12+, and the Shield is on 11. [scrim] is how
 * dark the overlay is (0..1). Fades to the next cover when the song changes.
 */
@Composable
fun BlurredCoverBackground(url: String?, scrim: Float, base: Color? = null) {
    val context = LocalContext.current
    val bg = base ?: MaterialTheme.colorScheme.background
    Crossfade(targetState = url, animationSpec = tween(900), label = "cover-bg") { u ->
        if (u != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(u).size(24).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp),
            )
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(bg.copy(alpha = (scrim - 0.12f).coerceIn(0f, 1f)), bg.copy(alpha = (scrim + 0.12f).coerceIn(0f, 1f))))
        )
    )
}

/**
 * Glass Now Playing backdrop: the cover's own colours frosted -- whitened by day; at night
 * darkened but kept rich (saturation up, near-black wash) rather than a flat gray scrim. With no
 * cover nothing is drawn, so the page behind (glassSky: the launcher's night sky) shows through.
 */
@Composable
fun GlassCoverBackground(url: String?, g: GlassPalette) {
    val context = LocalContext.current
    val wash = lerp(g.bgBase, g.bgBottom, g.night)
    Crossfade(targetState = url, animationSpec = tween(900), label = "glass-cover-bg") { u ->
        if (u != null) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(u).size(24).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f + 0.5f * g.night) }),
                    modifier = Modifier.fillMaxSize().blur(24.dp),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(wash.copy(alpha = 0.50f - 0.12f * g.night), wash.copy(alpha = 0.74f - 0.14f * g.night)))
                    )
                )
            }
        }
    }
}

/** The screensaver for whatever [viewModel]'s room is playing (Music screen and the guide). */
@Composable
fun MusicAmbientNow(viewModel: MusicViewModel, sleepingSoon: Boolean = false) {
    val ui by viewModel.ui.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(ui.isPlaying) {
        while (ui.isPlaying) { tick++; delay(500) }
    }
    val curIdx = ui.queue?.current?.let { cur ->
        ui.queueItems.indexOfFirst { it.id == cur.id }.takeIf { it >= 0 }
    } ?: ui.queue?.currentIndex
    val artistUri = ui.queue?.current?.artistUri
    val artistInfo by produceState<MusicAssistantRepository.ArtistInfo?>(null, artistUri) {
        value = artistUri?.let { runCatching { viewModel.artistInfo(it) }.getOrNull() }
    }
    MusicAmbient(
        current = ui.queue?.current,
        next = curIdx?.let { ui.queueItems.getOrNull(it + 1) },
        title = ui.queue?.current?.name ?: ui.selectedPlayer?.nowTitle,
        artist = ui.queue?.current?.artist ?: ui.selectedPlayer?.nowArtist,
        room = ui.selectedPlayer?.name,
        imageUrl = ui.queue?.current?.imageUrl ?: ui.selectedPlayer?.nowImageUrl,
        elapsedSec = remember(tick, ui.elapsedSec, ui.elapsedAtMs, ui.isPlaying) { viewModel.currentElapsed() },
        artistInfo = artistInfo,
        sleepingSoon = sleepingSoon,
    )
}

/**
 * Music screensaver, an information clock (Joe, 2026-11-22: "a weather clock, quotes, what's
 * playing and coming, maybe some info about the song, artist or genre"; not artist photos, not
 * big album art). Top: a large clock and the date, the weather and room opposite. Middle: a small
 * cover, the song in big type, album · year · genres, a one-line who-the-artist-is, progress and
 * up next. Bottom: the Stoic quote. Behind it the cover's colours as a slow wash, dimmer and warmer
 * at night. Always dark and high-contrast (Patio TV in daylight, 2026-11-20). No lyrics (too busy).
 * The layout drifts a few dp every 30s against OLED burn-in. Any key or tap wakes the Music screen.
 */
@Composable
fun MusicAmbient(
    current: MaQueueItem?,
    next: MaQueueItem?,
    title: String?,
    artist: String?,
    room: String?,
    imageUrl: String?,
    elapsedSec: Double,
    artistInfo: MusicAssistantRepository.ArtistInfo? = null,
    sleepingSoon: Boolean = false,
) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AmbientBackdrop(imageUrl)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val narrow = maxWidth < 600.dp
            var drift by remember { mutableStateOf(0f to 0f) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(30_000)
                    drift = (Random.nextFloat() * 2 - 1) to (Random.nextFloat() * 2 - 1)
                }
            }
            val dx by animateDpAsState(20.dp * drift.first, tween(8_000), label = "drift-x")
            val dy by animateDpAsState(14.dp * drift.second, tween(8_000), label = "drift-y")

            val now by produceState(System.currentTimeMillis()) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(15_000)
                }
            }
            val clock = remember(now) {
                // The clock alone, big; AM/PM rides along small beside it.
                val f = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now))
                f.substringBefore(' ') to f.substringAfter(' ', "")
            }
            val date = remember(now) { java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date(now)) }
            val weather by produceState<AmbientWeather?>(null) {
                while (true) {
                    AmbientWeather.fetch()?.let { value = it }
                    delay(if (value == null) 2 * 60_000L else 15 * 60_000L)
                }
            }

            val white = Color.White
            val soft = white.copy(alpha = 0.78f)
            Column(
                Modifier.fillMaxSize().offset(dx, dy)
                    .padding(horizontal = if (narrow) 20.dp else 64.dp, vertical = if (narrow) 20.dp else 28.dp),
            ) {
                // ── Top: clock + date | weather + room
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(clock.first, color = white, fontSize = if (narrow) 48.sp else 80.sp,
                                fontWeight = FontWeight.Light, lineHeight = if (narrow) 50.sp else 80.sp)
                            if (clock.second.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(clock.second, color = soft, fontSize = if (narrow) 18.sp else 28.sp,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = if (narrow) 6.dp else 12.dp))
                            }
                        }
                        Text(date, color = soft, fontSize = if (narrow) 17.sp else 24.sp, fontWeight = FontWeight.Medium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        weather?.let { w ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(w.icon, fontSize = if (narrow) 30.sp else 52.sp)
                                Spacer(Modifier.width(12.dp))
                                Text("${w.now}°", color = white, fontSize = if (narrow) 40.sp else 68.sp, fontWeight = FontWeight.Light)
                            }
                            Text(listOfNotNull(w.summary, "H ${w.high}° · L ${w.low}°", w.rain?.let { "$it% rain" }).joinToString("  ·  "),
                                color = soft, fontSize = if (narrow) 15.sp else 22.sp, fontWeight = FontWeight.Medium)
                        }
                        room?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = white.copy(alpha = 0.6f), fontSize = if (narrow) 14.sp else 20.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // ── Middle: the song
                Spacer(Modifier.height(if (narrow) 8.dp else 12.dp))
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!narrow) {
                            SmallCover(imageUrl, 160.dp)
                            Spacer(Modifier.width(40.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            FitTitle(title ?: "Music", TextAlign.Start, if (narrow) 30 else 48, if (narrow) 22 else 32)
                            artist?.let {
                                Text(it, color = white.copy(alpha = 0.92f), fontSize = if (narrow) 20.sp else 30.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val about = listOfNotNull(current?.album, current?.year?.toString(),
                                artistInfo?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString("  ·  ")
                            if (about.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(about, color = soft, fontSize = if (narrow) 15.sp else 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val who = listOfNotNull(artistInfo?.blurb, artistInfo?.mood?.let { "$it mood" }).joinToString("  ·  ")
                            if (who.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(who, color = white.copy(alpha = 0.6f), fontSize = if (narrow) 14.sp else 20.sp,
                                    fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val duration = current?.durationSec ?: 0
                            if (duration > 0) {
                                Spacer(Modifier.height(if (narrow) 12.dp else 14.dp))
                                AmbientProgress(elapsedSec, duration)
                            }
                            next?.takeIf { it.name.isNotBlank() }?.let {
                                Spacer(Modifier.height(if (narrow) 8.dp else 10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("UP NEXT", color = white.copy(alpha = 0.55f), fontSize = if (narrow) 12.sp else 15.sp,
                                        fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                                    Spacer(Modifier.width(14.dp))
                                    Text(listOfNotNull(it.name, it.artist).joinToString(" · "), color = white.copy(alpha = 0.85f),
                                        fontSize = if (narrow) 16.sp else 22.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // ── Bottom: the Stoic quote, centred under a short rule, in a serif
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(if (narrow) 1f else 0.8f)) {
                        Box(Modifier.width(56.dp).height(1.dp).background(white.copy(alpha = 0.35f)))
                        Spacer(Modifier.height(if (narrow) 10.dp else 10.dp))
                        AmbientQuote(Modifier.fillMaxWidth(), align = TextAlign.Center, fontSize = if (narrow) 17 else 22)
                    }
                }
                if (sleepingSoon) {
                    Spacer(Modifier.height(8.dp))
                    Text("Screen turns off soon · press any button to keep it on",
                        color = white.copy(alpha = 0.85f), fontSize = 18.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * The screensaver's background: the cover's colours as a slow wash, following the time of day like
 * the Glass theme -- a touch brighter by day, dimmer with a faint amber cast at night -- under a
 * scrim that keeps every line readable in daylight.
 */
@Composable
private fun AmbientBackdrop(coverUrl: String?) {
    val night by produceState(SunClock.nightLevel(GlassMode.AUTO, System.currentTimeMillis())) {
        while (true) {
            delay(60_000)
            value = SunClock.nightLevel(GlassMode.AUTO, System.currentTimeMillis())
        }
    }
    CoverColourWash(coverUrl)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.66f + 0.14f * night)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.25f), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f))))
    if (night > 0f) Box(Modifier.fillMaxSize().background(Color(0xFF3A1E00).copy(alpha = 0.22f * night)))
}

/** Current weather for the screensaver, from Open-Meteo (free, no key) at the time zone's city. */
private data class AmbientWeather(val now: Int, val high: Int, val low: Int, val rain: Int?, val icon: String, val summary: String?) {
    companion object {
        suspend fun fetch(): AmbientWeather? = withContext(Dispatchers.IO) {
            runCatching {
                val (lat, lon) = SunClock.placeFor(java.util.TimeZone.getDefault())
                val f = java.util.Locale.getDefault().country in setOf("US", "LR", "MM")
                val url = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&forecast_days=1&timezone=auto" + if (f) "&temperature_unit=fahrenheit" else "")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000 }
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val cur = o.getJSONObject("current")
                val daily = o.getJSONObject("daily")
                val code = cur.optInt("weather_code")
                val day = cur.optInt("is_day", 1) == 1
                AmbientWeather(
                    now = Math.round(cur.getDouble("temperature_2m")).toInt(),
                    high = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                    low = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                    rain = daily.optJSONArray("precipitation_probability_max")?.optInt(0)?.takeIf { it >= 30 },
                    icon = when (code) {
                        0 -> if (day) "☀️" else "🌙"
                        1, 2 -> if (day) "🌤️" else "☁️"
                        3 -> "☁️"
                        45, 48 -> "🌫️"
                        in 51..67, in 80..82 -> "🌧️"
                        in 71..77, 85, 86 -> "🌨️"
                        in 95..99 -> "⛈️"
                        else -> "🌡️"
                    },
                    summary = when (code) {
                        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Cloudy"
                        45, 48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67, in 80..82 -> "Rain"
                        in 71..77, 85, 86 -> "Snow"; in 95..99 -> "Storms"; else -> null
                    },
                )
            }.getOrNull()
        }
    }
}

/**
 * The cover's colours as a soft, slowly moving wash: the cover decoded tiny (8px), stretched far
 * past the screen and drifted/turned over a minute and a half, so the colours shift without any
 * shape showing. Works on Android 11 (no Modifier.blur needed at this size).
 */
@Composable
private fun CoverColourWash(url: String?) {
    val context = LocalContext.current
    val drift = androidx.compose.animation.core.rememberInfiniteTransition(label = "wash")
    val a by drift.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        tween(90_000, easing = androidx.compose.animation.core.LinearEasing),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "wash-a")
    Crossfade(targetState = url, animationSpec = tween(1_500), label = "wash-cover") { u ->
        if (u != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(u).size(8).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.9f) }),
                modifier = Modifier.fillMaxSize().blur(24.dp).graphicsLayer {
                    scaleX = 2.2f; scaleY = 2.2f
                    rotationZ = -8f + 16f * a
                    translationX = size.width * 0.12f * (a - 0.5f)
                    translationY = size.height * 0.10f * (0.5f - a)
                },
            )
        }
    }
}

/**
 * The song title on one line, shrinking from [maxSp] towards [minSp] until it fits; only a title
 * still too long at [minSp] takes a second line. A two-line 58sp title pushed "Up next" off the
 * bottom of the words column on the 1080p TVs (Master Bedroom, Joe, 2026-11-22).
 */
@Composable
private fun FitTitle(text: String, align: TextAlign, maxSp: Int, minSp: Int) {
    var size by remember(text) { mutableStateOf(maxSp) }
    var settled by remember(text) { mutableStateOf(false) }
    val atMin = size <= minSp
    Text(text, color = Color.White, fontSize = size.sp, fontWeight = FontWeight.ExtraBold,
        maxLines = if (atMin) 2 else 1, softWrap = atMin, overflow = TextOverflow.Ellipsis, textAlign = align,
        lineHeight = (size + 6).sp,
        onTextLayout = { r ->
            if (!atMin && r.hasVisualOverflow) size = maxOf(minSp, size - 4) else settled = true
        },
        modifier = Modifier.drawWithContent { if (settled) drawContent() })
}

@Composable
private fun AmbientProgress(elapsedSec: Double, durationSec: Int) {
    val frac = (elapsedSec / durationSec).toFloat().coerceIn(0f, 1f)
    // Glass: a slightly thicker bar in solid ink over a ~28% ink track, and ink times, so they
    // hold up on the day look's near-white cover wash (Joe, 2026-10-26). At night ink is the
    // light glass ink, so it looks as before. Other themes unchanged.
    // The wash is always dark now (2026-11-20), so white on every theme, sized for across a room.
    val bar = 6.dp
    val shape = RoundedCornerShape(bar / 2)
    val fill = Color.White.copy(alpha = 0.92f)
    val rest = Color.White.copy(alpha = 0.25f)
    val timeColor = Color.White.copy(alpha = 0.82f)
    val timeSize = 18.sp
    val timeWeight = FontWeight.Medium
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(bar).clip(shape).background(rest)) {
            Box(Modifier.fillMaxWidth(frac).height(bar).clip(shape).background(fill))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(clockTime(elapsedSec), color = timeColor, fontSize = timeSize, fontWeight = timeWeight)
            Spacer(Modifier.weight(1f))
            Text(clockTime(durationSec.toDouble()), color = timeColor, fontSize = timeSize, fontWeight = timeWeight)
        }
    }
}

private fun clockTime(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The cover, small, with a faint edge and shadow so a dark cover doesn't melt into the wash. */
@Composable
private fun SmallCover(url: String?, size: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(size).shadow(24.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant)
        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(size / 3))
        }
    }
}
