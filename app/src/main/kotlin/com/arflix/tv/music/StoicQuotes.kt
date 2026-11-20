package com.arflix.tv.music

import android.content.Context
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.json.JSONArray

/** How long each quote stays up on the screensavers. */
const val QUOTE_EVERY_MS = 75_000L

/**
 * Stoic quotes for the screensavers (Joe, 2026-11-20: calm, "composed under pressure"). One list
 * for every screen -- assets/stoic.json, seeded from ~/config/quotes/stoic.json on the Dell -- dealt
 * as a shuffled deck, so nothing repeats until the whole list has shown. The deck is shared by
 * House 3D and the music screensaver, so switching between them doesn't restart it either.
 */
object StoicQuotes {
    data class Quote(val text: String, val by: String)

    private var all: List<Quote>? = null
    private val deck = ArrayDeque<Quote>()
    private var last: Quote? = null

    @Synchronized
    fun next(context: Context): Quote? {
        val list = all ?: load(context).also { all = it }
        if (list.isEmpty()) return null
        if (deck.isEmpty()) {
            // A fresh shuffle mustn't open with the quote that just closed the last one.
            val shuffled = list.shuffled().toMutableList()
            if (shuffled.size > 1 && shuffled.first() == last) shuffled.add(shuffled.removeAt(0))
            deck.addAll(shuffled)
        }
        return deck.removeFirst().also { last = it }
    }

    private fun load(context: Context): List<Quote> = runCatching {
        val arr = JSONArray(context.assets.open("stoic.json").bufferedReader().use { it.readText() })
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            o.optString("text").trim().takeIf { it.isNotEmpty() }?.let { Quote(it, o.optString("by").trim()) }
        }
    }.getOrElse {
        Log.w("StoicQuotes", "no quotes: $it")
        emptyList()
    }
}

/** One quote at a time, small and dim, fading slowly to the next every [QUOTE_EVERY_MS]. */
@Composable
fun AmbientQuote(modifier: Modifier = Modifier, align: TextAlign = TextAlign.End, fontSize: Int = 17) {
    val context = LocalContext.current
    var quote by remember { mutableStateOf<StoicQuotes.Quote?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            quote = StoicQuotes.next(context)
            delay(QUOTE_EVERY_MS)
        }
    }
    val horizontal = when (align) {
        TextAlign.Center -> Alignment.CenterHorizontally
        TextAlign.Start -> Alignment.Start
        else -> Alignment.End
    }
    Crossfade(targetState = quote, animationSpec = tween(2_500), label = "quote", modifier = modifier) { q ->
        if (q != null) {
            Column(horizontalAlignment = horizontal) {
                Text("“${q.text}”", color = Color.White.copy(alpha = 0.85f), fontSize = fontSize.sp,
                    fontStyle = FontStyle.Italic, textAlign = align, lineHeight = (fontSize + 6).sp)
                if (q.by.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("— ${q.by}", color = Color.White.copy(alpha = 0.7f), fontSize = (fontSize - 3).sp, textAlign = align)
                }
            }
        }
    }
}
