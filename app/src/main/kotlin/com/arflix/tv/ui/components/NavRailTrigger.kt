package com.arflix.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** Rail open/closed state for one screen. Reset to closed on each new composition of the screen. */
@Composable
fun rememberNavRailOpen(): MutableState<Boolean> = remember { mutableStateOf(false) }

/**
 * Call first, at the top of a screen's onPreviewKeyEvent, before any of the
 * screen's own zone/focus logic. Returns true if this event was consumed by
 * rail-open/rail-owns-input handling, in which case the screen's own key
 * handling for this event should be skipped entirely (`return true`/short-circuit).
 *
 * - Rail closed + LEFT pressed while [atLeftEdge] is true: opens the rail, consumed.
 * - Rail open: consumed unconditionally — NavRail (which holds focus once open,
 *   see NavRail's own FocusRequester) owns all input until it closes itself.
 * - Otherwise: not consumed, screen handles the event as normal.
 */
fun navRailPreviewKey(
    event: KeyEvent,
    isRailOpen: MutableState<Boolean>,
    atLeftEdge: Boolean,
): Boolean {
    if (isRailOpen.value) return true
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.DirectionLeft && atLeftEdge) {
        isRailOpen.value = true
        return true
    }
    return false
}
