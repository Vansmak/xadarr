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
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
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
import com.arflix.tv.ui.skin.LocalFocusBorderColorOverride

/**
 * "20:35", "Thu 20:35", or "Now" — enough to tell tonight from next week at a glance.
 *
 * Deliberately shows the weekday rather than a date: the EPG only reaches a few days out, so
 * "Thu" is unambiguous and reads faster than "10 Sep" on a row that is already tight.
 */
private fun formatWhen(startUtcMillis: Long): String {
    val now = System.currentTimeMillis()
    if (startUtcMillis <= now) return "Now"
    val start = java.util.Calendar.getInstance().apply { timeInMillis = startUtcMillis }
    val today = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val clock = "%02d:%02d".format(
        start.get(java.util.Calendar.HOUR_OF_DAY),
        start.get(java.util.Calendar.MINUTE),
    )
    val sameDay = start.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) &&
        start.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
    if (sameDay) return clock
    val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        .format(java.util.Date(startUtcMillis))
    return "$day $clock"
}

/** A search hit — a channel, optionally with the specific program that matched the query. */
private data class SearchHit(
    val channel: EnrichedChannel,
    val matchedProgram: IptvProgram? = null,
    val isOffLineup: Boolean = false,
)

/**
 * One fixture/program airing on one or more channels, best channel first (see [channelRank]).
 * Used to be one row per fixture keeping whichever channel happened to come first, which is how
 * searching "baseball" tuned Joe to RDS -- a French-Canadian channel -- for Red Sox at Yankees
 * when English US channels carried the same game (2026-09-30: "hit or miss").
 */
private data class EventHit(
    val key: String,
    val program: IptvProgram,
    val channels: List<SearchHit>,
)

private val SearchAliases = mapOf(
    "tnf" to listOf("thursday night football"),
    "snf" to listOf("sunday night football"),
    "mnf" to listOf("monday night football"),
    "nfl" to listOf("nfl", "football"),
    "mlb" to listOf("mlb", "baseball"),
    "nba" to listOf("nba", "basketball"),
    "nhl" to listOf("nhl", "hockey"),
    "ufc" to listOf("ufc"),
    "cfb" to listOf("college football"),
)

/** The query plus any sports shorthand it stands for. */
private fun expandSearchQuery(q: String): List<String> {
    val trimmed = q.trim()
    return (listOf(trimmed) + SearchAliases[trimmed].orEmpty()).filter { it.isNotBlank() }.distinct()
}

private val FrenchChannelHints = listOf(
    "rds", "tva", "tqs", "radio-canada", "ici ", "télé", "tele-quebec", "télé-québec", "tv5",
    "canal+", "noovo", "fr |", "fr:", "(fr)", "french", "bein sports fr",
)
private val SpanishChannelHints = listOf(
    "deportes", "univision", "telemundo", "tudn", "unimas", "unimás", "galavision", "galavisión",
    "español", "espanol", "latino", "es |", "es:", "mx |", "(es)", "spanish", "estrella",
)
private val EnglishWords = setOf("the", "and", "of", "to", "in", "with", "at", "on", "for", "from", "live")
private val FrenchWords = setOf("le", "la", "les", "des", "du", "et", "à", "au", "aux", "pour", "avec", "une", "dans", "sur", "contre", "direct")
private val SpanishWords = setOf("el", "los", "las", "del", "y", "con", "para", "una", "por", "al", "vivo", "contra", "partido")

/**
 * Best-effort spoken language for a channel ("EN", "FR", "ES", or the country code when that's
 * all we know). [EnrichedChannel.lang] is really just the country, so a French-Canadian sports
 * channel read as "CA". Uses name/group hints first, then the program text: French writes
 * "Baseball MLB : Boston..." (space before the colon) and both have tell-tale function words.
 */
