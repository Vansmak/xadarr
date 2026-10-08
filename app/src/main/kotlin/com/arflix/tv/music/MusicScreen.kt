package com.arflix.tv.music

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.music.MusicAssistantRepository.ConnectionState
import kotlinx.coroutines.delay

private enum class Area { TABS, CONTENT }

// Now Playing has two focusable rows: the seek bar (0) and the transport controls (1).
private const val ROW_SEEK = 0
private const val ROW_CONTROLS = 1
// Volume gets its own row under the transport controls: at the end of the controls row it ran
// off the right edge on TVs (Joe, 2026-10-05). Left/Right on it turn the room down/up.
private const val ROW_VOLUME = 2
/** The transport controls the remote moves across; volume lives on [ROW_VOLUME]. */
private val TRANSPORT = Control.entries.filter { it != Control.VOLUME_DOWN && it != Control.VOLUME_UP }

private enum class Control { SHUFFLE, PREVIOUS, PLAY_PAUSE, NEXT, REPEAT, RADIO, VOLUME_DOWN, VOLUME_UP }

private val TAB_LABELS = mapOf(
    MusicTab.NOW_PLAYING to "Now Playing",
    MusicTab.QUEUE to "Queue",
    MusicTab.ZONES to "Zones",
    MusicTab.BROWSE to "Library",
)

// Phones: four equal tabs with an icon over a short label -- the text-only row ran off the
// right edge and Library was half hidden behind a sideways scroll (Joe, 2026-10-08).
private val TAB_ICONS = mapOf(
    MusicTab.NOW_PLAYING to Icons.Default.MusicNote,
    MusicTab.QUEUE to Icons.Default.QueueMusic,
    MusicTab.ZONES to Icons.Default.SpeakerGroup,
    MusicTab.BROWSE to Icons.Default.LibraryMusic,
)
private val TAB_SHORT_LABELS = mapOf(
    MusicTab.NOW_PLAYING to "Playing",
    MusicTab.QUEUE to "Queue",
    MusicTab.ZONES to "Zones",
    MusicTab.BROWSE to "Library",
)

/**
 * TV front end for Music Assistant: a remote and display only. It never plays audio on this
 * device — every action is a command to MA, and the Sonos speakers do the playing.
 *
 * D-pad focus is tracked by index (like SmartHomeScreen) rather than Compose focus, so the
 * whole screen is driven from one key handler and can't lose focus to an off-screen item.
 */
