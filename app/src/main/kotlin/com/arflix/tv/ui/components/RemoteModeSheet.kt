package com.arflix.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.data.repository.DPadKey
import com.arflix.tv.data.repository.LanPeer
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Remote Mode control sheet — pick a target Xadarr device on the LAN, or use its D-pad/text
 * popup once a target is active. Sending a channel tune or a play-title command happens at the
 * actual browse/tap site (LiveTvScreen/DetailsScreen), not here; this sheet only manages the
 * target and the D-pad/text popup. See RemoteModeRepository / RemoteCommandBus.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RemoteModeSheet(
    peers: List<LanPeer>,
    target: LanPeer?,
    onSelectTarget: (LanPeer?) -> Unit,
    onSendDpad: suspend (DPadKey) -> Boolean,
    onSendText: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = com.arflix.tv.ui.theme.BackgroundDark,
        contentColor = TextPrimary,
    ) {
        RemoteModeContent(peers, target, onSelectTarget, onSendDpad, onSendText)
    }
}

/**
 * Top-anchored variant for the global swipe-down entry point (see MainActivity's ArflixApp) —
 * a notification-shade-style panel: drops down from the top edge, dismissed by swiping up on
 * it or tapping the scrim, so it reads as "reveal/retract" rather than a bottom sheet (which
 * would conventionally dismiss by dragging it back down, the wrong direction for what was
 * asked: swipe down to open, swipe up to go back to browsing).
 */
@Composable
fun RemoteModeTopPanel(
    visible: Boolean,
    peers: List<LanPeer>,
    target: LanPeer?,
    onSelectTarget: (LanPeer?) -> Unit,
    onSendDpad: suspend (DPadKey) -> Boolean,
    onSendText: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { -it }) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { -it }) + androidx.compose.animation.fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(com.arflix.tv.ui.theme.BackgroundDark)
                    .statusBarsPadding()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -12f) onDismiss()
                        }
                    }
                    // Absorb taps on the panel itself so they don't fall through to the scrim's
                    // onDismiss above.
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .padding(top = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextSecondary.copy(alpha = 0.4f))
                )
                RemoteModeContent(peers, target, onSelectTarget, onSendDpad, onSendText)
            }
        }
    }
}

@Composable
private fun RemoteModeContent(
    peers: List<LanPeer>,
    target: LanPeer?,
    onSelectTarget: (LanPeer?) -> Unit,
    onSendDpad: suspend (DPadKey) -> Boolean,
    onSendText: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        androidx.tv.material3.Text(
                text = "Remote Mode",
                fontSize = 18.sp,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            androidx.tv.material3.Text(
                text = if (target != null) "Channel taps and title plays go to ${target.displayName}" else "Browsing plays locally on this device",
                fontSize = 13.sp,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(16.dp))

            RemoteDeviceRow(
                name = "This device (Local)",
                selected = target == null,
                icon = Icons.Default.Smartphone,
                onClick = { onSelectTarget(null) },
            )
            if (peers.isEmpty()) {
                androidx.tv.material3.Text(
                    text = "No other Xadarr devices found — enable LAN Sync on the target device too.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.height((peers.size * 56).coerceAtMost(280).dp)) {
                    items(peers) { peer ->
                        RemoteDeviceRow(
                            name = peer.displayName,
                            selected = target?.host == peer.host,
                            icon = Icons.Default.SettingsRemote,
                            onClick = { onSelectTarget(peer) },
                        )
                    }
                }
            }

            if (target != null) {
                Spacer(modifier = Modifier.height(20.dp))
                androidx.tv.material3.Text(text = "D-pad", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                RemoteDpadCross(onPress = { key -> scope.launch { onSendDpad(key) } })

                Spacer(modifier = Modifier.height(20.dp))
                androidx.tv.material3.Text(text = "Playback", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DpadButton(Icons.Default.FastRewind, "Rewind") { scope.launch { onSendDpad(DPadKey.REWIND) } }
                    DpadButton(Icons.Default.PlayArrow, "Play/Pause", filled = true) { scope.launch { onSendDpad(DPadKey.PLAY_PAUSE) } }
                    DpadButton(Icons.Default.Stop, "Stop") { scope.launch { onSendDpad(DPadKey.STOP) } }
                    DpadButton(Icons.Default.FastForward, "Fast forward") { scope.launch { onSendDpad(DPadKey.FAST_FORWARD) } }
                }
                Spacer(modifier = Modifier.height(14.dp))
                androidx.tv.material3.Text(text = "Volume", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DpadButton(Icons.Default.VolumeDown, "Volume down") { scope.launch { onSendDpad(DPadKey.VOLUME_DOWN) } }
                    DpadButton(Icons.Default.VolumeUp, "Volume up") { scope.launch { onSendDpad(DPadKey.VOLUME_UP) } }
                }

                Spacer(modifier = Modifier.height(20.dp))
                androidx.tv.material3.Text(text = "Type / search", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                var text by remember { mutableStateOf("") }
                var sending by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { androidx.tv.material3.Text("Search on ${target.displayName}…") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Pink,
                            unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                        ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        enabled = text.isNotBlank() && !sending,
                        onClick = {
                            val query = text
                            sending = true
                            scope.launch {
                                onSendText(query)
                                sending = false
                                text = ""
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Pink)
                    }
                }
            }
        }
    }

@Composable
private fun RemoteDeviceRow(name: String, selected: Boolean, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Pink else TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        androidx.tv.material3.Text(text = name, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) Pink else TextSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RemoteDpadCross(onPress: (DPadKey) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DpadButton(Icons.Default.KeyboardArrowUp, "Up") { onPress(DPadKey.UP) }
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            DpadButton(Icons.Default.KeyboardArrowLeft, "Left") { onPress(DPadKey.LEFT) }
            DpadButton(Icons.Default.RadioButtonUnchecked, "OK", filled = true) { onPress(DPadKey.CENTER) }
            DpadButton(Icons.Default.KeyboardArrowRight, "Right") { onPress(DPadKey.RIGHT) }
        }
        DpadButton(Icons.Default.KeyboardArrowDown, "Down") { onPress(DPadKey.DOWN) }
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            onClick = { onPress(DPadKey.BACK) },
            shape = RoundedCornerShape(8.dp),
            color = BackgroundElevated,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                androidx.tv.material3.Text(text = "Back", fontSize = 13.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun DpadButton(icon: ImageVector, label: String, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(52.dp)
            .background(if (filled) Pink.copy(alpha = 0.25f) else BackgroundElevated, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (filled) Pink else TextPrimary)
    }
}
