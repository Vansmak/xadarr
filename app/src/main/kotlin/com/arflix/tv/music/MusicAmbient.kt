package com.arflix.tv.music

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.arflix.tv.ui.glass.LocalGlass
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
fun BlurredCoverBackground(url: String?, scrim: Float) {
    val context = LocalContext.current
    val bg = MaterialTheme.colorScheme.background
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
    MusicAmbient(
        current = ui.queue?.current,
        previous = curIdx?.let { ui.queueItems.getOrNull(it - 1) },
        next = curIdx?.let { ui.queueItems.getOrNull(it + 1) },
        title = ui.queue?.current?.name ?: ui.selectedPlayer?.nowTitle,
        artist = ui.queue?.current?.artist ?: ui.selectedPlayer?.nowArtist,
        room = ui.selectedPlayer?.name,
        imageUrl = ui.queue?.current?.imageUrl ?: ui.selectedPlayer?.nowImageUrl,
        elapsedSec = remember(tick, ui.elapsedSec, ui.elapsedAtMs, ui.isPlaying) { viewModel.currentElapsed() },
        lyrics = ui.lyrics,
        sleepingSoon = sleepingSoon,
    )
}

/**
 * Music screensaver (Joe, 2026-10-12; look after a r/homeassistant now-playing display):
 * a coverflow -- this song's cover flat and large, the previous and next covers angled behind it
 * with a faint reflection -- over a blurred, darkened wash of the cover, with clock and room on
 * top, the song in big type, a thin progress bar, and "Up next". Songs with synced lyrics
 * show just the line being sung and the next one under the progress bar -- full lyrics are the
 * Lyrics tab's job (Joe, 2026-10-12: "I like the cover flow look and just couple lines"). The whole layout drifts a few dp every 30s so nothing sits still long enough to burn
 * into an OLED. The Music screen wakes it on any key or tap.
 */