@Composable
fun MusicScreen(
    viewModel: MusicViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsState()
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary

    var area by remember { mutableStateOf(Area.CONTENT) }
    var npRow by remember { mutableIntStateOf(ROW_CONTROLS) }
    var controlIndex by remember { mutableIntStateOf(TRANSPORT.indexOf(Control.PLAY_PAUSE)) }
    var listIndex by remember { mutableIntStateOf(0) }
    var showSearch by remember { mutableStateOf(false) }
    var showRoomPicker by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Ticks the progress bar between server updates while playing.
    var nowTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ui.isPlaying) {
        while (ui.isPlaying) { nowTick++; delay(500) }
    }

    // Screensaver (MusicAmbient): idle a couple of minutes with music playing. Any key or tap
    // wakes it, and that first press only wakes -- it doesn't also act on the screen.
    var lastInputAt by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    var ambient by remember { mutableStateOf(false) }
    var idleMs by remember { mutableLongStateOf(0L) }
    // Volume keys count as "someone's here" for keep-awake, without waking the screensaver.
    var lastVolumeAt by remember { mutableLongStateOf(0L) }
    var awakeIdleMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            val now = android.os.SystemClock.uptimeMillis()
            idleMs = now - lastInputAt
            awakeIdleMs = now - maxOf(lastInputAt, lastVolumeAt)
            ambient = ui.isPlaying && !showSearch && ui.busyPrompt == null && idleMs > MUSIC_AMBIENT_AFTER_MS
        }
    }
    KeepScreenOn(ui.isPlaying && awakeIdleMs < MUSIC_AWAKE_CHECK_MS + MUSIC_AWAKE_GRACE_MS)
    fun wake() {
        lastInputAt = android.os.SystemClock.uptimeMillis()
        idleMs = 0L
        awakeIdleMs = 0L
        ambient = false
    }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(showSearch) { if (!showSearch) runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(ui.tab) { listIndex = 0 }
    LaunchedEffect(listIndex, ui.tab) {
        if (ui.tab != MusicTab.NOW_PLAYING && listIndex >= 0) runCatching { listState.animateScrollToItem(listIndex) }
    }
    // Open the queue at the playing track.
    LaunchedEffect(ui.tab, ui.queueItems.size) {
        if (ui.tab == MusicTab.QUEUE) {
            val cur = ui.queue?.current?.id
            val idx = ui.queueItems.indexOfFirst { it.id == cur }
            if (idx >= 0) listIndex = idx
        }
    }

    val listSize = when (ui.tab) {
        MusicTab.NOW_PLAYING -> 0
        MusicTab.QUEUE -> ui.queueItems.size
        MusicTab.ZONES -> ui.players.size
        MusicTab.BROWSE -> ui.browse.items.size + 1 // +1 = the Search row at the top
    }

    fun activateControl(c: Control) = when (c) {
        Control.SHUFFLE -> viewModel.toggleShuffle()
        Control.PREVIOUS -> { viewModel.previous(); Unit }
        Control.PLAY_PAUSE -> viewModel.playPause()
        Control.NEXT -> { viewModel.next(); Unit }
        Control.REPEAT -> viewModel.cycleRepeat()
        Control.RADIO -> viewModel.startRadioFromCurrent()
        Control.VOLUME_DOWN -> viewModel.changeVolume(-3)
        Control.VOLUME_UP -> viewModel.changeVolume(3)
    }

    fun activateListItem(index: Int) {
        when (ui.tab) {
            MusicTab.QUEUE -> viewModel.playQueueIndex(index)
            MusicTab.ZONES -> ui.players.getOrNull(index)?.let {
                viewModel.selectPlayer(it.id)
                viewModel.setTab(MusicTab.NOW_PLAYING)
            }
            MusicTab.BROWSE -> if (index == 0) showSearch = true else ui.browse.items.getOrNull(index - 1)?.let { item ->
                if (item.isFolder) viewModel.openFolder(item) else viewModel.play(item)
            }
            MusicTab.NOW_PLAYING -> Unit
        }
    }

    fun handleBack() {
        when {
            ui.tab == MusicTab.BROWSE && area == Area.CONTENT && viewModel.browseBack() -> listIndex = 0
            ui.tab != MusicTab.NOW_PLAYING -> { viewModel.setTab(MusicTab.NOW_PLAYING); area = Area.CONTENT; npRow = ROW_CONTROLS }
            else -> onBack()
        }
    }

    BackHandler(enabled = !showSearch && !showRoomPicker) { handleBack() }
    if (showRoomPicker) {
        RoomPickerDialog(
            players = ui.players,
            selectedId = ui.selectedPlayerId,
            onPick = { id -> viewModel.selectPlayer(id); viewModel.setTab(MusicTab.NOW_PLAYING); showRoomPicker = false },
            onDismiss = { showRoomPicker = false },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .focusRequester(rootFocus)
            .focusable()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (!ambient) lastInputAt = android.os.SystemClock.uptimeMillis()
                    }
                }
            }
            .onPreviewKeyEvent { evt ->
                if (showSearch) return@onPreviewKeyEvent false
                // The phone's side buttons / the remote's volume keys drive the room's speaker,
                // not this device: Music never plays audio here (Joe, 2026-10-05).
                if (evt.key == Key.VolumeUp || evt.key == Key.VolumeDown) {
                    if (evt.type == KeyEventType.KeyDown) {
                        viewModel.changeVolume(if (evt.key == Key.VolumeUp) 2 else -2, announce = true)
                        lastVolumeAt = android.os.SystemClock.uptimeMillis()
                        awakeIdleMs = 0L
                    }
                    return@onPreviewKeyEvent true
                }
                if (ambient) {
                    if (evt.type == KeyEventType.KeyDown) wake()
                    return@onPreviewKeyEvent true
                }
                lastInputAt = android.os.SystemClock.uptimeMillis()
                // Consume both halves of Back here so the system BackHandler (kept for touch
                // gestures) doesn't fire a second time on key-up.
                if (evt.key == Key.Back || evt.key == Key.Escape) {
                    if (evt.type == KeyEventType.KeyDown) handleBack()
                    return@onPreviewKeyEvent true
                }
                if (evt.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val tabs = MusicTab.entries
                when (evt.key) {
                    // Remote media keys work from anywhere on the screen.
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { viewModel.playPause(); true }
                    Key.MediaNext -> { viewModel.next(); true }
                    Key.MediaPrevious -> { viewModel.previous(); true }
                    Key.MediaFastForward -> { viewModel.seekBy(15); true }
                    Key.MediaRewind -> { viewModel.seekBy(-15); true }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val d = if (evt.key == Key.DirectionRight) 1 else -1
                        when {
                            area == Area.TABS -> {
                                val next = (ui.tab.ordinal + d).coerceIn(0, tabs.size - 1)
                                viewModel.setTab(tabs[next])
                            }
                            ui.tab == MusicTab.NOW_PLAYING && npRow == ROW_SEEK -> viewModel.seekBy(10 * d)
                            ui.tab == MusicTab.NOW_PLAYING && npRow == ROW_VOLUME -> viewModel.changeVolume(2 * d)
                            ui.tab == MusicTab.NOW_PLAYING ->
                                controlIndex = (controlIndex + d).coerceIn(0, TRANSPORT.size - 1)
                            else -> Unit
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        when {
                            area == Area.TABS -> Unit
                            ui.tab == MusicTab.NOW_PLAYING -> when (npRow) {
                                ROW_VOLUME -> npRow = ROW_CONTROLS
                                ROW_CONTROLS -> npRow = ROW_SEEK
                                else -> area = Area.TABS
                            }
                            listIndex > 0 -> listIndex--
                            else -> area = Area.TABS
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        when {
                            area == Area.TABS -> { area = Area.CONTENT; npRow = ROW_CONTROLS }
                            ui.tab == MusicTab.NOW_PLAYING -> npRow = if (npRow == ROW_SEEK) ROW_CONTROLS else ROW_VOLUME
                            listIndex < listSize - 1 -> listIndex++
                            else -> Unit
                        }
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        when {
                            area == Area.TABS -> { area = Area.CONTENT; npRow = ROW_CONTROLS }
                            ui.tab == MusicTab.NOW_PLAYING ->
                                when (npRow) {
                                    ROW_CONTROLS -> activateControl(TRANSPORT[controlIndex])
                                    ROW_VOLUME -> Unit
                                    else -> viewModel.playPause()
                                }
                            else -> activateListItem(listIndex)
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        val coverUrl = ui.queue?.current?.imageUrl ?: ui.selectedPlayer?.nowImageUrl
        // Lighter than first shipped (0.78): Joe found it too subtle behind Now Playing.
        BlurredCoverBackground(coverUrl, scrim = 0.62f)
        // Phones: tighter margins, and Now Playing stacks vertically (see NowPlaying).
        val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 16.dp else 48.dp, vertical = if (narrow) 12.dp else 28.dp)) {
            // Phones: the room being controlled sits on its own row above the tabs and is
            // tappable -- it was the last item of a sideways-scrolling row, easy to never see,
            // and the Zones tab alone wasn't an obvious way to switch (Joe, 2026-10-03).
            if (narrow) {
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).clickable { showRoomPicker = true }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Speaker, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ui.selectedPlayer?.let { p -> p.name + if (p.isGroupLeader) " +${p.groupMembers.size - 1}" else "" } ?: "Pick a room",
                        color = colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("  ▾", color = colors.onSurfaceVariant, fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    ConnectionDot(ui.connection)
                }
                Spacer(Modifier.height(6.dp))
            }
            // ── Header: tabs + the zone being controlled ──
            Row(
                modifier = if (narrow) Modifier.fillMaxWidth() else Modifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MusicTab.entries.forEach { tab ->
                    val selected = ui.tab == tab
                    val focused = area == Area.TABS && selected
                    if (narrow) {
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 3.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable { viewModel.setTab(tab); area = Area.CONTENT }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val tint = if (selected) colors.onSurface else colors.onSurfaceVariant
                            Icon(TAB_ICONS.getValue(tab), contentDescription = null, tint = if (selected) accent else tint, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(TAB_SHORT_LABELS.getValue(tab), color = tint, fontSize = 12.sp, maxLines = 1,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                        return@forEach
                    }
                    Box(
                        Modifier
                            .padding(end = 10.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) accent.copy(alpha = if (focused) 0.35f else 0.18f) else Color.Transparent)
                            .border(2.dp, if (focused) accent else Color.Transparent, RoundedCornerShape(20.dp))
                            .clickable { viewModel.setTab(tab); area = Area.CONTENT }
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Text(TAB_LABELS.getValue(tab), color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                            fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                if (!narrow) {
                    Spacer(Modifier.weight(1f))
                    ui.selectedPlayer?.let { p ->
                        Icon(Icons.Default.Speaker, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(p.name, color = colors.onSurface, fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    ConnectionDot(ui.connection)
                }
            }
            Spacer(Modifier.height(if (narrow) 12.dp else 24.dp))

            Box(Modifier.fillMaxSize()) {
                when {
                    ui.connection != ConnectionState.CONNECTED && ui.players.isEmpty() -> ConnectionEmptyState(ui.connection)
                    // Connected but the first room list hasn't arrived: it said "No zones found"
                    // for a few seconds on every open (office TV, 2026-10-05).
                    ui.players.isEmpty() && !ui.playersLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent)
                    }
                    ui.players.isEmpty() -> EmptyState("No zones found", "Music Assistant didn't report any players.")
                    else -> when (ui.tab) {
                        MusicTab.NOW_PLAYING -> NowPlaying(
                            ui = ui,
                            elapsed = remember(nowTick, ui.elapsedSec, ui.elapsedAtMs, ui.isPlaying) { viewModel.currentElapsed() },
                            seekFocused = area == Area.CONTENT && npRow == ROW_SEEK,
                            focusedControl = if (area == Area.CONTENT && npRow == ROW_CONTROLS) TRANSPORT[controlIndex] else null,
                            volumeFocused = area == Area.CONTENT && npRow == ROW_VOLUME,
                            onControl = { c ->
                                TRANSPORT.indexOf(c).takeIf { it >= 0 }?.let { controlIndex = it; npRow = ROW_CONTROLS }
                                area = Area.CONTENT
                                activateControl(c)
                            },
                        )
                        MusicTab.QUEUE -> if (ui.queueItems.isEmpty()) {
                            EmptyState("The queue is empty", "Pick something in Library to start playing on ${ui.selectedPlayer?.name ?: "this zone"}.")
                        } else {
                            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                                itemsIndexed(ui.queueItems, key = { i, it -> "${it.id}#$i" }) { i, item ->
                                    MediaRow(
                                        title = item.name,
                                        subtitle = listOfNotNull(item.artist, item.album).joinToString(" · ").ifBlank { null },
                                        imageUrl = item.imageUrl,
                                        trailing = formatTime(item.durationSec.toDouble()),
                                        highlighted = item.id == ui.queue?.current?.id,
                                        focused = area == Area.CONTENT && listIndex == i,
                                        onClick = { listIndex = i; area = Area.CONTENT; activateListItem(i) },
                                    )
                                }
                            }
                        }
                        MusicTab.ZONES -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                            itemsIndexed(ui.players, key = { _, p -> p.id }) { i, p ->
                                MediaRow(
                                    title = p.name + if (p.isGroupLeader) "  +${p.groupMembers.size - 1}" else "",
                                    subtitle = when {
                                        p.isTvAudio -> "TV audio"
                                        p.nowTitle != null -> listOfNotNull(if (p.isPlaying) "Playing" else "Paused", p.nowTitle, p.nowArtist).joinToString(" · ")
                                        else -> "Idle"
                                    },
                                    imageUrl = p.nowImageUrl,
                                    fallbackIcon = Icons.Default.Speaker,
                                    trailing = p.effectiveVolume?.let { "Vol $it" },
                                    highlighted = p.id == ui.selectedPlayerId,
                                    focused = area == Area.CONTENT && listIndex == i,
                                    onClick = { listIndex = i; area = Area.CONTENT; activateListItem(i) },
                                )
                            }
                        }
                        MusicTab.BROWSE -> Column {
                            Text(ui.browse.title, color = colors.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                                item(key = "search") {
                                    MediaRow(
                                        title = if (ui.browse.query.isBlank()) "Search" else "Search: ${ui.browse.query}",
                                        subtitle = "Artists, albums, playlists, tracks, radio",
                                        imageUrl = null,
                                        fallbackIcon = Icons.Default.Search,
                                        focused = area == Area.CONTENT && listIndex == 0,
                                        onClick = { listIndex = 0; showSearch = true },
                                    )
                                }
                                if (ui.browse.loading) item(key = "loading") {
                                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = accent)
                                    }
                                }
                                ui.browse.error?.let { err ->
                                    item(key = "error") { Text(err, color = colors.onSurfaceVariant, fontSize = 15.sp, modifier = Modifier.padding(16.dp)) }
                                }
                                itemsIndexed(ui.browse.items, key = { i, it -> "${it.uri}|${it.browsePath}|$i" }) { i, item ->
                                    item.section?.let { title ->
                                        Text(title, color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp))
                                    }
                                    MediaRow(
                                        title = item.name,
                                        subtitle = item.subtitle ?: item.mediaType.replaceFirstChar { it.uppercase() },
                                        imageUrl = item.imageUrl,
                                        fallbackIcon = when {
                                            item.isFolder -> Icons.Default.Folder
                                            item.uri.endsWith("radio") -> Icons.Default.Radio
                                            item.uri.contains("seeall/") -> Icons.Default.ChevronRight
                                            item.isAction -> Icons.Default.Shuffle
                                            item.mediaType == "playlist" -> Icons.Default.QueueMusic
                                            else -> Icons.Default.Album
                                        },
                                        focused = area == Area.CONTENT && listIndex == i + 1,
                                        onClick = { listIndex = i + 1; area = Area.CONTENT; activateListItem(i + 1) },
                                    )
                                }
                            }
                        }
                    }
                }

                ui.message?.let { msg ->
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) { Text(msg, color = colors.onSurface, fontSize = 15.sp) }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = ambient,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(1_200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
        ) {
            Box(Modifier.fillMaxSize().clickable(indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { wake() }) {
                MusicAmbient(
                    title = ui.queue?.current?.name ?: ui.selectedPlayer?.nowTitle,
                    artist = ui.queue?.current?.artist ?: ui.selectedPlayer?.nowArtist,
                    room = ui.selectedPlayer?.name,
                    imageUrl = coverUrl,
                    next = ui.queue?.current?.let { cur ->
                        val i = ui.queueItems.indexOfFirst { it.id == cur.id }
                        ui.queueItems.getOrNull(if (i >= 0) i + 1 else (ui.queue?.currentIndex ?: -2) + 1)
                    },
                    sleepingSoon = awakeIdleMs > MUSIC_AWAKE_CHECK_MS,
                )
            }
        }

        ui.busyPrompt?.let { prompt ->
            BusyRoomDialog(prompt, onAnswer = { viewModel.resolveBusyPrompt(it) })
        }

        if (showSearch) {
            SearchDialog(
                initial = ui.browse.query,
                onSearch = { q -> showSearch = false; listIndex = 0; area = Area.CONTENT; viewModel.search(q) },
                onDismiss = { showSearch = false },
            )
        }
    }
}

// ── Now Playing ──────────────────────────────────────────────────────────────

@Composable
private fun NowPlaying(
    ui: MusicUiState,
    elapsed: Double,
    seekFocused: Boolean,
    focusedControl: Control?,
    volumeFocused: Boolean,
    onControl: (Control) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val player = ui.selectedPlayer
    val item = ui.queue?.current
    val title = item?.name ?: player?.nowTitle
    val artist = item?.artist ?: player?.nowArtist
    val image = item?.imageUrl ?: player?.nowImageUrl

    if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600) {
        NowPlayingNarrow(ui, elapsed, title, artist, image, onControl)
        return
    }

    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.fillMaxHeight(0.8f).aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(96.dp))
            }
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            if (title == null) {
                Text("Nothing playing", color = colors.onSurface, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Open Library to start something on ${player?.name ?: "a zone"}.", color = colors.onSurfaceVariant, fontSize = 18.sp)
            } else {
                Text(title, color = colors.onSurface, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                artist?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = colors.onSurface.copy(alpha = 0.85f), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                item?.album?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = colors.onSurfaceVariant, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                player?.let { Chip(it.name + if (it.isGroupLeader) " +${it.groupMembers.size - 1}" else "") }
                item?.quality?.let { Spacer(Modifier.width(8.dp)); Chip(it, highlight = true) }
            }

            Spacer(Modifier.height(28.dp))
            SeekBar(elapsed = elapsed, duration = item?.durationSec ?: 0, focused = seekFocused)
            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val q = ui.queue
                ControlButton(Icons.Default.Shuffle, "Shuffle", focusedControl == Control.SHUFFLE, active = q?.shuffle == true) { onControl(Control.SHUFFLE) }
                ControlButton(Icons.Default.SkipPrevious, "Previous", focusedControl == Control.PREVIOUS) { onControl(Control.PREVIOUS) }
                ControlButton(
                    if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (ui.isPlaying) "Pause" else "Play",
                    focusedControl == Control.PLAY_PAUSE, large = true,
                ) { onControl(Control.PLAY_PAUSE) }
                ControlButton(Icons.Default.SkipNext, "Next", focusedControl == Control.NEXT) { onControl(Control.NEXT) }
                ControlButton(
                    if (q?.repeat == "one") Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat",
                    focusedControl == Control.REPEAT, active = q != null && q.repeat != "off",
                ) { onControl(Control.REPEAT) }
                ControlButton(Icons.Default.Radio, "Radio from this song", focusedControl == Control.RADIO) { onControl(Control.RADIO) }
            }
            Spacer(Modifier.height(18.dp))
            VolumeBar(player, focused = volumeFocused, onControl = onControl, modifier = Modifier.widthIn(max = 460.dp))
        }
    }
}

