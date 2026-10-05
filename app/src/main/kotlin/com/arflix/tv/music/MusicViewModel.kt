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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

enum class MusicTab { NOW_PLAYING, QUEUE, ZONES, BROWSE }

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
    /** A pick waiting on "another room has the Spotify stream" (see [roomClashFor]). */
    val busyPrompt: MusicBusyPrompt? = null,
) {
    val selectedPlayer: MaPlayer? get() = players.firstOrNull { it.id == selectedPlayerId }
    val isPlaying: Boolean get() = (queue?.state ?: selectedPlayer?.state) == "playing"
}

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val repo: MusicAssistantRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(MusicUiState())
    val ui: StateFlow<MusicUiState> = _ui.asStateFlow()

    private var queueRefreshJob: Job? = null
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
        val saved = repo.selectedPlayerId()
        val selected = visible.firstOrNull { it.id == saved }?.id
            ?: visible.firstOrNull { it.isPlaying && !it.isTvAudio }?.id
            ?: visible.firstOrNull()?.id
        _ui.update { it.copy(players = visible, selectedPlayerId = selected) }
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
                    val sel = s.selectedPlayerId.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
                    s.copy(players = list, selectedPlayerId = sel)
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

    fun selectPlayer(id: String) {
        if (id == _ui.value.selectedPlayerId) return
        _ui.update { it.copy(selectedPlayerId = id, queue = null, queueItems = emptyList()) }
        viewModelScope.launch {
            repo.saveSelectedPlayerId(id)
            loadQueue(id)
        }
    }

    fun setTab(tab: MusicTab) = _ui.update { it.copy(tab = tab) }

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

    fun changeVolume(delta: Int) {
        val p = _ui.value.selectedPlayer ?: return
        val target = ((p.effectiveVolume ?: 0) + delta).coerceIn(0, 100)
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
            val playlistsJob = async { list("music/playlists/library_items", JSONObject().put("limit", 300).put("order_by", "sort_name")) }
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
