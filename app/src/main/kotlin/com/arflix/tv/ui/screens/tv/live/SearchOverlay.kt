package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.model.IptvNowNext
import com.arflix.tv.data.model.IptvProgram
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.repository.RawProviderStream
import com.arflix.tv.util.formatGenreName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A search hit — a channel, optionally with the specific program that matched the query. */
private data class SearchHit(
    val channel: EnrichedChannel,
    val matchedProgram: IptvProgram? = null,
    val isOffLineup: Boolean = false,
)

/**
 * Modal search overlay. Spec §3.5 — 760dp panel, accent caret, result rows with
 * channel number / logo / name / category / quality / lang.
 *
 * Matches channel name/number/genre/country AND program titles (now/next/upcoming) across
 * every channel — including channels in Hidden/New (not-yet-shown) provider groups — so a
 * program airing on a channel outside the curated daily lineup is still findable. Channels
 * in Removed groups are excluded: those are marked for deletion and shouldn't resurface.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchOverlay(
    initialQuery: String = "",
    channels: List<EnrichedChannel>,
    nowNext: Map<String, IptvNowNext> = emptyMap(),
    offLineupGroups: Set<String> = emptySet(),
    remoteSearchAvailable: Boolean = false,
    onRemoteSearch: suspend (String) -> List<RawProviderStream> = { emptyList() },
    pinnedStreamIds: Set<String> = emptySet(),
    onTogglePin: (RawProviderStream) -> Unit = {},
    onMediaSearch: suspend (String) -> List<MediaItem> = { emptyList() },
    onPickMedia: (MediaItem) -> Unit = {},
    onDismiss: () -> Unit,
    onPick: (EnrichedChannel) -> Unit,
    // Long-press (520ms hold, Menu key, or touch long-press — same gesture as RemoteStreamRow's
    // pin toggle) on a result opens program info instead of tuning immediately, so a program
    // match (as opposed to a bare channel/genre-name match) can be inspected/reminded on before
    // committing to it. `program` is the specific EPG hit that matched the query, or null when
    // the result matched on channel name/genre rather than a program title.
    onShowInfo: (EnrichedChannel, IptvProgram?) -> Unit = { _, _ -> },
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var debounced by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var remoteResults by remember { mutableStateOf<List<RawProviderStream>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var mediaResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var programResults by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var mediaLoading by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val overlayScope = rememberCoroutineScope()
    // Retry, because a single attempt loses the race. This runs as soon as the overlay enters
    // composition, which can be before the FocusRequester's modifier has been attached — the
    // request then throws, runCatching swallows it, and focus silently stays on the guide
    // underneath. That is the "search opens but the D-pad is still driving the grid behind it"
    // bug. Same pattern keepChannelFocus and openSidebar already use for the same reason.
    LaunchedEffect(Unit) {
        repeat(6) { attempt ->
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(if (attempt < 2) 16L else 48L)
        }
    }

    // Debounce input for 150ms per spec §7.
    LaunchedEffect(query) {
        delay(150)
        debounced = query.trim()
    }

    // Full raw-provider catalog search — separate, slower (network) pass; only fires for
    // queries specific enough to be worth a round trip, and only when Episeerr's Dispatcharr
    // proxy is actually configured.
    LaunchedEffect(debounced, remoteSearchAvailable) {
        if (!remoteSearchAvailable || debounced.length < 2) {
            remoteResults = emptyList()
            remoteLoading = false
            return@LaunchedEffect
        }
        remoteLoading = true
        remoteResults = runCatching { onRemoteSearch(debounced) }.getOrDefault(emptyList())
        remoteLoading = false
    }

    // TMDB movie/show search — separate, slower (network) pass, same shape as the remote
    // provider-catalog search above. This is currently the only reachable general search in
    // the app (the standalone Search screen was retired in the TiviMate redesign).
    LaunchedEffect(debounced) {
        if (debounced.length < 2) {
            mediaResults = emptyList()
            mediaLoading = false
            return@LaunchedEffect
        }
        mediaLoading = true
        mediaResults = runCatching { onMediaSearch(debounced) }.getOrDefault(emptyList())
        mediaLoading = false
    }

    LaunchedEffect(debounced, channels, nowNext) {
        val q = debounced.lowercase()
        if (q.isEmpty()) {
            // Show the first 60 by default — gives a preview list users can scroll.
            results = channels.take(60).map { SearchHit(it, isOffLineup = it.source.group in offLineupGroups) }
            programResults = emptyList()
            return@LaunchedEffect
        }
        results = withContext(Dispatchers.Default) {
            channels.asSequence()
                .map { ch ->
                    val nameLower = ch.name.lowercase()
                    val nn = nowNext[ch.id]
                    val programMatch = sequenceOf(nn?.now, nn?.next, nn?.later)
                        .plus(nn?.upcoming.orEmpty())
                        .filterNotNull()
                        .firstOrNull { it.title.lowercase().contains(q) }
                    val score = when {
                        ch.number.toString() == q -> 1000
                        nameLower == q -> 900
                        nameLower.startsWith(q) -> 700
                        nameLower.contains(q) -> 500
                        programMatch != null && programMatch.title.lowercase().startsWith(q) -> 420
                        programMatch != null -> 380
                        ch.genre.name.lowercase().contains(q) -> 250
                        ch.country?.lowercase() == q -> 200
                        else -> 0
                    }
                    Triple(ch, programMatch, score)
                }
                .filter { it.third > 0 }
                .sortedByDescending { it.third }
                .map { (ch, program, _) ->
                    SearchHit(ch, program, ch.source.group in offLineupGroups)
                }
                .take(200)
                .toList()
        }
        // Programmes get their own list rather than competing for the single slot each channel
        // was allowed. Before this, a channel could only ever contribute one result, and a name
        // match always outscored a programme match (500 vs 380) — so searching a sport or a show
        // filled the list with channels whose *names* matched and buried what was actually on.
        // A channel can now surface several programmes, and they are ordered by start time,
        // because "what is on soonest" is the useful ordering for a guide.
        programResults = withContext(Dispatchers.Default) {
            channels.asSequence()
                .flatMap { ch ->
                    val nn = nowNext[ch.id]
                    sequenceOf(nn?.now, nn?.next, nn?.later)
                        .plus(nn?.upcoming.orEmpty())
                        .filterNotNull()
                        // Description as well as title. Sports channels in this lineup title
                        // their entries generically — "Game Today", "No Game Today" — and put
                        // the fixture in the description ("San Francisco 49ers @ Los Angeles
                        // Rams on 2026-09-10..."). Matching titles alone meant searching a team
                        // found the odd channel named after it and missed every actual game.
                        .filter { prog ->
                            prog.title.lowercase().contains(q) ||
                                prog.description?.lowercase()?.contains(q) == true
                        }
                        .distinctBy { p -> p.title to p.startUtcMillis }
                        .map { prog -> SearchHit(ch, prog, ch.source.group in offLineupGroups) }
                }
                .sortedBy { hit -> hit.matchedProgram?.startUtcMillis ?: Long.MAX_VALUE }
                .take(80)
                .toList()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .padding(top = 64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(LiveColors.PanelRaised)
                .border(1.dp, LiveColors.Divider, RoundedCornerShape(16.dp))
                .padding(16.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Go to results",
                    tint = LiveColors.FgDim,
                    // Was decorative, which is fine until someone taps it and nothing happens.
                    // It now does what Down does: jump from typing into the results.
                    modifier = Modifier
                        .size(20.dp)
                        .pointerInput(results, programResults) {
                            detectTapGestures(onTap = {
                                if (results.isNotEmpty() || programResults.isNotEmpty()) {
                                    runCatching { firstResultFocus.requestFocus() }
                                }
                            })
                        },
                )
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { runCatching { firstResultFocus.requestFocus() } },
                    ),
                    cursorBrush = SolidColor(LiveColors.Accent),
                    textStyle = TextStyle(
                        color = LiveColors.Fg,
                        fontSize = 18.sp,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { ev ->
                            // Programme hits count as results too. This used to test `results`
                            // alone — the channel list — so a query that matched only programmes
                            // ("rams" matches NFL fixtures but no channel called Rams) left Down
                            // doing nothing at all, trapping focus in the text field with the
                            // matches sitting unreachable below it.
                            if (ev.type == KeyEventType.KeyDown &&
                                ev.key == Key.DirectionDown &&
                                (results.isNotEmpty() || programResults.isNotEmpty())
                            ) {
                                overlayScope.launch {
                                    repeat(4) {
                                        if (runCatching { firstResultFocus.requestFocus() }.isSuccess) {
                                            return@launch
                                        }
                                        delay(24L)
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }
                        .onKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Back) {
                                onDismiss(); true
                            } else false
                        },
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                "Channel, movie, or show title…",
                                style = TextStyle(color = LiveColors.FgMute, fontSize = 18.sp),
                            )
                        }
                        inner()
                    },
                )
                Text(
                    "ESC",
                    style = LiveType.NumberMono.copy(color = LiveColors.FgMute),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LiveColors.Divider),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(440.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(results, key = { it.channel.id }) { hit ->
                    val focusMod = if (results.isNotEmpty() && hit.channel.id == results.first().channel.id) {
                        Modifier.focusRequester(firstResultFocus)
                    } else Modifier
                    SearchResultRow(
                        hit = hit,
                        onPick = onPick,
                        onShowInfo = { onShowInfo(hit.channel, hit.matchedProgram) },
                        onMoveUp = if (results.isNotEmpty() && hit.channel.id == results.first().channel.id) {
                            { runCatching { focusRequester.requestFocus() } }
                        } else {
                            null
                        },
                        modifier = focusMod,
                    )
                }
                if (programResults.isNotEmpty()) {
                    item(key = "program-header") {
                        Text(
                            text = "ON NOW & COMING UP",
                            style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                            modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        programResults,
                        // Keyed on the programme, not the channel: one channel can legitimately
                        // appear several times here when it is showing more than one match.
                        key = { hit -> "prog:${hit.channel.id}:${hit.matchedProgram?.startUtcMillis ?: 0L}" },
                    ) { hit ->
                        // When nothing matched on channel name, the programmes are the whole
                        // result set, so the first of them has to be what Down from the text
                        // field lands on. Without this the focus requester was attached to a row
                        // that did not exist and the request silently failed.
                        val isFirstFocusable = results.isEmpty() && hit == programResults.first()
                        SearchResultRow(
                            hit = hit,
                            onPick = onPick,
                            onShowInfo = { onShowInfo(hit.channel, hit.matchedProgram) },
                            onMoveUp = if (isFirstFocusable) {
                                { runCatching { focusRequester.requestFocus() } }
                            } else {
                                null
                            },
                            modifier = if (isFirstFocusable) {
                                Modifier.focusRequester(firstResultFocus)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
                if (remoteSearchAvailable && debounced.length >= 2) {
                    item(key = "remote-header") {
                        Text(
                            text = if (remoteLoading) {
                                "SEARCHING YOUR FULL PROVIDER LINEUP…"
                            } else {
                                "FROM YOUR PROVIDER — NOT IN YOUR LINEUP"
                            },
                            style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                            modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(remoteResults, key = { "remote:${it.id}" }) { stream ->
                        RemoteStreamRow(
                            stream = stream,
                            isPinned = stream.id in pinnedStreamIds,
                            onPlay = { onPick(stream.toIptvChannel().enrich(0)) },
                            onTogglePin = { onTogglePin(stream) },
                        )
                    }
                }
                if (debounced.length >= 2) {
                    item(key = "media-header") {
                        Text(
                            text = if (mediaLoading) "SEARCHING MOVIES & SHOWS…" else "MOVIES & SHOWS",
                            style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                            modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(mediaResults, key = { "media:${it.mediaType}:${it.id}" }) { media ->
                        MediaSearchResultRow(media = media, onPick = { onPickMedia(media) })
                    }
                }
            }
        }
    }
}

/**
 * A TMDB movie/show hit — tapping opens Details, which independently resolves whether it's
 * already in the library (Play) or not (Add to Watchlist, which now auto-grabs via Episeerr —
 * see WatchlistRepository.addToWatchlist). Same result either way, this row doesn't need to know.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MediaSearchResultRow(
    media: MediaItem,
    onPick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) LiveColors.Panel else Color.Transparent)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) LiveColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.hasFocus }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                    onPick(); true
                } else false
            }
            .pointerInput(media.id, media.mediaType) {
                detectTapGestures(onTap = { onPick() })
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(LiveColors.Panel),
        ) {
            if (media.image.isNotBlank()) {
                AsyncImage(
                    model = media.image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = media.title,
                style = LiveType.CellTitle.copy(color = LiveColors.Fg, fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    if (media.mediaType == com.arflix.tv.data.model.MediaType.TV) "Show" else "Movie",
                    media.year.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResultRow(
    hit: SearchHit,
    onPick: (EnrichedChannel) -> Unit,
    onShowInfo: () -> Unit = {},
    onMoveUp: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val channel = hit.channel
    var focused by remember { mutableStateOf(false) }
    // Same 520ms-hold / Menu-key / touch-long-press pattern as RemoteStreamRow's pin toggle —
    // a quick tap/OK tunes the channel immediately, a hold opens program info instead.
    var selectPressed by remember { mutableStateOf(false) }
    var consumedLongPress by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (hit.matchedProgram != null) 72.dp else 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) LiveColors.Panel else Color.Transparent)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) LiveColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.hasFocus }
            .focusable()
            .onKeyEvent { ev ->
                val isSelect = ev.key == Key.DirectionCenter || ev.key == Key.Enter
                val isMenuKey = ev.key == Key.Menu
                when {
                    isMenuKey && ev.type == KeyEventType.KeyDown -> { onShowInfo(); true }
                    isSelect && ev.type == KeyEventType.KeyDown -> {
                        if (!selectPressed) {
                            selectPressed = true
                            consumedLongPress = false
                            longPressJob?.cancel()
                            longPressJob = scope.launch {
                                delay(520L)
                                if (selectPressed) {
                                    consumedLongPress = true
                                    onShowInfo()
                                }
                            }
                        }
                        true
                    }
                    isSelect && ev.type == KeyEventType.KeyUp && consumedLongPress -> {
                        longPressJob?.cancel()
                        selectPressed = false
                        consumedLongPress = false
                        true
                    }
                    isSelect && ev.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        selectPressed = false
                        onPick(channel)
                        true
                    }
                    ev.type != KeyEventType.KeyDown -> false
                    ev.key == Key.DirectionUp -> {
                        if (onMoveUp != null) {
                            onMoveUp()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .pointerInput(channel.id) {
                detectTapGestures(
                    onTap = { onPick(channel) },
                    onLongPress = { onShowInfo() },
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = channel.number.toString(),
            style = LiveType.NumberMono.copy(color = LiveColors.FgMute),
            modifier = Modifier.width(40.dp),
        )
        ChannelLogo(channel = channel, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = LiveType.CellTitle.copy(color = LiveColors.Fg, fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hit.matchedProgram != null) {
                Text(
                    text = hit.matchedProgram.title,
                    style = LiveType.SectionTag.copy(color = LiveColors.Accent),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = formatGenreName(channel.genre.name),
                    style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (hit.isOffLineup) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(LiveColors.Accent.copy(alpha = 0.22f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        // These are in the M3U already — they sit in a group that is hidden or
                        // still marked NEW. They tune and play like any other channel. The
                        // provider-catalogue rows below carry their own "not in your lineup"
                        // heading and behave quite differently (ephemeral unless pinned), so
                        // giving both the same words made two unrelated things look identical.
                        "HIDDEN GROUP",
                        style = LiveType.Badge.copy(color = LiveColors.Accent, fontSize = 10.sp),
                        maxLines = 1,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(LiveColors.Panel)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(channel.quality.label, style = LiveType.Badge.copy(color = LiveColors.Fg))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(LiveColors.Panel)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(channel.lang, style = LiveType.Badge.copy(color = LiveColors.FgMute))
                }
            }
        }
    }
}

/**
 * A result from Dispatcharr's full raw provider catalog — outside the curated daily lineup.
 * Select plays it immediately; a long-press (520ms hold, Menu key, or touch long-press —
 * matching ChannelRow's favorite-toggle pattern so a quick tap can never accidentally
 * pin/unpin) toggles whether it's pinned into the guide permanently.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RemoteStreamRow(
    stream: RawProviderStream,
    isPinned: Boolean,
    onPlay: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val enriched = remember(stream.id) { stream.toIptvChannel().enrich(0) }
    var focused by remember { mutableStateOf(false) }
    var selectPressed by remember { mutableStateOf(false) }
    var consumedLongPress by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) LiveColors.Panel else Color.Transparent)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) LiveColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.hasFocus }
            .focusable()
            .onKeyEvent { ev ->
                val isSelect = ev.key == Key.DirectionCenter || ev.key == Key.Enter
                val isMenuKey = ev.key == Key.Menu
                when {
                    isMenuKey && ev.type == KeyEventType.KeyDown -> { onTogglePin(); true }
                    !isSelect -> false
                    ev.type == KeyEventType.KeyDown -> {
                        if (!selectPressed) {
                            selectPressed = true
                            consumedLongPress = false
                            longPressJob?.cancel()
                            longPressJob = scope.launch {
                                delay(520L)
                                if (selectPressed) {
                                    consumedLongPress = true
                                    onTogglePin()
                                }
                            }
                        }
                        true
                    }
                    ev.type == KeyEventType.KeyUp && consumedLongPress -> {
                        longPressJob?.cancel()
                        selectPressed = false
                        consumedLongPress = false
                        true
                    }
                    ev.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        selectPressed = false
                        onPlay()
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(stream.id) {
                detectTapGestures(
                    onTap = { onPlay() },
                    onLongPress = { onTogglePin() },
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChannelLogo(channel = enriched, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stream.name,
                style = LiveType.CellTitle.copy(color = LiveColors.Fg, fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stream.group,
                style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = if (isPinned) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (isPinned) "Pinned to guide — hold to unpin" else "Hold to pin to guide",
            tint = if (isPinned) LiveColors.Accent else LiveColors.FgMute,
            modifier = Modifier.size(20.dp),
        )
    }
}