/** Phone layout: cover on top, then title, chips, progress and two rows of controls. */
@Composable
private fun NowPlayingNarrow(
    ui: MusicUiState,
    elapsed: Double,
    title: String?,
    artist: String?,
    image: String?,
    onControl: (Control) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val player = ui.selectedPlayer
    val item = ui.queue?.current
    val q = ui.queue
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth(0.82f).aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(72.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title ?: "Nothing playing", color = colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        artist?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = colors.onSurface.copy(alpha = 0.85f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            player?.let { Chip(it.name + if (it.isGroupLeader) " +${it.groupMembers.size - 1}" else "") }
            item?.quality?.let { Spacer(Modifier.width(8.dp)); Chip(it, highlight = true) }
        }
        Spacer(Modifier.height(18.dp))
        SeekBar(elapsed = elapsed, duration = item?.durationSec ?: 0, focused = false)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(Icons.Default.Shuffle, "Shuffle", false, active = q?.shuffle == true) { onControl(Control.SHUFFLE) }
            ControlButton(Icons.Default.SkipPrevious, "Previous", false) { onControl(Control.PREVIOUS) }
            ControlButton(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (ui.isPlaying) "Pause" else "Play",
                false, large = true) { onControl(Control.PLAY_PAUSE) }
            ControlButton(Icons.Default.SkipNext, "Next", false) { onControl(Control.NEXT) }
            ControlButton(if (q?.repeat == "one") Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", false,
                active = q != null && q.repeat != "off") { onControl(Control.REPEAT) }
        }
        Spacer(Modifier.height(12.dp))
        VolumeBar(player, focused = false, onControl = onControl, modifier = Modifier.fillMaxWidth(0.9f))
        Spacer(Modifier.height(8.dp))
        ControlButton(Icons.Default.Radio, "Radio from this song", false) { onControl(Control.RADIO) }
        Spacer(Modifier.height(24.dp))
    }
}

