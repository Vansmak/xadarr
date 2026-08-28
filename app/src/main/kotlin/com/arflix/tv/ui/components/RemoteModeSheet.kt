package com.arflix.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arflix.tv.data.repository.DPadKey
import com.arflix.tv.data.repository.LanPeer
import com.arflix.tv.data.repository.tvremote.RemoteCapability
import com.arflix.tv.data.repository.tvremote.RemoteDevice
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** A Home Assistant media_player that can serve as a room's volume target. */
data class HaSpeaker(val entityId: String, val name: String)

/**
 * Remote Mode control sheet — pick a target Xadarr device on the LAN, or use its D-pad/text
 * popup once a target is active. Sending a channel tune or a play-title command happens at the
 * actual browse/tap site (LiveTvScreen/DetailsScreen), not here; this sheet only manages the
 * target and the D-pad/text popup. See RemoteModeRepository / RemoteCommandBus.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RemoteModeSheet(
    devices: List<RemoteDevice>,
    device: RemoteDevice?,
    onSelectDevice: (RemoteDevice?) -> Unit,
    onSendDpad: suspend (DPadKey) -> Boolean,
    onSendText: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    isTvRemotePaired: Boolean = false,
    onPairTvRemote: (() -> Unit)? = null,
    onSendPower: (suspend () -> Boolean)? = null,
    speakers: List<HaSpeaker> = emptyList(),
    volumeEntityId: String? = null,
    onSelectVolumeEntity: ((String?) -> Unit)? = null,
    onSelectInput: ((String) -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = com.arflix.tv.ui.theme.BackgroundDark,
        contentColor = TextPrimary,
    ) {
        RemoteModeContent(devices, device, onSelectDevice, onSendDpad, onSendText, isTvRemotePaired, onPairTvRemote, onSendPower, speakers, volumeEntityId, onSelectVolumeEntity, onSelectInput)
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
    devices: List<RemoteDevice>,
    device: RemoteDevice?,
    onSelectDevice: (RemoteDevice?) -> Unit,
    onSendDpad: suspend (DPadKey) -> Boolean,
    onSendText: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
    isTvRemotePaired: Boolean = false,
    onPairTvRemote: (() -> Unit)? = null,
    onSendPower: (suspend () -> Boolean)? = null,
    speakers: List<HaSpeaker> = emptyList(),
    volumeEntityId: String? = null,
    onSelectVolumeEntity: ((String?) -> Unit)? = null,
    onSelectInput: ((String) -> Unit)? = null,
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
            val maxHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.82f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(com.arflix.tv.ui.theme.BackgroundDark)
                    .statusBarsPadding()
                    // Absorb taps on the panel itself so they don't fall through to the scrim's
                    // onDismiss above.
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
            ) {
                // Drag-to-dismiss lives only on the handle, not the whole panel — putting it on
                // the full column would eat every vertical drag before the scrollable content
                // below ever saw it, breaking scroll entirely (first cut of this panel had no
                // scrolling at all, which was the actual bug: Volume/Search were simply pushed
                // off the bottom of the screen with nothing to reveal them).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -12f) onDismiss()
                            }
                        }
                        .padding(top = 4.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TextSecondary.copy(alpha = 0.4f))
                    )
                }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    RemoteModeContent(devices, device, onSelectDevice, onSendDpad, onSendText, isTvRemotePaired, onPairTvRemote, onSendPower, speakers, volumeEntityId, onSelectVolumeEntity, onSelectInput)
                }
            }
        }
    }
}

@Composable
private fun RemoteModeContent(
    devices: List<RemoteDevice>,
    device: RemoteDevice?,
    onSelectDevice: (RemoteDevice?) -> Unit,
    onSendDpad: suspend (DPadKey) -> Boolean,
    onSendText: suspend (String) -> Boolean,
    isTvRemotePaired: Boolean = false,
    onPairTvRemote: (() -> Unit)? = null,
    onSendPower: (suspend () -> Boolean)? = null,
    speakers: List<HaSpeaker> = emptyList(),
    volumeEntityId: String? = null,
    onSelectVolumeEntity: ((String?) -> Unit)? = null,
    onSelectInput: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Pink.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.SettingsRemote, contentDescription = null, tint = Pink, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                androidx.tv.material3.Text(text = "Remote Mode", fontSize = 17.sp, color = TextPrimary)
                androidx.tv.material3.Text(
                    text = if (device != null) "Controlling ${device.displayName}" else "Browsing plays locally on this device",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
            }
        }

        // What the remote can do is decided entirely by the selected device: an LG TV gets a
        // D-pad and its inputs, a Sonos gets transport and volume and no D-pad. Volume is the one
        // control that stays separately mappable, because a streaming box often isn't in its own
        // room's audio path (see RemoteVolumeRouter).
        val caps = device?.capabilities.orEmpty()
        val powerAvailable = if (device?.isXadarr == true) isTvRemotePaired else device?.powerEntity != null

        RemoteSection(title = "DEVICE") {
            RemoteDevicePicker(devices = devices, selected = device, onSelect = onSelectDevice)
            if (device?.isXadarr == true && !isTvRemotePaired && onPairTvRemote != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onPairTvRemote)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.SettingsRemote, contentDescription = null, tint = Pink, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        androidx.tv.material3.Text(text = "Pair for full remote control", fontSize = 13.sp, color = Pink)
                        androidx.tv.material3.Text(
                            text = "One-time — enables power and real volume on this device",
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                    }
                }
            }
            if (RemoteCapability.VOLUME in caps && speakers.isNotEmpty()) {
                RemoteCapabilityPicker(
                    label = "Speaker",
                    icon = Icons.Default.VolumeUp,
                    devices = speakers,
                    selectedEntityId = volumeEntityId,
                    onSelect = { onSelectVolumeEntity?.invoke(it) },
                )
            }
        }

        if (device != null) {
            RemoteSection(title = "CONTROL") {
                // Volume rocker sits beside the ring rather than in its own section — that cost a
                // full section of height and pushed Search off-screen.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (RemoteCapability.VOLUME in caps) {
                        RemoteVolumeRocker(
                            onUp = { scope.launch { onSendDpad(DPadKey.VOLUME_UP) } },
                            onDown = { scope.launch { onSendDpad(DPadKey.VOLUME_DOWN) } },
                        )
                        if (RemoteCapability.DPAD in caps) Spacer(modifier = Modifier.width(20.dp))
                    }
                    if (RemoteCapability.DPAD in caps) {
                        RemoteDpadCross(
                            onPress = { key -> scope.launch { onSendDpad(key) } },
                            onPower = if (powerAvailable && onSendPower != null) {
                                { scope.launch { onSendPower() } }
                            } else null,
                        )
                    }
                }

                // Home/Menu/Exit — a TV's own settings menus can't be navigated without them.
                if (RemoteCapability.DPAD in caps) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        DpadButton(Icons.Default.Home, "Home") { scope.launch { onSendDpad(DPadKey.HOME) } }
                        DpadButton(Icons.Default.Menu, "Menu") { scope.launch { onSendDpad(DPadKey.MENU) } }
                        DpadButton(Icons.Default.Close, "Exit") { scope.launch { onSendDpad(DPadKey.EXIT) } }
                    }
                }

                // A device with no D-pad (a speaker) still needs its power button somewhere.
                if (powerAvailable && onSendPower != null && RemoteCapability.DPAD !in caps) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        DpadButton(Icons.Default.PowerSettingsNew, "Power") { scope.launch { onSendPower() } }
                    }
                }

                if (RemoteCapability.TRANSPORT in caps) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Divider(color = TextSecondary.copy(alpha = 0.12f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        DpadButton(Icons.Default.FastRewind, "Rewind") { scope.launch { onSendDpad(DPadKey.REWIND) } }
                        DpadButton(Icons.Default.PlayArrow, "Play/Pause", filled = true) { scope.launch { onSendDpad(DPadKey.PLAY_PAUSE) } }
                        DpadButton(Icons.Default.Stop, "Stop") { scope.launch { onSendDpad(DPadKey.STOP) } }
                        DpadButton(Icons.Default.FastForward, "Fast forward") { scope.launch { onSendDpad(DPadKey.FAST_FORWARD) } }
                    }
                }
            }

            if (RemoteCapability.INPUT in caps) {
                RemoteSection(title = "INPUT") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        device.sources.forEach { source ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .clickable { onSelectInput?.invoke(source) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            ) {
                                androidx.tv.material3.Text(text = source, fontSize = 13.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            }

            if (RemoteCapability.TEXT in caps) {
            RemoteSection(title = "TYPE / SEARCH") {
                var text by remember { mutableStateOf("") }
                var sending by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        placeholder = { androidx.tv.material3.Text("Search on ${device.displayName}…", fontSize = 14.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Pink,
                            unfocusedBorderColor = TextSecondary.copy(alpha = 0.25f),
                        ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (text.isNotBlank() && !sending) Pink.copy(alpha = 0.18f) else BackgroundElevated)
                            .clickable(enabled = text.isNotBlank() && !sending) {
                                val query = text
                                sending = true
                                scope.launch {
                                    onSendText(query)
                                    sending = false
                                    text = ""
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (text.isNotBlank() && !sending) Pink else TextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun RemoteSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BackgroundElevated.copy(alpha = 0.6f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.tv.material3.Text(
            text = title,
            fontSize = 11.sp,
            color = TextSecondary.copy(alpha = 0.7f),
            letterSpacing = 1.sp,
        )
        content()
    }
}

/**
 * Single collapsed box showing the current selection; tap opens a dropdown with the rest —
 * replaces a permanently-expanded list of device rows, which alone was eating close to a
 * quarter of the panel's height (Joe: "can[']t remote all fit without scrolling? make the
 * devices a one box select").
 */