private fun detectLanguage(channel: EnrichedChannel, program: IptvProgram?): String {
    val name = (channel.name + " " + channel.source.group).lowercase()
    if (FrenchChannelHints.any { it in name }) return "FR"
    if (SpanishChannelHints.any { it in name }) return "ES"
    if (program != null) {
        val text = (program.title + " " + program.description.orEmpty())
        if (Regex("""\w :""").containsMatchIn(program.title)) return "FR"
        val words = text.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.isNotBlank() }
        val en = words.count { it in EnglishWords }
        val fr = words.count { it in FrenchWords }
        val es = words.count { it in SpanishWords }
        if (fr > en && fr >= es && fr >= 2) return "FR"
        if (es > en && es > fr && es >= 2) return "ES"
    }
    return when (channel.country) {
        null, "US", "UK", "GB", "CA", "AU", "IE", "NZ" -> "EN"
        else -> channel.country
    }
}

/** Higher = better place to watch. English, in your lineup, not Low BW, favorite, US, quality. */
private fun channelRank(hit: SearchHit, favorites: Set<String>): Int {
    val ch = hit.channel
    var score = 0
    if (detectLanguage(ch, hit.matchedProgram) == "EN") score += 1000
    if (!hit.isOffLineup) score += 400
    val lowBw = ch.source.group.contains("low bw", ignoreCase = true) || ch.name.startsWith("LBW", ignoreCase = true)
    if (!lowBw) score += 200
    if (ch.id in favorites) score += 150
    if (ch.country == null || ch.country == "US") score += 100
    score += when (ch.quality) {
        Quality.K4 -> 40
        Quality.FHD -> 30
        Quality.HD -> 20
        Quality.SD -> 10
    }
    return score
}

