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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

private val Panel = Color(0xFF15171F)
private val RowBg = Color(0xFF1E2130)
private val Accent = Color(0xFF4F7FB0)
private val Fg = Color(0xFFF5F5F8)
private val FgDim = Color(0xFFB5B6BE)

/**
 * Settings → Live TV → Channels: every channel in the groups you show, in channel-number order,
 * ticked = listed in the guide (Joe, 2026-10-12: keep the DirecTV lineup authentic but drop the
 * channels he'd never watch -- DirecTV's custom guide, TiviMate's hide channel). Unticked
 * channels leave the group lists and channel surfing; Favorites, Recent, search and typing the
 * number still reach them. The filter matches a name, a group or the start of a number, and
 * Show all / Hide all act on what it shows -- type "4" for the 4000s, then Hide all.
 */
@Composable
fun ChannelsDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.loadChannels() }
    val channels by viewModel.channels.collectAsState()
    var filter by remember { mutableStateOf("") }
    // Genre buttons (Joe, 2026-10-12: "sports, news, movies in filter box?") -- quicker than
    // typing with a remote. They combine with the text filter.
    var genre by remember { mutableStateOf<String?>(null) }
    val genres = remember(channels) {
        listOf("Sports", "News", "Movies", "Kids", "Music", "Docs", "Other").filter { g -> channels.any { it.genre == g } }
    }
    val visible = remember(channels, filter, genre) {
        val q = filter.trim()
        channels.filter { ch ->
            (genre == null || ch.genre == genre) && (q.isEmpty() || ch.number.startsWith(q) ||
                ch.name.contains(q, ignoreCase = true) || ch.group.contains(q, ignoreCase = true) ||
                ch.genre.equals(q, ignoreCase = true))
        }
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
                    Text("Channels", color = Fg, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    val shown = channels.count { !it.hidden }
                    Text("$shown of ${channels.size} in the guide", color = FgDim, fontSize = 14.sp)
                }
                ChannelFilterField(filter, { filter = it })
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { ChannelsPill(if (genre == null) "• All" else "All") { genre = null } }
                    items(genres) { g -> ChannelsPill(if (genre == g) "• $g" else g) { genre = if (genre == g) null else g } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChannelsPill("Show all", Modifier.focusRequester(firstFocus)) {
                        viewModel.setChannelsHidden(visible.map { it.id }, hidden = false)
                    }
                    ChannelsPill("Hide all") {
                        viewModel.setChannelsHidden(visible.map { it.id }, hidden = true)
                    }
                    Spacer(Modifier.weight(1f))
                    ChannelsPill("Done") { onDismiss() }
                }
                if (channels.isEmpty()) {
                    Text("No channel list loaded yet -- open Live TV once, then come back.", color = FgDim, fontSize = 14.sp)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(visible, key = { it.id }) { ch ->
                        ChannelRowItem(ch) { viewModel.setChannelsHidden(listOf(ch.id), hidden = !ch.hidden) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelFilterField(value: String, onChange: (String) -> Unit) {
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
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text("Filter: name, group or number (e.g. 4 for the 4000s, STARZ)", color = FgDim, fontSize = 16.sp)
            inner()
        },
    )
}

@Composable
private fun ChannelsPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Accent else RowBg)
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, color = Fg, fontSize = 15.sp)
    }
}

@Composable
private fun ChannelRowItem(ch: SettingsViewModel.ChannelRow, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Accent.copy(alpha = 0.35f) else RowBg)
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (ch.hidden) Icons.Filled.CheckBoxOutlineBlank else Icons.Filled.CheckBox,
            contentDescription = if (ch.hidden) "Hidden" else "Shown",
            tint = if (ch.hidden) FgDim else Accent,
            modifier = Modifier.size(22.dp),
        )
        Text(
            ch.number,
            color = if (ch.hidden) FgDim else Fg,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.width(56.dp),
        )
        Text(
            ch.name,
            color = if (ch.hidden) FgDim else Fg,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(ch.group, color = FgDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp))
    }
}