/**
 * Points one capability (volume, power, input) at whichever device actually handles it for the
 * current target. "This device" keeps the old behaviour — send it to the box itself — which is
 * right when the TV's own speakers are the output and it owns its own power; anything else routes
 * that capability through Home Assistant.
 */
@Composable
private fun RemoteCapabilityPicker(
    label: String,
    icon: ImageVector,
    devices: List<HaSpeaker>,
    selectedEntityId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentName = devices.firstOrNull { it.entityId == selectedEntityId }?.name
        ?: "This device"
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(10.dp))
            androidx.tv.material3.Text(text = label, fontSize = 13.sp, color = TextSecondary)
            // Fixed gap + a weighted, ellipsised value: without this a long entity name collapsed
            // the spacer entirely and ran straight into the label ("Input[LG] webOS TV ...").
            Spacer(modifier = Modifier.width(12.dp))
            androidx.tv.material3.Text(
                text = currentName,
                fontSize = 13.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.86f).background(BackgroundElevated),
        ) {
            RemoteDeviceRow(
                name = "This device",
                selected = selectedEntityId == null,
                icon = Icons.Default.SettingsRemote,
                onClick = { onSelect(null); expanded = false },
            )
            devices.forEach { device ->
                RemoteDeviceRow(
                    name = device.name,
                    selected = selectedEntityId == device.entityId,
                    icon = icon,
                    onClick = { onSelect(device.entityId); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun RemoteDevicePicker(
    devices: List<RemoteDevice>,
    selected: RemoteDevice?,
    onSelect: (RemoteDevice?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentName = selected?.displayName ?: "This device (Local)"
    val currentIcon = if (selected == null) Icons.Default.Smartphone else Icons.Default.SettingsRemote
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(currentIcon, contentDescription = null, tint = Pink, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            androidx.tv.material3.Text(
                text = currentName,
                fontSize = 15.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .background(BackgroundElevated),
        ) {
            RemoteDeviceRow(
                name = "This device (Local)",
                selected = selected == null,
                icon = Icons.Default.Smartphone,
                onClick = { onSelect(null); expanded = false },
            )
            // Xadarr instances first — they're the ones you drive most of the time; TVs and
            // speakers are the occasional detour.
            devices.sortedBy { !it.isXadarr }.forEach { candidate ->
                RemoteDeviceRow(
                    name = candidate.displayName,
                    selected = selected?.id == candidate.id,
                    icon = if (candidate.isXadarr) Icons.Default.SettingsRemote else Icons.Default.VolumeUp,
                    onClick = { onSelect(candidate); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun RemoteDeviceRow(name: String, selected: Boolean, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Pink.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Pink else TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        androidx.tv.material3.Text(text = name, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Pink,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Single ring rather than four separate button "blobs" — matches the reference remotes Joe
 * pointed at (Unimote, the Shield's own app, GoogleTV): one continuous circle divided into
 * directional zones reads as a real D-pad, not a loose cluster of icons.
 */
@Composable
private fun RemoteDpadCross(onPress: (DPadKey) -> Unit, onPower: (() -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .size(172.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Pink.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DpadRingZone(Icons.Default.KeyboardArrowUp, "Up", Modifier.align(Alignment.TopCenter)) { onPress(DPadKey.UP) }
            DpadRingZone(Icons.Default.KeyboardArrowDown, "Down", Modifier.align(Alignment.BottomCenter)) { onPress(DPadKey.DOWN) }
            DpadRingZone(Icons.Default.KeyboardArrowLeft, "Left", Modifier.align(Alignment.CenterStart)) { onPress(DPadKey.LEFT) }
            DpadRingZone(Icons.Default.KeyboardArrowRight, "Right", Modifier.align(Alignment.CenterEnd)) { onPress(DPadKey.RIGHT) }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Pink)
                    .clickable { onPress(DPadKey.CENTER) },
                contentAlignment = Alignment.Center,
            ) {
                androidx.tv.material3.Text(text = "OK", fontSize = 14.sp, color = com.arflix.tv.ui.theme.BackgroundDark)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onPress(DPadKey.BACK) }
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(6.dp))
            androidx.tv.material3.Text(text = "Back", fontSize = 13.sp, color = TextSecondary)
        }
        if (onPower != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onPower)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Pink, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                androidx.tv.material3.Text(text = "Power", fontSize = 13.sp, color = Pink)
            }
        }
    }
}

@Composable
private fun DpadRingZone(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = TextPrimary, modifier = Modifier.size(22.dp))
    }
}

/**
 * Vertical rocker — a taller pill with +/- at the ends — instead of two separate round
 * buttons, matching the reference remotes' VOL/CH rocker style.
 */
@Composable
private fun RemoteVolumeRocker(onUp: () -> Unit, onDown: () -> Unit) {
    Column(
        modifier = Modifier
            .width(56.dp)
            .height(172.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color.White.copy(alpha = 0.05f)),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(onClick = onUp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = "Volume up", tint = TextPrimary, modifier = Modifier.size(22.dp))
        }
        androidx.tv.material3.Text(
            text = "VOL",
            fontSize = 11.sp,
            color = TextSecondary,
            letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(onClick = onDown),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Volume down", tint = TextPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun DpadButton(icon: ImageVector, label: String, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(if (filled) Pink.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (filled) Pink else TextPrimary, modifier = Modifier.size(22.dp))
    }
}
