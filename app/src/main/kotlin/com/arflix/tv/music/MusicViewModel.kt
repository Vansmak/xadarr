package com.arflix.tv.music

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.music.MusicAssistantRepository.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

enum class MusicTab { NOW_PLAYING, QUEUE, LYRICS, ZONES, BROWSE }

data class BrowseState(
    val title: String = "Library",
    /** Stack of browse paths; empty = the Library home (playlists + recently played). */
    val pathStack: List<String> = emptyList(),
    /** A home section's "See all" list; Back returns to the home. */
    val sectionList: Boolean = false,
    val query: String = "",
    val items: List<MaMediaItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class MusicBusyPrompt(val item: MaMediaItem, val target: MaPlayer, val playing: List<MaPlayer>)

data class MusicUiState(
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val players: List<MaPlayer> = emptyList(),
    val selectedPlayerId: String? = null,
    val queue: MaQueue? = null,
    val queueItems: List<MaQueueItem> = emptyList(),
    /** Elapsed seconds at [elapsedAtMs] (device uptime clock) — the UI extrapolates from here. */
    val elapsedSec: Double = 0.0,
    val elapsedAtMs: Long = 0L,
    val tab: MusicTab = MusicTab.NOW_PLAYING,
    val browse: BrowseState = BrowseState(),
    val message: String? = null,
    /** False until the first players/all reply; "No zones found" waits for it. */
    val playersLoaded: Boolean = false,
    /** A pick waiting on "another room has the Spotify stream" (see [roomClashFor]). */
    val busyPrompt: MusicBusyPrompt? = null,
    /** Lyrics for [lyricsKey] (the current track); null with [lyricsLoaded] = none found. */
    val lyrics: MusicLyrics? = null,
    val lyricsKey: String? = null,
    val lyricsLoaded: Boolean = false,
    /** Every Sonos speaker, grouped members included (players hides those under their leader). */
    val speakers: List<MaPlayer> = emptyList(),
    /** Rooms with a TV Home Assistant can wake (lower-cased MA names, see [MusicTvRooms]). */
    val tvRooms: Set<String> = emptySet(),
    /**
     * Rooms whose TV toggle is on in the open Speakers dialog: joining them wakes their TV into
     * Xadarr Music. Not remembered -- every toggle starts off each time (Joe decides each time).
     */
    val tvToggles: Set<String> = emptySet(),
    /** A "Move to <room>" in progress (target room name). */
    val moving: String? = null,
    /** Xadarr Music on a TV with no room saved yet: ask "Which room is this TV in?" once. */
    val askHomeRoom: Boolean = false,
) {
    fun hasTv(p: MaPlayer) = p.name.lowercase() in tvRooms
    fun tvOn(p: MaPlayer) = p.name in tvToggles
    /** [member] plays [leader]'s music right now. */
    fun inGroupOf(member: MaPlayer, leader: MaPlayer) = member.syncedTo == leader.id || (member.id != leader.id && member.id in leader.groupMembers)
    val selectedPlayer: MaPlayer? get() = players.firstOrNull { it.id == selectedPlayerId }
    val isPlaying: Boolean get() = (queue?.state ?: selectedPlayer?.state) == "playing"
}

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val repo: MusicAssistantRepository,
    private val tv: MusicTvRooms,
) : ViewModel() {

    private val _ui = MutableStateFlow(MusicUiState())
    val ui: StateFlow<MusicUiState> = _ui.asStateFlow()

    private var queueRefreshJob: Job? = null
    /**
     * The room this screen follows: it shows that room's music -- its group leader's while it's
     * grouped, its own once it's on its own again. Set by picking a room (or the TV's home room).
     */
    private var followRoomId: String? = null
    /** Xadarr Music on a TV: open on this device's home room (see [openOnHomeRoom]). */
    private var homeRoomMode = false
    private var homeRoomPending = false
    /** Re-join guards from "join with TV", by room id; unticking a room cancels its guard. */
    private val joinGuards = mutableMapOf<String, Job>()
    private var messageJob: Job? = null

    init {
        repo.acquire()
        viewModelScope.launch {
            repo.state.collect { s ->
                _ui.update { it.copy(connection = s) }
            }
        }
        viewModelScope.launch {
            repo.events.collect { handleEvent(it) }
        }
        // Another Music surface (the phone's mini bar vs. the Music screen) picked a room.
        viewModelScope.launch {
            repo.selectedPlayerIdFlow.collect { id ->
                if (id != null && id != _ui.value.selectedPlayerId && _ui.value.players.any { it.id == id }) selectPlayer(id)
            }
        }
        // Lyrics follow the current track (Lyrics tab + screensaver).
        viewModelScope.launch {
            ui.map { it.queue?.current }.distinctUntilChanged { a, b -> a?.let(MusicLyricsSource::key) == b?.let(MusicLyricsSource::key) }
                .collectLatest { item ->
                    if (item == null) {
                        _ui.update { it.copy(lyrics = null, lyricsKey = null, lyricsLoaded = false) }
                        return@collectLatest
                    }
                    val key = MusicLyricsSource.key(item)
                    _ui.update { it.copy(lyrics = null, lyricsKey = key, lyricsLoaded = false) }
                    val found = MusicLyricsSource.lyricsFor(item)
                    _ui.update { if (it.lyricsKey == key) it.copy(lyrics = found, lyricsLoaded = true) else it }
                }
        }
        viewModelScope.launch { val rooms = tv.roomsWithTv(refresh = true); _ui.update { it.copy(tvRooms = rooms) } }
        // If the socket was already up (another screen holds it), load straight away.
        if (repo.state.value == ConnectionState.CONNECTED) viewModelScope.launch { loadAll() }
    }

    override fun onCleared() {
        repo.release()
        super.onCleared()
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    private suspend fun loadAll() {
        val players = runCatching { repo.command("players/all") as? JSONArray }.getOrNull() ?: return
        val parsed = (0 until players.length()).mapNotNull { players.optJSONObject(it)?.let { o -> MaParse.player(o, repo) } }
        val visible = parsed.filter { it.visible }.sortedBy { it.name.lowercase() }
        val speakers = parsed.filter { it.provider == "sonos" }.sortedBy { it.name.lowercase() }
        // Which room to show: this TV's home room on open (Xadarr Music), else the followed or
        // last-picked room. A room grouped under another shows its leader -- that's what plays
        // there. Never the first room in the alphabet (the SHIELD and Gym opened on "Bathroom").
        val home = if (homeRoomMode) repo.homeRoomId() else null
        val base = when {
            homeRoomPending && home != null -> home.also { homeRoomPending = false; followRoomId = it }
            else -> followRoomId ?: repo.selectedPlayerId()
        }
        val target = parsed.firstOrNull { it.id == base }?.syncedTo ?: base
        val selected = visible.firstOrNull { it.id == target }?.id
            ?: _ui.value.selectedPlayerId?.takeIf { id -> visible.any { it.id == id } }
            ?: if (homeRoomMode) null else visible.firstOrNull { it.isPlaying && !it.isTvAudio }?.id
        if (followRoomId == null) followRoomId = base ?: selected
        val askHome = homeRoomMode && home == null
        _ui.update { it.copy(players = visible, speakers = speakers, selectedPlayerId = selected, playersLoaded = true, askHomeRoom = askHome) }
        selected?.let { if (it != repo.selectedPlayerId()) repo.saveSelectedPlayerId(it) }
        selected?.let { loadQueue(it) }
        if (_ui.value.browse.items.isEmpty()) loadLibraryHome()
    }

    private suspend fun loadQueue(playerId: String) {
        val q = runCatching {
            repo.command("player_queues/get_active_queue", JSONObject().put("player_id", playerId)) as? JSONObject
        }.getOrNull()
        if (q == null) {
            _ui.update { it.copy(queue = null, queueItems = emptyList()) }
            return
        }
        applyQueue(MaParse.queue(q, repo))
        loadQueueItems()
    }

    private suspend fun loadQueueItems() {
        val queueId = _ui.value.queue?.id ?: return
        val arr = runCatching {
            repo.command("player_queues/items", JSONObject().put("queue_id", queueId).put("limit", 200)) as? JSONArray
        }.getOrNull() ?: return
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> MaParse.queueItem(o, repo) } }
        _ui.update { it.copy(queueItems = items) }
    }

    private fun applyQueue(q: MaQueue) {
        _ui.update { it.copy(queue = q, elapsedSec = q.elapsedSec, elapsedAtMs = SystemClock.elapsedRealtime()) }
    }

    private fun scheduleQueueItemsRefresh() {
        queueRefreshJob?.cancel()
        queueRefreshJob = viewModelScope.launch {
            delay(400) // queue_items_updated tends to arrive in bursts
            loadQueueItems()
        }
    }

    // ── Events ───────────────────────────────────────────────────────────────

    private suspend fun handleEvent(e: MusicAssistantRepository.Event) {
        when (e.name) {
            MusicAssistantRepository.EVENT_CONNECTED -> loadAll()
            "player_added", "player_removed" -> loadAll()
            "player_updated" -> {
                val o = e.data as? JSONObject ?: return
                val p = MaParse.player(o, repo)
                val before = _ui.value.selectedPlayer
                _ui.update { s ->
                    val others = s.players.filterNot { it.id == p.id }
                    val list = if (p.visible) (others + p).sortedBy { it.name.lowercase() } else others
                    // The followed room joined a group (show its leader) or left one (show it again);
                    // never fall back to the first room in the alphabet.
                    val leader = when {
                        p.id == followRoomId -> (p.syncedTo ?: p.id).takeIf { id -> list.any { it.id == id } }
                        p.id == s.selectedPlayerId && p.syncedTo != null -> p.syncedTo.takeIf { id -> list.any { it.id == id } }
                        else -> null
                    }
                    val sel = leader ?: s.selectedPlayerId.takeIf { id -> list.any { it.id == id } }
                    val speakers = if (p.provider == "sonos") (s.speakers.filterNot { it.id == p.id } + p).sortedBy { it.name.lowercase() } else s.speakers
                    s.copy(players = list, speakers = speakers, selectedPlayerId = sel)
                }
                if (_ui.value.selectedPlayerId != before?.id) _ui.value.selectedPlayerId?.let { id ->
                    repo.saveSelectedPlayerId(id)
                    loadQueue(id)
                    return
                }
                // Switching source (e.g. a new playlist started from the Sonos app) can move the
                // selected player onto a different queue.
                if (p.id == _ui.value.selectedPlayerId && before?.activeSource != p.activeSource) loadQueue(p.id)
            }
            "queue_updated" -> {
                val o = e.data as? JSONObject ?: return
                if (o.optString("queue_id") != _ui.value.queue?.id) return
                val prevItemId = _ui.value.queue?.current?.id
                applyQueue(MaParse.queue(o, repo))
                if (_ui.value.queue?.current?.id != prevItemId) scheduleQueueItemsRefresh()
            }
            "queue_items_updated" -> {
                if (e.objectId == null || e.objectId == _ui.value.queue?.id) scheduleQueueItemsRefresh()
            }
            "queue_time_updated" -> {
                if (e.objectId != _ui.value.queue?.id) return
                val secs = (e.data as? Number)?.toDouble() ?: return
                _ui.update { it.copy(elapsedSec = secs, elapsedAtMs = SystemClock.elapsedRealtime()) }
            }
        }
    }

    // ── Zone ─────────────────────────────────────────────────────────────────

    /** Picks the room to control (Zones tab, room chip); this screen then follows that room. */
    fun selectPlayer(id: String, follow: String = id) {
        followRoomId = follow
        if (id == _ui.value.selectedPlayerId) return
        _ui.update { it.copy(selectedPlayerId = id, queue = null, queueItems = emptyList()) }
        viewModelScope.launch {
            repo.saveSelectedPlayerId(id)
            loadQueue(id)
        }
    }

    fun setTab(tab: MusicTab) = _ui.update { it.copy(tab = tab) }

    /**
     * Xadarr Music on a TV, each time it's opened: show this TV's home room (or its group's
     * leader). The home room is asked once ([askHomeRoom]) and kept on this device only.
     */
    fun openOnHomeRoom() {
        homeRoomMode = true
        homeRoomPending = true
        if (repo.state.value == ConnectionState.CONNECTED) viewModelScope.launch { loadAll() }
    }

    /** The answer to "Which room is this TV in?". */
    fun setHomeRoom(id: String) {
        _ui.update { it.copy(askHomeRoom = false) }
        viewModelScope.launch {
            repo.saveHomeRoomId(id)
            homeRoomPending = true
            loadAll()
        }
    }

    fun dismissHomeRoomQuestion() = _ui.update { it.copy(askHomeRoom = false) }

    // ── Speakers: group, TV toggle, move ─────────────────────────────────────

    /** Fresh room list (and TV rooms) for the Speakers dialog; every TV toggle starts off. */
    fun refreshSpeakers() {
        _ui.update { it.copy(tvToggles = emptySet()) }
        viewModelScope.launch {
            val all = runCatching { repo.speakers() }.getOrNull()?.filter { it.provider == "sonos" }
            if (all != null) _ui.update { it.copy(speakers = all) }
            val rooms = tv.roomsWithTv(refresh = true)
            _ui.update { it.copy(tvRooms = rooms) }
        }
    }

    /** Joins [member] to the selected room's group, or takes it out. Never turns a TV off. */
    fun toggleGrouped(member: MaPlayer) {
        val leader = _ui.value.selectedPlayer ?: return showMessage("Pick a room first")
        if (member.id == leader.id) return
        val join = !_ui.value.inGroupOf(member, leader)
        // Optimistic tick; MA's player_updated events confirm (or undo) it.
        _ui.update { s ->
            s.copy(speakers = s.speakers.map { if (it.id == member.id) it.copy(syncedTo = if (join) leader.id else null) else it })
        }
        if (!join) joinGuards.remove(member.id)?.cancel()
        if (join && _ui.value.hasTv(member) && _ui.value.tvOn(member)) {
            joinGuards.remove(member.id)?.cancel()
            joinGuards[member.id] = viewModelScope.launch {
                if (!joinWithTv(member, leader)) showMessage("${member.name} kept going back to TV audio")
            }
            return
        }
        viewModelScope.launch {
            val ok = runCatching { repo.setGrouped(leader.id, member.id, join) }.getOrDefault(false)
            if (!ok) { showMessage("Couldn't ${if (join) "add" else "remove"} ${member.name}"); refreshSpeakers() }
        }
    }

    /** The room's TV toggle. Turning it on for a room already in the group wakes its TV now. */
    fun toggleTv(room: MaPlayer) {
        val on = !_ui.value.tvOn(room)
        _ui.update { it.copy(tvToggles = if (on) it.tvToggles + room.name else it.tvToggles - room.name) }
        viewModelScope.launch {
            val leader = _ui.value.selectedPlayer
            if (on && leader != null && _ui.value.inGroupOf(room, leader)) {
                joinGuards.remove(room.id)?.cancel()
                joinGuards[room.id] = viewModelScope.launch { joinWithTv(room, leader) }
            }
        }
    }

    private suspend fun speaker(id: String) = runCatching { repo.speakers() }.getOrNull()?.firstOrNull { it.id == id }

    private suspend fun waitFor(ms: Long, test: suspend () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) { if (test()) return true; delay(700) }
        return false
    }

    /**
     * Joins [room] to [leader]'s group with its TV on. The TV comes on over CEC and its Sonos
     * then switches to TV input, which drops it out of a group it already joined (Joe's first
     * test, Family Room -> Gym, 2026-10-26). So: wake the TV, wait for the Sonos to go to TV
     * (or ~10s), then join; for 30s after, re-join whenever it flips back to TV or drops out.
     * Returns true when the join held (in the group at the end, for the last 5s at least).
     */
    private suspend fun joinWithTv(room: MaPlayer, leader: MaPlayer): Boolean {
        if (tv.tvOn(room.name)) {
            showMessage("Waking the ${room.name} TV")
            waitFor(10_000) { speaker(room.id)?.isTvAudio == true }
        } else {
            showMessage("Couldn't wake the ${room.name} TV")
        }
        runCatching { repo.setGrouped(leader.id, room.id, true) }
        var lastJoinAt = SystemClock.elapsedRealtime()
        var inGroupSince: Long? = null
        val until = lastJoinAt + 30_000
        while (SystemClock.elapsedRealtime() < until) {
            delay(1_500)
            val all = runCatching { repo.speakers() }.getOrNull() ?: continue
            val r = all.firstOrNull { it.id == room.id } ?: continue
            if (all.firstOrNull { it.id == leader.id }?.isPlaying != true) return false
            val now = SystemClock.elapsedRealtime()
            if (r.syncedTo == leader.id) {
                if (inGroupSince == null) inGroupSince = now
                continue
            }
            inGroupSince = null
            // Give each join a moment to land before sending it again.
            if (now - lastJoinAt > 3_000) {
                runCatching { repo.setGrouped(leader.id, room.id, true) }
                lastJoinAt = now
            }
        }
        val since = inGroupSince
        return since != null && SystemClock.elapsedRealtime() - since >= 5_000
    }

    /**
     * "Move to <room>": the music carries on in [target] alone, same song and position.
     * 1. [target] joins the playing room's group. "With TV": its TV wakes into Xadarr Music
     *    first, then the speaker joins and must stay joined ([joinWithTv]); "speaker only", or a
     *    room without a TV, just joins;
     * 2. the starting room's TV goes off (always, when it has one); 3. the starting room leaves the group -- Music
     * Assistant hands the queue to [target] at the current position (a second's gap at most).
     */
    fun moveTo(target: MaPlayer, withTv: Boolean) {
        val from = _ui.value.selectedPlayer ?: return showMessage("Pick a room first")
        if (target.id == from.id || _ui.value.moving != null) return
        if (!from.isPlaying || !from.playsMaQueue) return showMessage("Nothing playing on ${from.name} to move")
        _ui.update { it.copy(moving = target.name) }
        showMessage("Moving to ${target.name}…")
        viewModelScope.launch {
            try {
                // 1. Group the target. "With TV": wake it first, join once its Sonos has gone to
                // TV, and keep re-joining for 30s; carry on only if the join held.
                if (withTv && _ui.value.hasTv(target)) {
                    if (!joinWithTv(target, from)) {
                        showMessage("${target.name} didn't stay in the group · music stays on ${from.name}")
                        return@launch
                    }
                } else {
                    if (speaker(target.id)?.syncedTo != from.id) runCatching { repo.setGrouped(from.id, target.id, true) }
                    if (!waitFor(10_000) { speaker(target.id)?.syncedTo == from.id }) {
                        showMessage("${target.name} didn't join · music stays on ${from.name}")
                        return@launch
                    }
                }
                // 2. The starting room's TV off.
                if (_ui.value.hasTv(from)) tv.tvOff(from.name)
                // 3. The starting room leaves; MA moves the queue to a remaining member.
                repo.send("players/cmd/ungroup", JSONObject().put("player_id", from.id))
                waitFor(12_000) { speaker(target.id)?.let { it.syncedTo != from.id } ?: false }
                val leader = speaker(target.id)?.let { it.syncedTo ?: it.id } ?: target.id
                selectPlayer(leader, follow = target.id)
                loadAll()
                showMessage("Now playing on ${target.name}")
            } finally {
                _ui.update { it.copy(moving = null) }
            }
        }
    }

    // ── Controls ─────────────────────────────────────────────────────────────
    // All of these just tell Music Assistant what to do; the speakers do the playing.

    private val queueId: String? get() = _ui.value.queue?.id ?: _ui.value.selectedPlayerId

    fun playPause() {
        val id = queueId ?: return
        // Optimistic flip so the button responds instantly; the queue_updated event confirms.
        _ui.value.queue?.let { q ->
            applyQueue(q.copy(state = if (q.state == "playing") "paused" else "playing", elapsedSec = currentElapsed()))
        }
        repo.fire("player_queues/play_pause", JSONObject().put("queue_id", id))
    }

    fun next() = queueId?.let { repo.fire("player_queues/next", JSONObject().put("queue_id", it)) }
    fun previous() = queueId?.let { repo.fire("player_queues/previous", JSONObject().put("queue_id", it)) }

    fun seekTo(positionSec: Double) {
        val id = queueId ?: return
        val target = positionSec.coerceAtLeast(0.0)
        _ui.update { it.copy(elapsedSec = target, elapsedAtMs = SystemClock.elapsedRealtime()) }
        repo.fire("player_queues/seek", JSONObject().put("queue_id", id).put("position", target.toInt()))
    }

    fun seekBy(deltaSec: Int) {
        val id = queueId ?: return
        val duration = _ui.value.queue?.current?.durationSec ?: 0
        val target = (currentElapsed() + deltaSec).coerceIn(0.0, if (duration > 0) duration.toDouble() - 1 else Double.MAX_VALUE)
        _ui.update { it.copy(elapsedSec = target, elapsedAtMs = SystemClock.elapsedRealtime()) }
        repo.fire("player_queues/seek", JSONObject().put("queue_id", id).put("position", target.toInt()))
    }

    fun toggleShuffle() {
        val q = _ui.value.queue ?: return
        applyQueue(q.copy(shuffle = !q.shuffle, elapsedSec = currentElapsed()))
        repo.fire("player_queues/shuffle", JSONObject().put("queue_id", q.id).put("shuffle_enabled", !q.shuffle))
    }

    fun cycleRepeat() {
        val q = _ui.value.queue ?: return
        val next = when (q.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }
        applyQueue(q.copy(repeat = next, elapsedSec = currentElapsed()))
        repo.fire("player_queues/repeat", JSONObject().put("queue_id", q.id).put("repeat_mode", next))
    }

    /** [announce]: say the new level (hardware volume keys, where nothing on screen shows it). */
    fun changeVolume(delta: Int, announce: Boolean = false) {
        val p = _ui.value.selectedPlayer ?: return
        val target = ((p.effectiveVolume ?: 0) + delta).coerceIn(0, 100)
        if (announce) showMessage("${p.name} · Volume $target")
        _ui.update { s ->
            s.copy(players = s.players.map {
                if (it.id != p.id) it else if (it.isGroupLeader) it.copy(groupVolume = target) else it.copy(volume = target)
            })
        }
        if (p.isGroupLeader) {
            repo.fire("players/cmd/group_volume", JSONObject().put("player_id", p.id).put("volume_level", target))
        } else {
            repo.fire("players/cmd/volume_set", JSONObject().put("player_id", p.id).put("volume_level", target))
        }
    }

    fun playQueueIndex(index: Int) {
        val id = queueId ?: return
        repo.fire("player_queues/play_index", JSONObject().put("queue_id", id).put("index", index))
    }

    fun currentElapsed(): Double {
        val s = _ui.value
        if (!s.isPlaying || s.elapsedAtMs == 0L) return s.elapsedSec
        return s.elapsedSec + (SystemClock.elapsedRealtime() - s.elapsedAtMs) / 1000.0
    }

    // ── Browse & search ──────────────────────────────────────────────────────

    // The Library home, in sections instead of one long list (Joe, 2026-10-05: "I just want to
    // play and not always hear the same shit"). Each section shows a few rows plus "See all";
    // the full lists are kept here for that.
    private var homeItems: List<MaMediaItem> = emptyList()
    private val sectionLists = mutableMapOf<String, List<MaMediaItem>>()

    fun loadLibraryHome() {
        if (homeItems.isNotEmpty()) {
            _ui.update { it.copy(browse = BrowseState(items = homeItems)) }
            return
        }
        _ui.update { it.copy(browse = BrowseState(loading = true)) }
        viewModelScope.launch {
            suspend fun list(command: String, args: JSONObject) =
                runCatching { repo.command(command, args) as? JSONArray }.getOrNull()
            val recentJob = async { list("music/recently_played_items", JSONObject().put("limit", 24)) }
            val playlistsJob = async { list("music/playlists/library_items", JSONObject().put("limit", 300).put("order_by", "last_played_desc")) }
            val albumsJob = async { list("music/albums/library_items", JSONObject().put("limit", 24).put("order_by", "random")) }
            val recent = recentJob.await()
            val playlistsArr = playlistsJob.await()
            val albums = albumsJob.await()

            // Split playlists: the ones you made (editable), Spotify's and MA's own mixes, and
            // ones you follow from other people.
            val yours = mutableListOf<MaMediaItem>()
            val madeForYou = mutableListOf<MaMediaItem>()
            val following = mutableListOf<MaMediaItem>()
            for (i in 0 until (playlistsArr?.length() ?: 0)) {
                val o = playlistsArr?.optJSONObject(i) ?: continue
                val item = MaParse.mediaItem(o, repo) ?: continue
                val owner = o.optString("owner")
                when {
                    o.optBoolean("is_editable") -> yours += item.copy(subtitle = "Playlist")
                    owner == "Spotify" || owner == "Music Assistant" || o.optBoolean("is_dynamic") -> madeForYou += item.copy(subtitle = owner.ifBlank { "Mix" })
                    else -> following += item.copy(subtitle = owner.ifBlank { "Playlist" })
                }
            }

            fun action(id: String, name: String, subtitle: String, section: String? = null) =
                MaMediaItem(uri = MA_ACTION_PREFIX + id, name = name, subtitle = subtitle, mediaType = "action",
                    imageUrl = null, browsePath = null, section = section)

            val items = mutableListOf<MaMediaItem>()
            fun section(key: String, title: String, all: List<MaMediaItem>, show: Int) {
                if (all.isEmpty()) return
                sectionLists[key] = all
                items += all.take(show).mapIndexed { i, it -> if (i == 0) it.copy(section = title) else it }
                if (all.size > show) items += action("seeall/$key", "See all ${all.size}", title)
            }

            items += action("fresh", "Fresh mix", "Songs you rarely hear, shuffled", section = "Start something")
            items += action("shuffle", "Shuffle everything", "Your whole library, in random order")
            items += action("radio", "Surprise radio", "Endless radio from a random artist in your library")
            items += action("new", "Newly added", "The latest songs in your library")
            section("recent", "Recently played", MaParse.mediaItems(recent, repo), 5)
            section("yours", "Your playlists", yours, 6)
            section("foryou", "Made for you", madeForYou, 6)
            section("albums", "Albums to try", MaParse.mediaItems(albums, repo), 5)
            section("following", "Following", following, 4)
            // Entry into the full provider tree (Spotify, radio, library folders...).
            items += MaMediaItem(uri = "", name = "Browse all sources", subtitle = "Spotify, radio, library folders",
                mediaType = "folder", imageUrl = null, browsePath = "root", section = "More")

            if (playlistsArr != null) homeItems = items
            _ui.update {
                it.copy(browse = BrowseState(items = items,
                    error = if (playlistsArr == null && recent == null) "Couldn't load the library" else null))
            }
        }
    }

    fun openFolder(item: MaMediaItem) {
        val path = item.browsePath ?: return
        val stack = _ui.value.browse.pathStack + path
        browse(stack, item.name)
    }

    /** Returns false when already at the Library home (so Back can leave the tab). */
    fun browseBack(): Boolean {
        val b = _ui.value.browse
        if (b.query.isNotBlank() || b.sectionList) { loadLibraryHome(); return true }
        if (b.pathStack.isEmpty()) return false
        val stack = b.pathStack.dropLast(1)
        if (stack.isEmpty()) loadLibraryHome() else browse(stack, "Browse")
        return true
    }

    private fun browse(stack: List<String>, title: String) {
        _ui.update { it.copy(browse = BrowseState(title = title, pathStack = stack, loading = true)) }
        viewModelScope.launch {
            val path = stack.last()
            val arr = runCatching {
                repo.command("music/browse", JSONObject().apply {
                    if (path != "root") put("path", path)
                    _ui.value.selectedPlayerId?.let { put("player_id", it) }
                }) as? JSONArray
            }.getOrNull()
            _ui.update {
                it.copy(browse = it.browse.copy(items = MaParse.mediaItems(arr, repo), loading = false,
                    error = if (arr == null) "Couldn't open this folder" else null))
            }
        }
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isBlank()) { loadLibraryHome(); return }
        _ui.update { it.copy(browse = BrowseState(title = "Search: $q", query = q, loading = true)) }
        viewModelScope.launch {
            val res = runCatching {
                repo.command("music/search", JSONObject().put("search_query", q).put("limit", 12)
                    .put("media_types", JSONArray(listOf("playlist", "artist", "album", "track", "radio")))) as? JSONObject
            }.getOrNull()
            // Playlists/albums first: on a TV you usually want to start something long-running.
            val items = listOf("playlists", "albums", "artists", "tracks", "radio").flatMap { key ->
                MaParse.mediaItems(res?.optJSONArray(key), repo)
            }
            _ui.update {
                it.copy(browse = it.browse.copy(items = items, loading = false,
                    error = when { res == null -> "Search failed"; items.isEmpty() -> "Nothing found for \"$q\""; else -> null }))
            }
        }
    }

    /** Plays a browse/search pick on the selected zone, replacing what's playing. */
    fun play(item: MaMediaItem) {
        if (item.isFolder || item.uri.isBlank()) { openFolder(item); return }
        if (item.uri.startsWith(MA_ACTION_PREFIX + "seeall/")) {
            val key = item.uri.substringAfterLast('/')
            _ui.update { it.copy(browse = BrowseState(title = item.subtitle ?: "Library", sectionList = true,
                items = sectionLists[key].orEmpty().map { i -> i.copy(section = null) })) }
            return
        }
        val player = _ui.value.selectedPlayer ?: return showMessage("Pick a zone first")
        val clash = _ui.value.players.roomClashFor(player)
        if (clash.playing.isNotEmpty()) {
            _ui.update { it.copy(busyPrompt = MusicBusyPrompt(item, player, clash.playing)) }
            return
        }
        startPlay(item, player, clash.paused.map { it.id }, null)
    }

    /** The busy-room prompt's answer: [joinBusyRoom] groups into it, else the busy rooms stop. */
    fun resolveBusyPrompt(joinBusyRoom: Boolean?) {
        val prompt = _ui.value.busyPrompt ?: return
        _ui.update { it.copy(busyPrompt = null) }
        if (joinBusyRoom == null) return
        val paused = _ui.value.players.roomClashFor(prompt.target).paused.map { it.id }
        if (joinBusyRoom) startPlay(prompt.item, prompt.target, paused, prompt.playing.first().id)
        else startPlay(prompt.item, prompt.target, prompt.playing.map { it.id } + paused, null)
    }

    private fun startPlay(item: MaMediaItem, player: MaPlayer, stopFirst: List<String>, joinLeaderId: String?) {
        val joined = joinLeaderId?.let { id -> _ui.value.players.firstOrNull { it.id == id } }
        showMessage("Playing ${item.name} on ${joined?.let { "${it.name} + ${player.name}" } ?: player.name}")
        _ui.update { it.copy(tab = MusicTab.NOW_PLAYING) }
        viewModelScope.launch {
            val (media, radio) = resolveMedia(item) ?: return@launch showMessage("Couldn't start ${item.name}")
            val target = runCatching { repo.clearWayFor(player.id, stopFirst, joinLeaderId) }.getOrDefault(player.id)
            repo.fire("player_queues/play_media", JSONObject()
                .put("queue_id", if (target == player.id) _ui.value.queue?.id ?: player.id else target)
                .put("media", media)
                .put("option", "replace")
                .put("radio_mode", radio))
            if (target != player.id) selectPlayer(target)
        }
    }

    /** What to hand MA for [item]: its own uri, or for a home shortcut, a freshly drawn set of songs. */
    private suspend fun resolveMedia(item: MaMediaItem): Pair<JSONArray, Boolean>? {
        if (!item.isAction) return JSONArray().put(item.uri) to false
        suspend fun tracks(orderBy: String, limit: Int): JSONArray? {
            val arr = runCatching {
                repo.command("music/tracks/library_items", JSONObject().put("limit", limit).put("order_by", orderBy)) as? JSONArray
            }.getOrNull() ?: return null
            val uris = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("uri")?.takeIf { u -> u.isNotBlank() } }
            return uris.takeIf { it.isNotEmpty() }?.let { JSONArray(it) }
        }
        return when (item.uri.removePrefix(MA_ACTION_PREFIX)) {
            // MA's random_play_count: random, weighted toward songs played least.
            "fresh" -> tracks("random_play_count", 80)?.let { it to false }
            "shuffle" -> tracks("random", 150)?.let { it to false }
            "new" -> tracks("timestamp_added_desc", 60)?.let { it to false }
            "radio" -> {
                val artist = runCatching {
                    (repo.command("music/artists/library_items", JSONObject().put("limit", 1).put("order_by", "random")) as? JSONArray)?.optJSONObject(0)
                }.getOrNull() ?: return null
                showMessage("Surprise radio: ${artist.optString("name")}")
                JSONArray().put(artist.optString("uri")) to true
            }
            else -> null
        }
    }

    /** MA's "play radio of": an endless mix seeded from the playing track, replacing the queue. */
    fun startRadioFromCurrent() {
        val track = _ui.value.queue?.current ?: return showMessage("Nothing playing")
        val uri = track.uri ?: return showMessage("Can't start radio from this track")
        val playerId = _ui.value.selectedPlayerId ?: return showMessage("Pick a zone first")
        repo.fire("player_queues/play_media", JSONObject()
            .put("queue_id", _ui.value.queue?.id ?: playerId)
            .put("media", JSONArray().put(uri))
            .put("option", "replace")
            .put("radio_mode", true))
        showMessage("Starting ${track.name} radio")
    }

    private fun showMessage(text: String) {
        messageJob?.cancel()
        _ui.update { it.copy(message = text) }
        messageJob = viewModelScope.launch {
            delay(3_000)
            _ui.update { it.copy(message = null) }
        }
    }

    fun retry() = repo.reconnectNow()
}
