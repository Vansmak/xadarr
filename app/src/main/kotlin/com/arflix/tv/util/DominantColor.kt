package com.arflix.tv.util

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Small in-memory cache so navigating back to a hero item already shown this
// session doesn't redecode + re-run Palette on it.
private val dominantColorCache = LruCache<String, Color>(64)

/**
 * Extracts an ambient accent color from [imageUrl] (typically the hero
 * backdrop) via a tiny downsampled decode + [Palette] pass, so the hero glow
 * feels like it belongs to that specific piece of artwork instead of a fixed
 * theme color. Falls back to [fallback] while loading, on failure, or when
 * [imageUrl] is blank.
 *
 * Runs on [Dispatchers.IO]; results are cached per URL for the process
 * lifetime since the extracted color never changes for a given image.
 */
@Composable
fun rememberDominantColor(imageUrl: String?, fallback: Color): State<Color> {
    val context = LocalContext.current
    val colorState = remember { mutableStateOf(fallback) }
    LaunchedEffect(imageUrl, fallback) {
        if (imageUrl.isNullOrBlank()) {
            colorState.value = fallback
            return@LaunchedEffect
        }
        dominantColorCache.get(imageUrl)?.let {
            colorState.value = it
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.IO) {
            runCatching { extractDominantColor(context, imageUrl) }.getOrNull()
        }
        val resolved = extracted ?: fallback
        if (extracted != null) dominantColorCache.put(imageUrl, resolved)
        colorState.value = resolved
    }
    return colorState
}

private suspend fun extractDominantColor(context: android.content.Context, imageUrl: String): Color? {
    // Tiny target size — only the coarse color distribution matters, and a
    // small decode keeps this cheap enough to run on every hero change.
    val request = ImageRequest.Builder(context)
        .data(imageUrl)
        .size(48, 27)
        .allowHardware(false) // need to read pixels off the bitmap directly
        .build()
    val result = context.imageLoader.execute(request)
    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    return vibrantColorFrom(bitmap)
}

/**
 * Hand-rolled stand-in for `Palette`'s "Vibrant" swatch: a saturation-weighted
 * average over the bitmap's pixels, so muted/gray/near-black/near-white
 * pixels contribute almost nothing and the result leans toward whatever
 * saturated color actually stands out in the artwork. Avoids pulling in
 * androidx.palette purely for this one small computation — that library drags
 * in the old androidx.legacy support-core-utils module (loader, documentfile,
 * localbroadcastmanager, print) that nothing else in the app uses, which on
 * an unminified debug build alone was worth ~10MB of APK size.
 */
private fun vibrantColorFrom(bitmap: Bitmap): Color? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    var rSum = 0.0
    var gSum = 0.0
    var bSum = 0.0
    var weightSum = 0.0
    val hsv = FloatArray(3)
    for (pixel in pixels) {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha < 64) continue
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val saturation = hsv[1]
        val value = hsv[2]
        val weight = (saturation * saturation) * value
        rSum += r * weight
        gSum += g * weight
        bSum += b * weight
        weightSum += weight
    }
    // Weight sum too low means the image is effectively grayscale — nothing
    // useful to extract, let the caller fall back to the theme color instead
    // of returning a washed-out near-white/gray "accent".
    if (weightSum < 1.0) return null
    return Color(
        red = (rSum / weightSum / 255.0).toFloat().coerceIn(0f, 1f),
        green = (gSum / weightSum / 255.0).toFloat().coerceIn(0f, 1f),
        blue = (bSum / weightSum / 255.0).toFloat().coerceIn(0f, 1f)
    )
}

/** Blends [tint] into black at [amount] (0f = pure black, 1f = pure tint). */
fun blackTintedWith(tint: Color, amount: Float): Color = lerp(Color.Black, tint, amount)