@Composable
fun MusicAmbient(
    current: MaQueueItem?,
    previous: MaQueueItem?,
    next: MaQueueItem?,
    title: String?,
    artist: String?,
    room: String?,
    imageUrl: String?,
    elapsedSec: Double,
    lyrics: MusicLyrics?,
    sleepingSoon: Boolean = false,
) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BlurredCoverBackground(imageUrl, scrim = 0.82f) // darker than Now Playing: a dark room at night (Joe, 2026-10-12)
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

            val clock by produceState(initialValue = "") {
                val fmt = android.text.format.DateFormat.getTimeFormat(context)
                while (true) {
                    value = fmt.format(java.util.Date())
                    delay(15_000)
                }
            }
            val coverSize = if (narrow) minOf(maxWidth * 0.62f, 260.dp) else minOf(maxHeight * 0.5f, 380.dp)
            val positionMs = (elapsedSec * 1000).toLong()

            val visual: @Composable (Modifier) -> Unit = { mod ->
                Box(mod, contentAlignment = Alignment.Center) {
                    CoverFlow(previous?.imageUrl, imageUrl, next?.imageUrl, coverSize)
                }
            }
            val words: @Composable (TextAlign) -> Unit = { align ->
                val horizontal = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
                Column(horizontalAlignment = horizontal, modifier = Modifier.fillMaxWidth()) {
                    Text(title ?: "Music", color = Color.White, fontSize = if (narrow) 28.sp else 44.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = align,
                        lineHeight = if (narrow) 32.sp else 50.sp)
                    artist?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = Color.White.copy(alpha = 0.82f), fontSize = if (narrow) 18.sp else 26.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
                    }
                    val albumLine = listOfNotNull(current?.album, current?.year?.toString()).joinToString(" · ")
                    if (albumLine.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(albumLine, color = Color.White.copy(alpha = 0.6f), fontSize = if (narrow) 15.sp else 19.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
                    }
                    val duration = current?.durationSec ?: 0
                    if (duration > 0) {
                        Spacer(Modifier.height(if (narrow) 16.dp else 24.dp))
                        AmbientProgress(elapsedSec, duration)
                    }
                    lyrics?.takeIf { it.synced }?.let { synced ->
                        val i = synced.currentIndex(positionMs)
                        val now = synced.lines.getOrNull(i)?.text?.takeIf { it.isNotBlank() }
                        val following = synced.lines.getOrNull(i + 1)?.text?.takeIf { it.isNotBlank() }
                        if (now != null || following != null) {
                            Spacer(Modifier.height(if (narrow) 14.dp else 22.dp))
                            Crossfade(targetState = now to following, animationSpec = tween(400), label = "ambient-lyric") { (line, upcoming) ->
                                Column(horizontalAlignment = horizontal, modifier = Modifier.fillMaxWidth()) {
                                    Text(line ?: "♪", color = Color.White, fontSize = if (narrow) 18.sp else 24.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = align)
                                    upcoming?.let {
                                        Spacer(Modifier.height(4.dp))
                                        Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = if (narrow) 15.sp else 19.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Column(
                Modifier.fillMaxSize().offset(dx, dy)
                    .padding(horizontal = if (narrow) 20.dp else 56.dp, vertical = if (narrow) 20.dp else 32.dp),
            ) {
                // Top line: clock · room
                Text(listOfNotNull(clock.ifBlank { null }, room).joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.7f), fontSize = if (narrow) 15.sp else 20.sp, maxLines = 1)
                if (narrow) {
                    visual(Modifier.fillMaxWidth().weight(1f))
                    words(TextAlign.Center)
                } else {
                    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        visual(Modifier.weight(1.1f).fillMaxHeight())
                        Spacer(Modifier.width(48.dp))
                        Box(Modifier.weight(1f)) { words(TextAlign.Start) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                next?.let {
                    // Glass: ink, not light gray -- unreadable over the day look's near-white
                    // cover wash (Joe, 2026-10-26). Other themes unchanged.
                    val g = LocalGlass.current
                    Text("Up next: " + listOfNotNull(it.name, it.artist).joinToString(" · "),
                        color = g?.ink?.copy(alpha = 0.8f) ?: Color.White.copy(alpha = 0.6f),
                        fontSize = if (g != null) (if (narrow) 15.sp else 19.sp) else if (narrow) 14.sp else 18.sp,
                        fontWeight = if (g != null) FontWeight.Medium else null,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = if (narrow) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
                }
                if (sleepingSoon) {
                    Spacer(Modifier.height(8.dp))
                    Text("Screen turns off soon · press any button to keep it on",
                        color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp,
                        textAlign = if (narrow) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AmbientProgress(elapsedSec: Double, durationSec: Int) {
    val frac = (elapsedSec / durationSec).toFloat().coerceIn(0f, 1f)
    // Glass: a slightly thicker bar in solid ink over a ~28% ink track, and ink times, so they
    // hold up on the day look's near-white cover wash (Joe, 2026-10-26). At night ink is the
    // light glass ink, so it looks as before. Other themes unchanged.
    val g = LocalGlass.current
    val bar = if (g != null) 5.dp else 4.dp
    val shape = RoundedCornerShape(bar / 2)
    val fill = g?.ink ?: Color.White.copy(alpha = 0.85f)
    val rest = g?.ink?.copy(alpha = 0.28f) ?: Color.White.copy(alpha = 0.2f)
    val timeColor = g?.ink?.copy(alpha = 0.8f) ?: Color.White.copy(alpha = 0.6f)
    val timeSize = if (g != null) 15.sp else 14.sp
    val timeWeight = if (g != null) FontWeight.Medium else null
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

/** Previous and next covers angled behind the current one, each with a faint reflection. */
@Composable
private fun CoverFlow(previousUrl: String?, currentUrl: String?, nextUrl: String?, size: Dp) {
    Box(contentAlignment = Alignment.TopCenter) {
        listOf(-1 to previousUrl, 1 to nextUrl).forEach { (side, url) ->
            if (url != null) {
                Box(
                    Modifier.graphicsLayer {
                        translationX = side * size.toPx() * 0.62f
                        translationY = size.toPx() * 0.08f
                        rotationY = -side * 48f
                        scaleX = 0.78f
                        scaleY = 0.78f
                        alpha = 0.55f
                        cameraDistance = 14f * density
                    }
                ) { ReflectedCover(url, size) }
            }
        }
        ReflectedCover(currentUrl, size)
    }
}

@Composable
private fun ReflectedCover(url: String?, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val cover: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.size(size).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            if (url != null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(size / 3))
            }
        }
    }
    Column {
        cover(Modifier)
        Spacer(Modifier.height(4.dp))
        // Reflection: the bottom of the cover, flipped, fading out.
        Box(
            Modifier.size(size, size * 0.28f).clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent)),
                        blendMode = BlendMode.DstIn,
                    )
                }
        ) {
            cover(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { scaleY = -1f })
        }
    }
}
