package com.arflix.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.arflix.tv.ui.glass.glassOr
import com.arflix.tv.ui.glass.sheet
import com.arflix.tv.ui.glass.sheetRaised
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

// Glass (2026-10-25): these follow the glass look's day/night; the old values on other themes.
private val Panel: Color @Composable @ReadOnlyComposable get() = glassOr(Color(0xFF15171F)) { sheet }
private val RowBg: Color @Composable @ReadOnlyComposable get() = glassOr(Color(0xFF1E2130)) { sheetRaised }
private val Accent: Color @Composable @ReadOnlyComposable get() = glassOr(Color(0xFF4F7FB0)) { accent }
private val Fg: Color @Composable @ReadOnlyComposable get() = glassOr(Color(0xFFF5F5F8)) { ink }
private val FgDim: Color @Composable @ReadOnlyComposable get() = glassOr(Color(0xFFB5B6BE)) { dim }
private val FocusRing: Color @Composable @ReadOnlyComposable get() = glassOr(Color.White) { ink }
private val NewTag = Color(0xFFE8A33D)

/**
 * Settings → Live TV → Channel groups: TiviMate-style checklist of every playlist group.
 * Ticked = shown in the guide. The filter narrows the list, and Show all / Hide all act on what
 * the filter shows -- type "UK" then Hide all. Same per-device hidden/NEW state the guide's own
 * Show/Hide uses.
 */
@Composable
fun ChannelGroupsDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.loadChannelGroups() }
    val groups by viewModel.channelGroups.collectAsState()
    var filter by remember { mutableStateOf("") }
    val visible = remember(groups, filter) {
        val q = filter.trim()
        if (q.isEmpty()) groups else groups.filter { it.name.contains(q, ignoreCase = true) }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(6) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50L)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xCC000000)).padding(12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Panel)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Channel groups", color = Fg, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    val shown = groups.count { !it.hidden }
                    Text("$shown of ${groups.size} shown", color = FgDim, fontSize = 14.sp)
                }
                FilterField(filter, { filter = it })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Show all", Modifier.focusRequester(firstFocus)) {
                        viewModel.setChannelGroupsHidden(visible.map { it.name }, hidden = false)
                    }
                    PillButton("Hide all") {
                        viewModel.setChannelGroupsHidden(visible.map { it.name }, hidden = true)
                    }
                    Spacer(Modifier.weight(1f))
                    PillButton("Done") { onDismiss() }
                }
                if (groups.isEmpty()) {
                    Text("No channel list loaded yet -- open Live TV once, then come back.", color = FgDim, fontSize = 14.sp)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(visible, key = { it.name }) { g ->
                        GroupRow(g) { viewModel.setChannelGroupsHidden(listOf(g.name), hidden = !g.hidden) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterField(value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        cursorBrush = SolidColor(Accent),
        textStyle = TextStyle(color = Fg, fontSize = 16.sp),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 1.dp, if (focused) FocusRing else glassOr(Color(0x33FFFFFF)) { chip }, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text("Filter groups (e.g. UK, Sports, 24/7)", color = FgDim, fontSize = 16.sp)
            inner()
        },
    )
}

@Composable
private fun PillButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Accent else RowBg)
            .border(if (focused) 2.dp else 0.dp, if (focused) FocusRing else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, color = Fg, fontSize = 15.sp)
    }
}

@Composable
private fun GroupRow(group: SettingsViewModel.ChannelGroupRow, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Accent.copy(alpha = 0.35f) else RowBg)
            .border(if (focused) 2.dp else 0.dp, if (focused) FocusRing else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (group.hidden) Icons.Filled.CheckBoxOutlineBlank else Icons.Filled.CheckBox,
            contentDescription = if (group.hidden) "Hidden" else "Shown",
            tint = if (group.hidden) FgDim else Accent,
            modifier = Modifier.size(22.dp),
        )
        Text(
            group.name,
            color = if (group.hidden) FgDim else Fg,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (group.isNew) Text("NEW", color = NewTag, fontSize = 12.sp)
        Text(group.count.toString(), color = FgDim, fontSize = 13.sp)
    }
}