/** The room's volume: − / level bar / +. On the remote, Left/Right turn it while the row is focused. */
@Composable
private fun VolumeBar(player: MaPlayer?, focused: Boolean, onControl: (Control) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val level = player?.effectiveVolume
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(Icons.Default.VolumeDown, "Volume down", false) { onControl(Control.VOLUME_DOWN) }
            Box(
                Modifier.weight(1f).height(if (focused) 10.dp else 6.dp).clip(RoundedCornerShape(5.dp))
                    .background(colors.onSurface.copy(alpha = 0.18f))
                    .border(if (focused) 2.dp else 0.dp, if (focused) accent else Color.Transparent, RoundedCornerShape(5.dp))
            ) {
                Box(Modifier.fillMaxWidth(((level ?: 0) / 100f).coerceIn(0f, 1f)).fillMaxHeight().background(accent))
            }
            Spacer(Modifier.width(12.dp))
            ControlButton(Icons.Default.VolumeUp, "Volume up", false) { onControl(Control.VOLUME_UP) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(listOfNotNull(player?.name, level?.let { "Volume $it" }).joinToString(" · "),
                color = colors.onSurfaceVariant, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (focused) Text("◀ ▶ volume", color = accent, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SeekBar(elapsed: Double, duration: Int, focused: Boolean) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val fraction = if (duration > 0) (elapsed / duration).toFloat().coerceIn(0f, 1f) else 0f
    Column {
        Box(
            Modifier.fillMaxWidth().height(if (focused) 10.dp else 6.dp).clip(RoundedCornerShape(5.dp))
                .background(colors.onSurface.copy(alpha = 0.18f))
                .border(if (focused) 2.dp else 0.dp, if (focused) accent else Color.Transparent, RoundedCornerShape(5.dp))
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent))
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(formatTime(elapsed), color = colors.onSurfaceVariant, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (focused) Text("◀ ▶ seek 10s", color = accent, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) formatTime(duration.toDouble()) else "--:--", color = colors.onSurfaceVariant, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    focused: Boolean,
    active: Boolean = false,
    large: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val size = if (large) 72.dp else 54.dp
    Box(
        Modifier.padding(end = 12.dp).size(size).clip(CircleShape)
            .background(
                when {
                    focused -> accent
                    large -> colors.onSurface.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = label,
            tint = when {
                focused -> colors.onPrimary
                active -> accent
                else -> colors.onSurface
            },
            modifier = Modifier.size(if (large) 40.dp else 30.dp),
        )
    }
}

@Composable
private fun Chip(text: String, highlight: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (highlight) colors.primary.copy(alpha = 0.22f) else colors.onSurface.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = if (highlight) colors.primary else colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Lists ────────────────────────────────────────────────────────────────────

@Composable
private fun MediaRow(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    trailing: String? = null,
    highlighted: Boolean = false,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> accent.copy(alpha = 0.22f)
                    highlighted -> colors.onSurface.copy(alpha = 0.07f)
                    else -> Color.Transparent
                }
            )
            .border(2.dp, if (focused) accent else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(fallbackIcon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (highlighted) accent else colors.onSurface, fontSize = 18.sp,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, color = colors.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, color = colors.onSurfaceVariant, fontSize = 14.sp)
        }
    }
}

// ── Empty / status states ────────────────────────────────────────────────────

@Composable
private fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF34D399)
        ConnectionState.CONNECTING -> Color(0xFFFBBF24)
        else -> Color(0xFFF87171)
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun ConnectionEmptyState(state: ConnectionState) {
    when (state) {
        ConnectionState.CONNECTING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        ConnectionState.NOT_CONFIGURED -> EmptyState(
            "Music Assistant isn't set up",
            "Settings → Plugins & Extensions → Music Assistant: enter the server and log in.",
        )
        ConnectionState.AUTH_FAILED -> EmptyState(
            "Music Assistant login needed",
            "The saved login was rejected. Log in again in Settings → Plugins & Extensions → Music Assistant.",
        )
        ConnectionState.OFFLINE, ConnectionState.CONNECTED -> EmptyState(
            "Can't reach Music Assistant",
            "Retrying automatically. Check that the server is running.",
        )
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.MusicNote, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = colors.onSurfaceVariant, fontSize = 16.sp)
    }
}

// ── Search ───────────────────────────────────────────────────────────────────

@Composable
private fun SearchDialog(initial: String, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { fieldFocus.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(24.dp)
        ) {
            Text("Search music", color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { androidx.compose.material3.Text("Artist, album, playlist, song…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    focusedBorderColor = colors.primary,
                    cursorColor = colors.primary,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Text("Press the keyboard's search key to search, Back to cancel.", color = colors.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

private fun formatTime(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Another room has MA's one Spotify stream. Play here (stops it), play in both rooms (joins
 * its group), or cancel. Plain focusable rows so it works on a remote and on touch.
 */
@Composable
private fun BusyRoomDialog(prompt: MusicBusyPrompt, onAnswer: (Boolean?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rooms = prompt.playing.joinToString(", ") { it.name }
    val first = prompt.playing.first()
    val options = listOfNotNull(
        false to "Play on ${prompt.target.name} instead · stops $rooms",
        (true to "Play in both rooms · ${prompt.target.name} joins ${first.name}").takeIf { prompt.playing.size == 1 },
        null to "Cancel",
    )
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { firstFocus.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = { onAnswer(null) }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(vertical = 12.dp),
        ) {
            Text("Music is playing in $rooms", color = colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Text(listOfNotNull(first.nowTitle?.let { "\"$it\"" }, "Spotify plays in one room at a time").joinToString(" · "),
                color = colors.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
            options.forEachIndexed { i, (answer, label) ->
                var focused by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .background(if (focused) colors.primary.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { onAnswer(answer) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (answer == null) Icons.Default.Close else Icons.Default.Speaker, contentDescription = null,
                        tint = if (focused) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(label, color = colors.onSurface, fontSize = 16.sp)
                }
            }
        }
    }
}

/** Phone: pick the room to control. TV-audio rooms are labelled so, not as music. */
@Composable
private fun RoomPickerDialog(players: List<MaPlayer>, selectedId: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(vertical = 12.dp),
        ) {
            Text("Rooms", color = colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            players.forEach { p ->
                val status = when {
                    p.isTvAudio -> "TV audio"
                    p.isPlaying && p.nowTitle != null -> listOfNotNull("Playing", p.nowTitle, p.nowArtist).joinToString(" · ")
                    p.nowTitle != null -> "Paused · ${p.nowTitle}"
                    else -> "Idle"
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(p.id) }
                        .background(if (p.id == selectedId) colors.primary.copy(alpha = 0.15f) else Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Speaker, contentDescription = null, tint = if (p.id == selectedId) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name + if (p.isGroupLeader) " +${p.groupMembers.size - 1}" else "", color = colors.onSurface, fontSize = 16.sp)
                        Text(status, color = colors.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
