package com.arflix.tv.ui.screens.tv.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.IptvProgram
import kotlinx.coroutines.delay

/**
 * Shown when a program cell is selected in [EpgGrid]'s timeline — title, air time, and
 * description, with a Watch action only when there's actually something playable (live now,
 * or within the channel's catchup window). Selecting a future program used to silently attempt
 * playback via a catchup URL that doesn't exist yet (Joe, 2026-08-14: "it should give me info
 * about that time, program"); this replaces that with an explicit info-first step.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgramInfoPopup(
    channel: EnrichedChannel,
    program: IptvProgram,
    nowMillis: Long,
    isReminderSet: Boolean = false,
    notificationsEnabled: Boolean = true,
    onToggleReminder: () -> Unit = {},
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isLive = nowMillis in program.startUtcMillis until program.endUtcMillis
    val isFuture = program.startUtcMillis > nowMillis
    val catchupEligible = !isFuture && !isLive &&
        effectiveCatchupDays(channel) > 0 &&
        program.startUtcMillis >= nowMillis - effectiveCatchupDays(channel) * 24L * 60L * 60_000L
    val canWatch = isLive || catchupEligible

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(6) { attempt ->
            delay(if (attempt == 0) 32L else 24L)
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(60f)
            .background(Color(0xB3000000))
            .focusable()
            .onPreviewKeyEvent { ev ->
                // Must swallow every KeyDown here, not just Back/Escape. Direction
                // keys that reach the focused button unconsumed (it only handles
                // Center/Enter) bubble past this popup and trigger Compose's default
                // focus-search — which lands on EpgGrid cells still composed (just
                // visually hidden behind zIndex 60), moving the guide's selection
                // while the popup stays stuck on top (Joe, 2026-08-17: "navigate is
                // behind it"). Center/Enter still pass through so the focused
                // button/row's own onKeyEvent below gets to act on them.
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Back, Key.Escape -> { onDismiss(); true }
                    Key.DirectionCenter, Key.Enter -> false
                    else -> true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(LiveColors.PanelRaised)
                .border(1.dp, LiveColors.Divider, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(
                text = channel.name,
                style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = program.title,
                style = LiveType.CellTitle.copy(color = LiveColors.Fg, fontSize = 20.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = formatTimeWindow(program),
                    style = LiveType.NumberMono.copy(color = LiveColors.Accent, fontSize = 14.sp),
                )
                if (isLive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(LiveColors.Accent.copy(alpha = 0.22f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("LIVE NOW", style = LiveType.Badge.copy(color = LiveColors.Accent, fontSize = 10.sp))
                    }
                }
                if (isFuture) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(LiveColors.Panel)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("UPCOMING", style = LiveType.Badge.copy(color = LiveColors.FgMute, fontSize = 10.sp))
                    }
                }
            }
            if (!program.description.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = program.description,
                    style = LiveType.CellTitle.copy(color = LiveColors.FgDim, fontSize = 13.sp),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (canWatch) {
                var focused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (focused) LiveColors.Accent else LiveColors.Panel)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.hasFocus }
                        .focusable()
                        .onKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown &&
                                (ev.key == Key.DirectionCenter || ev.key == Key.Enter)
                            ) {
                                onWatch(); true
                            } else false
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isLive) "Watch Live" else "Watch from Start",
                        style = LiveType.CellTitle.copy(
                            color = if (focused) LiveColors.PanelDeep else LiveColors.Fg,
                            fontSize = 15.sp,
                        ),
                    )
                }
            } else if (isFuture) {
                var focused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (focused) LiveColors.Accent else LiveColors.Panel)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.hasFocus }
                        .focusable()
                        .onKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown &&
                                (ev.key == Key.DirectionCenter || ev.key == Key.Enter)
                            ) {
                                onToggleReminder(); true
                            } else false
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isReminderSet) "Reminder set — select to cancel" else "Remind me",
                        style = LiveType.CellTitle.copy(
                            color = if (focused) LiveColors.PanelDeep else LiveColors.Fg,
                            fontSize = 15.sp,
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (notificationsEnabled) {
                    Text(
                        text = "Best-effort — notifies a few minutes before air if the app can wake up.",
                        style = LiveType.SectionTag.copy(color = LiveColors.FgMute, fontSize = 11.sp),
                    )
                } else {
                    Text(
                        text = "Notifications are off for Xadarr, so this won't actually notify you. " +
                            "Enable them in Android Settings → Apps → Xadarr → Notifications.",
                        style = LiveType.SectionTag.copy(color = LiveColors.Accent, fontSize = 11.sp),
                    )
                }
            } else {
                Text(
                    text = "Not available to watch.",
                    style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .focusable(),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "BACK to close",
                style = LiveType.SectionTag.copy(color = LiveColors.FgMute.copy(alpha = 0.6f)),
            )
        }
    }
}