/**
 * Modal search overlay. Spec §3.5 — 760dp panel, accent caret, result rows with
 * channel number / logo / name / category / quality / lang.
 *
 * Matches channel name/number/genre/country AND program titles (now/next/upcoming) across
 * every channel — including channels in Hidden/New (not-yet-shown) provider groups — so a
 * program airing on a channel outside the curated daily lineup is still findable. Channels
 * in Removed groups are excluded: those are marked for deletion and shouldn't resurface.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    // Music Assistant: songs, artists, albums, playlists (Spotify included). Off when MA isn't set up.
    musicSearchAvailable: Boolean = false,
    onMusicSearch: suspend (String) -> List<com.arflix.tv.music.MaMediaItem> = { emptyList() },
    onPickMusic: (com.arflix.tv.music.MaMediaItem) -> Unit = {},
    onPickMedia: (MediaItem) -> Unit = {},
    // Favorite channel ids (ranking boost) and "movie:<tmdb>"/"tv:<tmdb>" keys already in the
    // library (Movies & Shows rows say "In library" instead of offering to add).
    favoriteIds: Set<String> = emptySet(),
    libraryMediaKeys: Set<String> = emptySet(),
    // Your own shows/movies (Sonarr/Radarr), matched locally and instantly -- they lead the
    // Movies & Shows row, ahead of TMDB results that could be added.
    libraryItems: List<MediaItem> = emptyList(),
    // Reports the current text, so "Back returns to your search" can reopen it.
    onQueryChange: (String) -> Unit = {},
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
    LaunchedEffect(query) { onQueryChange(query) }
    var debounced by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var remoteResults by remember { mutableStateOf<List<RawProviderStream>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var mediaResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var programResults by remember { mutableStateOf<List<EventHit>>(emptyList()) }
    // Movies & Shows row: library matches first (instant), then TMDB results not already shown.
    val titleRow = remember(debounced, libraryItems, mediaResults) {
        val q = debounced.trim().lowercase()
        if (q.length < 2 || q.all { it.isDigit() }) {
            emptyList()
        } else {
            fun key(m: MediaItem) = (if (m.mediaType == com.arflix.tv.data.model.MediaType.TV) "tv:" else "movie:") + m.id
            val lib = libraryItems.filter { it.title.lowercase().contains(q) }
            val libKeys = lib.mapTo(HashSet(), ::key)
            lib + mediaResults.filterNot { key(it) in libKeys }
        }
    }
    // Events whose other channels are shown (Right on the row expands, Left collapses).
    var expandedEvents by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mediaLoading by remember { mutableStateOf(false) }
    var musicResults by remember { mutableStateOf<List<com.arflix.tv.music.MaMediaItem>>(emptyList()) }
    var musicLoading by remember { mutableStateOf(false) }
    LaunchedEffect(debounced, musicSearchAvailable) {
        if (!musicSearchAvailable || debounced.length < 2 || debounced.all { it.isDigit() }) {
            musicResults = emptyList()
            musicLoading = false
            return@LaunchedEffect
        }
        musicLoading = true
        musicResults = runCatching { onMusicSearch(debounced) }.getOrDefault(emptyList())
        musicLoading = false
    }
    val focusRequester = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val overlayScope = rememberCoroutineScope()
    var resultsFocused by remember { mutableStateOf(false) }
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

    // Back belongs to the overlay while it is open. Without this, a Back press with focus on a
    // result row escaped to the guide's own handlers: the guide took focus back while the search
    // panel stayed on screen, leaving a visible search box that no longer responded to anything.
    // Two steps, matching how every other panel here behaves — out of the results first, then out
    // of the overlay.
    BackHandler(enabled = true) {
        if (resultsFocused) {
            resultsFocused = false
            overlayScope.launch {
                repeat(4) {
                    if (runCatching { focusRequester.requestFocus() }.isSuccess) return@launch
                    delay(24L)
                }
            }
        } else {
            onDismiss()
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
        if (debounced.length < 2 || debounced.all { it.isDigit() }) {
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
        // A bare number is a channel number: just the channel list (exact number first, see
        // the 1000 score below), no program/event matches.
        val isChannelNumber = q.isNotEmpty() && q.all { it.isDigit() }
        // Shorthand people actually type for sports ("tnf") vs. how listings spell it.
        val qTerms = expandSearchQuery(q)
        fun String.matchesQuery() = qTerms.any { this.contains(it) }
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
        programResults = if (isChannelNumber) emptyList() else withContext(Dispatchers.Default) {
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
                        // Descriptions only count on sports channels: that's where fixtures hide in
                        // the description ("49ers @ Rams"). Elsewhere it matched every 24/7 cartoon
                        // whose episode blurb mentions the word -- "baseball" returned Davey and
                        // Goliath, Mighty Max, I Love Lucy... burying the actual games (Joe,
                        // 2026-10-01).
                        .filter { prog ->
                            prog.title.lowercase().matchesQuery() ||
                                (ch.genre == Genre.Sports && prog.description?.lowercase()?.matchesQuery() == true)
                        }
                        .distinctBy { p -> p.title to p.startUtcMillis }
                        .map { prog -> SearchHit(ch, prog, ch.source.group in offLineupGroups) }
                }
                // One row per fixture: the same game is carried by several channels (and regional
                // variants), grouped here with the best place to watch it first.
                .groupBy { hit ->
                    (hit.matchedProgram?.title?.lowercase()?.trim() ?: hit.channel.id) + "@" +
                        (hit.matchedProgram?.startUtcMillis ?: 0L)
                }
                .map { (key, hits) ->
                    val ranked = hits.distinctBy { it.channel.id }.sortedByDescending { channelRank(it, favoriteIds) }
                    EventHit(key = key, program = ranked.first().matchedProgram!!, channels = ranked)
                }
                // Title matches first (the program IS the thing searched for), then by start time.
                .sortedWith(
                    compareBy<EventHit> { if (it.program.title.lowercase().matchesQuery()) 0 else 1 }
                        .thenBy { it.program.startUtcMillis }
                )
                .take(25)
                .toList()
        }
        expandedEvents = emptySet()
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
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                // Focus trap: D-pad focus search could leave the panel (e.g. Up from a row whose
                // neighbour above isn't composed, or back into the text box) and land on the
                // guide behind it -- Joe, 2026-09-30, screenshot with the guide row highlighted
                // behind the open search. Nothing leaves this panel while it's open.
                .focusProperties { exit = { androidx.compose.ui.focus.FocusRequester.Cancel } }
                .focusGroup(),
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
                                    resultsFocused = true
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
                    cursorBrush = SolidColor((LocalFocusBorderColorOverride.current ?: LiveColors.Accent)),
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
                                (titleRow.isNotEmpty() || results.isNotEmpty() || programResults.isNotEmpty())
                            ) {
                                resultsFocused = true
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
                if (debounced.length >= 2 && (titleRow.isNotEmpty() || mediaLoading)) {
                    item(key = "titles") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (mediaLoading && titleRow.isEmpty()) "SEARCHING MOVIES & SHOWS…" else "MOVIES & SHOWS",
                                style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                            )
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                items(titleRow, key = { "title:${it.mediaType}:${it.id}" }) { media ->
                                    val isFirst = media === titleRow.first()
                                    val key = (if (media.mediaType == com.arflix.tv.data.model.MediaType.TV) "tv:" else "movie:") + media.id
                                    TitleCard(
                                        media = media,
                                        inLibrary = key in libraryMediaKeys,
                                        onPick = { onPickMedia(media) },
                                        onMoveUp = { resultsFocused = false; runCatching { focusRequester.requestFocus() } },
                                        modifier = if (isFirst) Modifier.focusRequester(firstResultFocus) else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
                // Music sits near the top as one row of covers, so it's visible without scrolling
                // past every live-TV hit for the same name.
                if (musicSearchAvailable && debounced.length >= 2 && (musicResults.isNotEmpty() || musicLoading)) {
                    item(key = "music") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (musicLoading && musicResults.isEmpty()) "SEARCHING MUSIC…" else "MUSIC",
                                style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                            )
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                items(musicResults, key = { "music:${it.uri}" }) { item ->
                                    MusicCard(
                                        item = item,
                                        onPick = { onPickMusic(item) },
                                        onMoveUp = { resultsFocused = false; runCatching { focusRequester.requestFocus() } },
                                        modifier = if (titleRow.isEmpty() && item === musicResults.first()) Modifier.focusRequester(firstResultFocus) else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
                if (programResults.isNotEmpty()) {
                    item(key = "program-header") {
                        Text(
                            text = "ON NOW & COMING UP",
                            style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(programResults, key = { "event:${it.key}" }) { event ->
                        val isFirst = titleRow.isEmpty() && musicResults.isEmpty() && event === programResults.first()
                        val expanded = event.key in expandedEvents
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SearchResultRow(
                                hit = event.channels.first(),
                                langLabel = detectLanguage(event.channels.first().channel, event.program),
                                moreCount = event.channels.size - 1,
                                expanded = expanded,
                                onToggleExpand = {
                                    expandedEvents = if (expanded) expandedEvents - event.key else expandedEvents + event.key
                                },
                                onPick = onPick,
                                onShowInfo = { onShowInfo(event.channels.first().channel, event.program) },
                                onMoveUp = if (isFirst) {
                                    { resultsFocused = false; runCatching { focusRequester.requestFocus() } }
                                } else {
                                    null
                                },
                                modifier = if (isFirst) Modifier.focusRequester(firstResultFocus) else Modifier,
                            )
                            if (expanded) {
                                event.channels.drop(1).forEach { alt ->
                                    SearchResultRow(
                                        hit = alt,
                                        langLabel = detectLanguage(alt.channel, alt.matchedProgram),
                                        indent = 40.dp,
                                        onCollapse = { expandedEvents = expandedEvents - event.key },
                                        onPick = onPick,
                                        onShowInfo = { onShowInfo(alt.channel, alt.matchedProgram) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (results.isNotEmpty()) {
                    if (debounced.isNotEmpty()) {
                        item(key = "channel-header") {
                            Text(
                                text = "CHANNELS",
                                style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
                                modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 2.dp),
                            )
                        }
                    }
                    items(results, key = { it.channel.id }) { hit ->
                        val isFirst = titleRow.isEmpty() && musicResults.isEmpty() && programResults.isEmpty() && hit.channel.id == results.first().channel.id
                        SearchResultRow(
                            hit = hit,
                            langLabel = detectLanguage(hit.channel, hit.matchedProgram),
                            onPick = onPick,
                            onShowInfo = { onShowInfo(hit.channel, hit.matchedProgram) },
                            onMoveUp = if (isFirst) {
                                { resultsFocused = false; runCatching { focusRequester.requestFocus() } }
                            } else {
                                null
                            },
                            modifier = if (isFirst) Modifier.focusRequester(firstResultFocus) else Modifier,
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
            }
        }
    }
}

/** A Music Assistant hit as a cover card: name, and what it is ("Song · Artist"). Select picks a room. */
@Composable
private fun MusicCard(
    item: com.arflix.tv.music.MaMediaItem,
    onPick: () -> Unit,
    onMoveUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val kind = when (item.mediaType) {
        "track" -> "Song"
        "artist" -> "Artist"
        "album" -> "Album"
        "playlist" -> "Playlist"
        else -> item.mediaType.replaceFirstChar { it.uppercase() }
    }
    Column(
        modifier = modifier
            .width(140.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) LiveColors.Panel else Color.Transparent)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) (LocalFocusBorderColorOverride.current ?: LiveColors.FocusRing) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.hasFocus }
            .focusable()
            .onKeyEvent { ev ->
                when {
                    ev.type != KeyEventType.KeyDown -> false
                    ev.key == Key.DirectionCenter || ev.key == Key.Enter -> { onPick(); true }
                    ev.key == Key.DirectionUp -> { onMoveUp(); true }
                    else -> false
                }
            }
            .pointerInput(item.uri) { detectTapGestures(onTap = { onPick() }) }
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .clip(if (item.mediaType == "artist") RoundedCornerShape(64.dp) else RoundedCornerShape(6.dp))
                .background(LiveColors.PanelDeep),
        ) {
            if (item.imageUrl != null) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = item.name,
            style = LiveType.CellTitle.copy(color = LiveColors.Fg, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(kind, item.subtitle?.takeIf { item.mediaType != "artist" }).joinToString(" · "),
            style = LiveType.SectionTag.copy(color = LiveColors.FgMute),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Landscape card in the Movies & Shows row: backdrop, title, IN LIBRARY / + ADD. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TitleCard(
    media: MediaItem,
    inLibrary: Boolean,
    onPick: () -> Unit,
    onMoveUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalFocusBorderColorOverride.current ?: LiveColors.Accent
    Column(
        modifier = modifier
            .width(196.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) LiveColors.Panel else Color.Transparent)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) (LocalFocusBorderColorOverride.current ?: LiveColors.FocusRing) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.hasFocus }
            .focusable()
            .onKeyEvent { ev ->
                when {
                    ev.type != KeyEventType.KeyDown -> false
                    ev.key == Key.DirectionCenter || ev.key == Key.Enter -> { onPick(); true }
                    ev.key == Key.DirectionUp -> { onMoveUp(); true }
                    else -> false
                }
            }
            .pointerInput(media.id, media.mediaType) { detectTapGestures(onTap = { onPick() }) }
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(LiveColors.PanelDeep),
        ) {
            val art = media.backdrop?.takeIf { it.isNotBlank() } ?: media.image.takeIf { it.isNotBlank() }
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (inLibrary) accent else Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    if (inLibrary) "IN LIBRARY" else "+ ADD",
                    style = LiveType.Badge.copy(color = if (inLibrary) LiveColors.PanelDeep else LiveColors.Fg, fontSize = 9.sp),
                )
            }
        }
        Text(
            text = media.title,
            style = LiveType.CellTitle.copy(color = LiveColors.Fg, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(
                if (media.mediaType == com.arflix.tv.data.model.MediaType.TV) "Show" else "Movie",
                media.year.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            style = LiveType.SectionTag.copy(color = LiveColors.FgMute, fontSize = 11.sp),
        )
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
    inLibrary: Boolean = false,
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
                color = if (focused) (LocalFocusBorderColorOverride.current ?: LiveColors.FocusRing) else Color.Transparent,
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
        // Already yours (play it from its page) vs. something its page can add.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (inLibrary) (LocalFocusBorderColorOverride.current ?: LiveColors.Accent).copy(alpha = 0.22f) else LiveColors.Panel)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                if (inLibrary) "IN LIBRARY" else "+ ADD",
                style = LiveType.Badge.copy(
                    color = if (inLibrary) (LocalFocusBorderColorOverride.current ?: LiveColors.Accent) else LiveColors.FgMute,
                    fontSize = 10.sp,
                ),
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
    // Detected spoken language (see detectLanguage), shown instead of the bare country code.
    langLabel: String = hit.channel.lang,
    // Event rows: how many other channels carry it; Right expands them, Left collapses.
    moreCount: Int = 0,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    // Alternate-channel rows under an expanded event: indented, Left collapses the event.
    indent: androidx.compose.ui.unit.Dp = 0.dp,
    onCollapse: (() -> Unit)? = null,
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
            .padding(start = indent)
            .fillMaxWidth()
            .height(if (hit.matchedProgram != null) 72.dp else 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) LiveColors.Panel else Color.Transparent)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) (LocalFocusBorderColorOverride.current ?: LiveColors.FocusRing) else Color.Transparent,
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
                    ev.key == Key.DirectionRight && moreCount > 0 && !expanded && onToggleExpand != null -> {
                        onToggleExpand(); true
                    }
                    ev.key == Key.DirectionLeft && expanded && onToggleExpand != null -> {
                        onToggleExpand(); true
                    }
                    ev.key == Key.DirectionLeft && onCollapse != null -> {
                        onCollapse(); true
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
                // Searching a team is asking when and where. The channel name above answers
                // "where"; without this the row never answered "when" at all, so a fixture
                // tonight and one next Tuesday looked identical.
                Text(
                    text = "${formatWhen(hit.matchedProgram.startUtcMillis)}  ·  ${hit.matchedProgram.title}",
                    style = LiveType.SectionTag.copy(color = (LocalFocusBorderColorOverride.current ?: LiveColors.Accent)),
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
            if (moreCount > 0) {
                Text(
                    text = if (expanded) "◂ hide" else "+$moreCount more ▸",
                    style = LiveType.Badge.copy(color = LiveColors.FgDim, fontSize = 11.sp),
                )
            }
            if (hit.isOffLineup) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background((LocalFocusBorderColorOverride.current ?: LiveColors.Accent).copy(alpha = 0.22f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        // Names the group, because "HIDDEN GROUP" tells you a channel is out of
                        // the lineup without telling you where to go to unhide it — and with
                        // Tier 2 event groups that is the only thing you actually need to know.
                        // The provider-catalogue rows below carry their own "not in your lineup"
                        // heading and behave differently (ephemeral unless pinned), so the two
                        // must not read the same.
                        hit.channel.source.group.uppercase().take(22),
                        style = LiveType.Badge.copy(color = (LocalFocusBorderColorOverride.current ?: LiveColors.Accent), fontSize = 10.sp),
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
                    Text(
                        langLabel,
                        style = LiveType.Badge.copy(
                            color = if (langLabel == "EN") LiveColors.FgMute else (LocalFocusBorderColorOverride.current ?: LiveColors.Accent),
                        ),
                    )
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
                color = if (focused) (LocalFocusBorderColorOverride.current ?: LiveColors.FocusRing) else Color.Transparent,
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
            tint = if (isPinned) (LocalFocusBorderColorOverride.current ?: LiveColors.Accent) else LiveColors.FgMute,
            modifier = Modifier.size(20.dp),
        )
    }
}
