package com.arflix.tv.ui.screens.tv.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import com.arflix.tv.ui.skin.LocalFocusBorderColorOverride
import kotlinx.coroutines.delay

/**
 * On-screen 0-9 pad for going to a channel by number on remotes with no number buttons
 * (Joe, 2026-10-11: "most remotes don't have num pad"). Opened by holding OK in fullscreen or
 * from "Go to channel" in the guide sidebar. Digits only tune on Go -- D-pad entry is too slow
 * for the number buttons' tune-after-a-pause.
 */
@Composable
fun ChannelKeypad(
    onTune: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var digits by remember { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }
    BackHandler { onDismiss() }
    // Same retry as SearchOverlay: the first request can land before the modifier is attached.
    LaunchedEffect(Unit) {
        repeat(6) {
            if (runCatching { firstKey.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40L)
        }
    }
    fun press(label: String) {
        when (label) {
            "⌫" -> digits = digits.dropLast(1)
            "Go" -> if (digits.isEmpty()) onDismiss() else onTune(digits)
            else -> if (digits.length < 5) digits += label
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(450f)
            .background(Color(0x99000000))
            // Number buttons still work while the pad is up.
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val d = digitForKey(ev.key) ?: return@onPreviewKeyEvent false
                press(d.toString())
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(LiveColors.PanelRaised)
                .border(1.dp, LiveColors.Divider, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "CH  " + digits.padEnd(3, '_'),
                style = LiveType.NumberMono.copy(color = LiveColors.Fg, fontSize = 30.sp),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("⌫", "0", "Go"),
            ).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { label ->
                        KeypadKey(
                            label = label,
                            focusRequester = if (label == "1") firstKey else null,
                            onPress = { press(label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(label: String, focusRequester: FocusRequester?, onPress: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ring = LocalFocusBorderColorOverride.current ?: LiveColors.FocusRing
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 56.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 1.dp, if (focused) ring else LiveColors.Divider, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) LiveColors.Accent else LiveColors.Panel)
            .focusable()
            .onKeyEvent { ev ->
                // Held OK (the press that opened the pad from fullscreen) repeats; ignore repeats.
                if (ev.type != KeyEventType.KeyDown || ev.nativeKeyEvent.repeatCount > 0) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onPress(); true }
                    else -> false
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onPress() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LiveType.NumberMono.copy(color = LiveColors.Fg, fontSize = 22.sp),
        )
    }
}
