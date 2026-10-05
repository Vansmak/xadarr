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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
const val MUSIC_AMBIENT_AFTER_MS = 120_000L

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
 * Screensaver for OLED TVs: after a couple of idle minutes with music playing, just the cover
 * and song over a dim blurred backdrop, gliding to a new spot every 20s so nothing stays put
 * long enough to burn in. The Music screen wakes it on any key or tap.
 */
@Composable
fun MusicAmbient(
    title: String?,
    artist: String?,
    room: String?,
    imageUrl: String?,
    next: MaQueueItem? = null,
    sleepingSoon: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BlurredCoverBackground(imageUrl, scrim = 0.6f)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val narrow = maxWidth < 600.dp
            val cover = if (narrow) 160.dp else 220.dp
            val cardW = if (narrow) maxWidth * 0.85f else minOf(680.dp, maxWidth * 0.7f)
            val cardH = if (narrow) cover + 140.dp else cover
            var spot by remember { mutableStateOf(Random.nextFloat() to Random.nextFloat()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(20_000)
                    spot = Random.nextFloat() to Random.nextFloat()
                }
            }
            val x by animateDpAsState((maxWidth - cardW).coerceAtLeast(0.dp) * spot.first, tween(6_000), label = "ambient-x")
            val y by animateDpAsState((maxHeight - cardH).coerceAtLeast(0.dp) * spot.second, tween(6_000), label = "ambient-y")
            val art: @Composable () -> Unit = {
                Box(
                    Modifier.size(cover).clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (imageUrl != null) {
                        AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(64.dp))
                    }
                }
            }
            val words: @Composable () -> Unit = {
                Column {
                    Text(title ?: "Music", color = Color.White.copy(alpha = 0.88f), fontSize = if (narrow) 22.sp else 30.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    artist?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = if (narrow) 16.sp else 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    room?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, maxLines = 1)
                    }
                    next?.let {
                        Spacer(Modifier.height(12.dp))
                        Text("Next: " + listOfNotNull(it.name, it.artist).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.6f), fontSize = if (narrow) 14.sp else 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (sleepingSoon) {
                        Spacer(Modifier.height(10.dp))
                        Text("Screen turns off soon · press any button to keep it on",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                }
            }
            Box(Modifier.offset(x, y).width(cardW)) {
                if (narrow) {
                    Column { art(); Spacer(Modifier.height(14.dp)); words() }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) { art(); Spacer(Modifier.width(28.dp)); words() }
                }
            }
        }
    }
}
