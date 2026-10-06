package com.arflix.tv.ui.screens.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.GroupState
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvSnapshot
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.DispatcharrCatalogRepository
import com.arflix.tv.data.repository.IptvConfig
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.IptvTvSessionState
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.PinnedProviderChannelsRepository
import com.arflix.tv.data.repository.ProgramReminder
import com.arflix.tv.data.repository.ProgramReminderRepository
import com.arflix.tv.data.repository.RawProviderStream
import com.arflix.tv.data.model.IptvProgram
import com.arflix.tv.ui.screens.tv.live.FavoriteSortMode
import com.arflix.tv.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal const val FAVORITES_GROUP_NAME = "My Favorites"

data class TvUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val loadingMessage: String? = null,
    val loadingPercent: Int = 0,
    val config: IptvConfig = IptvConfig(),
    val snapshot: IptvSnapshot = IptvSnapshot(),
    val channelLookup: Map<String, IptvChannel> = emptyMap(),
    val groups: List<String> = emptyList(),
    val channelsByGroup: Map<String, List<IptvChannel>> = emptyMap(),
    val tvSession: IptvTvSessionState = IptvTvSessionState(),
    val iptvPreferencesLoaded: Boolean = false,
    val tvSessionLoaded: Boolean = false,
    val favoritesOnly: Boolean = false,
    val query: String = "",
    val groupBlacklistEnabled: Boolean = false,
    val isRefreshingPlaylist: Boolean = false,
    val playlistRefreshResult: String? = null,
) {
    val isConfigured: Boolean get() =
        config.m3uUrl.isNotBlank() ||
            config.stalkerPortalUrl.isNotBlank() ||
            config.playlists.any { it.enabled && it.m3uUrl.isNotBlank() }
}

/**
 * Guide state that must outlive a single TvViewModel: Home's back-stack entry (and so its
 * ViewModel) is recreated on every return to the guide. Process lifetime, so a cold start
 * still loads fresh.
 */
object GuideSessionCache {
    @Volatile var enrichedChannels: Any? = null
    @Volatile var channelsSignature: String? = null
    @Volatile var shows: List<com.arflix.tv.data.repository.ShowGuideEntry> = emptyList()
    @Volatile var movies: com.arflix.tv.data.repository.MovieGuide = com.arflix.tv.data.repository.MovieGuide()
    @Volatile var musicPlaylists: List<com.arflix.tv.music.MaMediaItem> = emptyList()
    @Volatile var musicTracks: Map<String, com.arflix.tv.music.MaPlaylistTracks> = emptyMap()
}

@HiltViewModel
class TvViewModel @Inject constructor(
    val iptvRepository: IptvRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    val dispatcharrCatalogRepository: DispatcharrCatalogRepository,
    private val pinnedProviderChannelsRepository: PinnedProviderChannelsRepository,
    private val programReminderRepository: ProgramReminderRepository,
    private val mediaRepository: MediaRepository,
    private val remoteModeRepository: com.arflix.tv.data.repository.RemoteModeRepository,
    private val lanSyncService: com.arflix.tv.data.repository.LanSyncService,
    private val tvRemoteService: com.arflix.tv.data.repository.tvremote.TvRemoteService,
    private val remoteVolumeRouter: com.arflix.tv.data.repository.tvremote.RemoteVolumeRouter,
    private val homeAssistantRepository: com.arflix.tv.data.repository.HomeAssistantRepository,
    private val remoteCommandBus: com.arflix.tv.data.repository.RemoteCommandBus,
    private val sonarrRepository: com.arflix.tv.data.repository.SonarrRepository,
    private val radarrRepository: com.arflix.tv.data.repository.RadarrRepository,
    private val musicAssistantRepository: com.arflix.tv.music.MusicAssistantRepository,
    private val homeServerRepository: com.arflix.tv.data.repository.HomeServerRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvUiState())
    val uiState: StateFlow<TvUiState> = _uiState.asStateFlow()

    // Remote Mode — when a target is set, channel selection dispatches to that device
    // instead of tuning locally. See RemoteModeRepository / RemoteCommandBus.
    val remoteTarget: StateFlow<com.arflix.tv.data.repository.LanPeer?> = remoteModeRepository.target

    suspend fun sendRemoteTuneChannel(epgId: String): Boolean = remoteModeRepository.sendTuneChannel(epgId)

    // Live NSD peers merged with paired-but-currently-unreachable devices — see the matching
    // comment in RemoteModeViewModel.peers for why (a sleeping TV drops out of lanSyncService's
    // live list well before you'd want to give up on reaching its Power button).
    val remoteLanPeers: StateFlow<List<com.arflix.tv.data.repository.LanPeer>> =
        combine(lanSyncService.peers, tvRemoteService.pairedPeers) { live, paired ->
            val liveHosts = live.map { it.host }.toSet()
            live + paired.filterNot { it.host in liveHosts }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun setRemoteTarget(peer: com.arflix.tv.data.repository.LanPeer?) = remoteModeRepository.setTarget(peer)
    suspend fun sendRemoteDpad(key: com.arflix.tv.data.repository.DPadKey): Boolean = remoteModeRepository.sendDpad(key)
    suspend fun sendRemoteText(text: String): Boolean = remoteModeRepository.sendText(text)

    // Android TV Remote Service — real system-level volume. See RemoteModeViewModel for the
    // same wiring on the global panel; this is the Guide's own "Remote" pill sheet.
    suspend fun isTvRemotePaired(host: String): Boolean = tvRemoteService.isPaired(host)
    fun startTvRemotePairing(): com.arflix.tv.data.repository.tvremote.TvRemotePairingClient = tvRemoteService.startPairing()
    suspend fun onTvRemotePairingFinished(host: String, deviceName: String) =
        tvRemoteService.onPairingFinished(host, deviceName)

    // ── Universal remote (Guide's own "Remote" pill) ────────────────────────
    // Mirrors RemoteModeViewModel: the control device decides what the buttons drive, and is kept
    // separate from remoteTarget so hopping over to the TV never redirects tune/play.
    // Shared via the repository — the swipe-down panel is the same remote, and must agree.
    val controlDevice: StateFlow<com.arflix.tv.data.repository.tvremote.RemoteDevice?> =
        remoteModeRepository.controlDevice

    private val _haDevices = MutableStateFlow<List<com.arflix.tv.data.repository.tvremote.RemoteDevice>>(emptyList())

    val remoteDevices: StateFlow<List<com.arflix.tv.data.repository.tvremote.RemoteDevice>> =
        combine(remoteLanPeers, _haDevices) { xadarr, ha ->
            // Merged so one physical device is one entry — see RemoteModeViewModel.devices.
            com.arflix.tv.data.repository.tvremote.RemoteDevice.merge(
                xadarr.map { com.arflix.tv.data.repository.tvremote.RemoteDevice.fromPeer(it) },
                ha,
            )
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun setControlDevice(device: com.arflix.tv.data.repository.tvremote.RemoteDevice?) =
        remoteModeRepository.setControlDevice(device)

    /**
     * Flip between watching here and driving the last device — the guide's Remote pill long-press.
     *
     * Falls back through the live peer list rather than only the merged device flow: the merged
     * flow can legitimately be empty when nothing is observing it, and a toggle that silently does
     * nothing reads as a broken button.
     */
    fun toggleRemoteLocal() {
        val active = controlDevice.value != null || remoteTarget.value != null
        if (active) {
            setControlDevice(null)
            return
        }
        val next = remoteModeRepository.lastControlDevice
            ?: remoteDevices.value.firstOrNull { it.isXadarr }
            ?: lanSyncService.peers.value.firstOrNull()
                ?.let { com.arflix.tv.data.repository.tvremote.RemoteDevice.fromPeer(it) }
        if (next != null) setControlDevice(next)
    }

    suspend fun loadRemoteDevices() {
        _haDevices.value = com.arflix.tv.data.repository.tvremote.RemoteDevice
            .fromHaEntities(homeAssistantRepository.getControlEntities())
    }

    private fun activeRemoteDevice(): com.arflix.tv.data.repository.tvremote.RemoteDevice? =
        controlDevice.value ?: remoteTarget.value?.let {
            com.arflix.tv.data.repository.tvremote.RemoteDevice.fromPeer(it)
        }

    suspend fun sendRemoteKey(key: com.arflix.tv.data.repository.DPadKey): Boolean {
        val device = activeRemoteDevice() ?: return false
        val host = device.peer?.host
        if (host != null) {
            // System-level first — see RemoteModeViewModel.sendKey for why.
            if (tvRemoteService.sendDpadKey(host, key)) return true
            return remoteModeRepository.sendDpad(key)
        }
        return remoteVolumeRouter.sendKey(device, key)
    }

    suspend fun sendRemoteVolumeUp(): Boolean {
        val device = activeRemoteDevice() ?: return false
        return remoteVolumeRouter.volumeUp(device).takeIf { it }
            ?: if (device.isXadarr) remoteModeRepository.sendDpad(com.arflix.tv.data.repository.DPadKey.VOLUME_UP) else false
    }

    suspend fun sendRemoteVolumeDown(): Boolean {
        val device = activeRemoteDevice() ?: return false
        return remoteVolumeRouter.volumeDown(device).takeIf { it }
            ?: if (device.isXadarr) remoteModeRepository.sendDpad(com.arflix.tv.data.repository.DPadKey.VOLUME_DOWN) else false
    }

    suspend fun sendRemotePower(): Boolean =
        activeRemoteDevice()?.let { remoteVolumeRouter.togglePower(it) } ?: false

    suspend fun selectRemoteInput(source: String): Boolean =
        activeRemoteDevice()?.let { remoteVolumeRouter.selectInput(it, source) } ?: false

    suspend fun remoteProfileFor(deviceId: String) = remoteVolumeRouter.profileFor(deviceId)
    suspend fun setRemoteProfile(deviceId: String, profile: com.arflix.tv.data.repository.tvremote.RemoteVolumeRouter.DeviceProfile) =
        remoteVolumeRouter.setProfile(deviceId, profile)

    // Derived from the merged device list so each physical device appears once — see
    // RemoteModeViewModel.loadSpeakers.
    suspend fun loadRemoteSpeakers(): List<com.arflix.tv.ui.components.HaSpeaker> {
        if (_haDevices.value.isEmpty()) loadRemoteDevices()
        return _haDevices.value.mapNotNull { device ->
            device.volumeEntity?.let { com.arflix.tv.ui.components.HaSpeaker(it, device.displayName) }
        }
    }

    // Receiving side, delivered directly rather than via navigation args: when this device is
    // the Remote Mode target and this screen (Home/the guide) is already composed and already
    // showing a channel, re-navigating to Home with a new channelId query param is a singleTop
    // reuse of the same backStackEntry — Compose does not recompose just because a Bundle
    // argument changed, so a rememberSaveable-seeded-once channel id never picks up the new
    // value (confirmed on-device: nav fires, screen flashes, channel doesn't change). This
    // ViewModel is scoped to that same backStackEntry, so LiveTvScreen can collect commands
    // directly here and react regardless of whether the screen was already showing.
    val incomingRemoteCommands: kotlinx.coroutines.flow.SharedFlow<com.arflix.tv.data.repository.RemoteCommand> =
        remoteCommandBus.incoming

    val pinnedProviderChannels: StateFlow<List<RawProviderStream>> =
        pinnedProviderChannelsRepository.observePinned()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    // Synthetic "Shows" guide channel data (see project_nostalgex_style_media_channel memory) —
    // one-shot fetch, not a Flow, since Episeerr caches this server-side already
    // (_GUIDE_SCHEDULE_TTL_SECONDS in xadarr.py) and re-fetching on every recomposition would
    // just hit that cache repeatedly for no benefit. Refreshed by calling refreshShowsGuide()
    // (e.g. alongside a manual playlist refresh), not automatically.
    private val _showsGuideSchedule =
        kotlinx.coroutines.flow.MutableStateFlow<List<com.arflix.tv.data.repository.ShowGuideEntry>>(GuideSessionCache.shows)
    val showsGuideSchedule: StateFlow<List<com.arflix.tv.data.repository.ShowGuideEntry>> =
        _showsGuideSchedule.asStateFlow()

    // Download progress for each show's not-yet-downloaded next episode, from the same Episeerr
    // series-status endpoint the Details screen's episode badges use (Joe, 2026-10-05: "it
    // should show the progress", and "don't reinvent this"). Merged over the cached schedule:
    // the schedule is cached for minutes server-side, progress moves by the second.
    private var showsBase: List<com.arflix.tv.data.repository.ShowGuideEntry> = GuideSessionCache.shows
    private var nextDownloads: Map<Int, Float> = emptyMap()   // seriesId -> 0-100
    private val refreshedForDone = mutableSetOf<String>()
    private var showDownloadPollJob: kotlinx.coroutines.Job? = null

    private fun publishShows() {
        _showsGuideSchedule.value = showsBase.map { e ->
            val n = e.next
            val p = nextDownloads[e.seriesId]
            if (n == null || n.downloaded || p == null) e else e.copy(next = n.copy(downloadProgress = p))
        }
    }

    /** Every 15s re-checks what's downloading; once a minute (every 4th pass) checks every aired, missing next episode. */
    private fun ensureShowDownloadPoll() {
        if (showDownloadPollJob?.isActive == true) return
        showDownloadPollJob = viewModelScope.launch {
            var pass = 0
            while (true) {
                val now = System.currentTimeMillis()
                val candidates = showsBase.filter { e ->
                    val n = e.next ?: return@filter false
                    if (n.downloaded || e.tvdbId == null) return@filter false
                    // Only released episodes can be downloading.
                    val aired = com.arflix.tv.ui.screens.tv.live.parseShowAirDate(n.airDate)?.toEpochMilli() ?: return@filter false
                    aired <= now && (pass % 4 == 0 || e.seriesId in nextDownloads)
                }
                val found = nextDownloads.toMutableMap().apply { keys.retainAll(showsBase.map { it.seriesId }.toSet()) }
                var finished = false
                for (e in candidates) {
                    val n = e.next ?: continue
                    val info = runCatching { sonarrRepository.getEpisodeStatuses(e.tvdbId.toString(), n.season) }
                        .getOrNull()?.get(n.episode)
                    when (info?.status) {
                        com.arflix.tv.data.repository.SonarrEpisodeStatus.QUEUED -> found[e.seriesId] = info.downloadProgress
                        com.arflix.tv.data.repository.SonarrEpisodeStatus.AVAILABLE -> {
                            found.remove(e.seriesId)
                            // Once per episode: the schedule may lag a moment behind series-status.
                            if (refreshedForDone.add("${e.seriesId}:${n.season}:${n.episode}")) finished = true
                        }
                        null -> Unit
                        else -> found.remove(e.seriesId)
                    }
                }
                if (found != nextDownloads) {
                    nextDownloads = found
                    publishShows()
                }
                // Finished downloading: reload so the cell becomes the episode title.
                if (finished) refreshShowsGuide(forceRefresh = true)
                pass++
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    // Selecting a synthetic Shows-channel row/program navigates to Details instead of trying to
    // play a raw stream URL (there isn't one) -- Details already has the real, tested episode
    // resolution + VOD playback path (resolveAvailablePlayTarget et al.), so this reuses it
    // rather than duplicating stream-resolution logic inside the live guide. mediaRepository is
    // private to this ViewModel, hence this thin public wrapper for LiveTvScreen to call.
    suspend fun resolveShowTmdbRef(tvdbId: Int): Pair<com.arflix.tv.data.model.MediaType, Int>? =
        runCatching { mediaRepository.resolveTvdbToTmdbRef(tvdbId, com.arflix.tv.data.model.MediaType.TV) }.getOrNull()

    // Synthetic Movies channels ("Watch Now" linear schedule + "Premiering"), refreshed together
    // with the Shows data.
    private val _movieGuide =
        kotlinx.coroutines.flow.MutableStateFlow(GuideSessionCache.movies)
    val movieGuide: StateFlow<com.arflix.tv.data.repository.MovieGuide> = _movieGuide.asStateFlow()

    fun refreshShowsGuide(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val shows = runCatching { sonarrRepository.getShowsGuideSchedule(forceRefresh = forceRefresh) }
                .getOrDefault(emptyList())
            // A failed fetch keeps what we had rather than blanking the Shows rows.
            if (shows.isNotEmpty() || GuideSessionCache.shows.isEmpty()) {
                showsBase = shows
                GuideSessionCache.shows = shows
                publishShows()
                ensureShowDownloadPoll()
            }
        }
        viewModelScope.launch {
            val movies = runCatching { radarrRepository.getMovieGuide(forceRefresh) }
                .getOrDefault(com.arflix.tv.data.repository.MovieGuide())
            if (movies.movies.isNotEmpty() || movies.premiering.isNotEmpty() ||
                (GuideSessionCache.movies.movies.isEmpty() && GuideSessionCache.movies.premiering.isEmpty())
            ) {
                _movieGuide.value = movies
                GuideSessionCache.movies = movies
            }
        }
        viewModelScope.launch {
            val playlists = runCatching { musicAssistantRepository.playlists() }.getOrDefault(emptyList())
            if (playlists.isNotEmpty() || GuideSessionCache.musicPlaylists.isEmpty()) {
                _musicPlaylists.value = playlists
                GuideSessionCache.musicPlaylists = playlists
            }
        }
    }

    // Synthetic "Music" guide channels: one per Music Assistant playlist. Selecting one picks a
    // Sonos zone and starts it there through MA; the TV itself never plays the audio. Empty (so
    // the group doesn't exist) unless Music Assistant is set up.
    private val _musicPlaylists =
        kotlinx.coroutines.flow.MutableStateFlow(GuideSessionCache.musicPlaylists)
    val musicPlaylists: StateFlow<List<com.arflix.tv.music.MaMediaItem>> = _musicPlaylists.asStateFlow()

    private val _musicZones = kotlinx.coroutines.flow.MutableStateFlow<List<com.arflix.tv.music.MaPlayer>>(emptyList())
    val musicZones: StateFlow<List<com.arflix.tv.music.MaPlayer>> = _musicZones.asStateFlow()
    private val _lastMusicZoneId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val lastMusicZoneId: StateFlow<String?> = _lastMusicZoneId.asStateFlow()

    /** Fetched each time the zone picker opens, so "playing" markers are current. */
    fun refreshMusicZones() {
        viewModelScope.launch {
            _lastMusicZoneId.value = musicAssistantRepository.selectedPlayerId()
            val speakers = runCatching { musicAssistantRepository.speakers() }.getOrDefault(emptyList())
            _musicSpeakers.value = speakers
            _musicZones.value = speakers.filter { it.visible }
        }
    }

    // Every Sonos speaker, grouped ones included, for the guide's speaker-grouping menu.
    private val _musicSpeakers = kotlinx.coroutines.flow.MutableStateFlow<List<com.arflix.tv.music.MaPlayer>>(emptyList())
    val musicSpeakers: StateFlow<List<com.arflix.tv.music.MaPlayer>> = _musicSpeakers.asStateFlow()

    fun setSpeakerGrouped(leaderId: String, memberId: String, grouped: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { musicAssistantRepository.setGrouped(leaderId, memberId, grouped) }.getOrDefault(false)
            onResult(ok)
            // Sonos takes a moment to regroup before MA reports it.
            delay(1_500)
            refreshMusicZones()
        }
    }

    // Music rows' lineups in the guide grid: each playlist's own tracks (fetched once a
    // session, one playlist at a time), and the live queue of every zone that's playing.
    private val _musicTracks = kotlinx.coroutines.flow.MutableStateFlow(GuideSessionCache.musicTracks)
    val musicTracks: StateFlow<Map<String, com.arflix.tv.music.MaPlaylistTracks>> = _musicTracks.asStateFlow()
    private val _musicLineups = kotlinx.coroutines.flow.MutableStateFlow<List<com.arflix.tv.music.MaLineup>>(emptyList())
    val musicLineups: StateFlow<List<com.arflix.tv.music.MaLineup>> = _musicLineups.asStateFlow()
    private var musicTracksJob: kotlinx.coroutines.Job? = null

    fun loadMusicTracks(playlists: List<com.arflix.tv.music.MaMediaItem>) {
        if (musicTracksJob?.isActive == true) return
        musicTracksJob = viewModelScope.launch {
            playlists.filter { it.uri !in _musicTracks.value }.forEach { p ->
                val tracks = runCatching { musicAssistantRepository.playlistTracks(p.uri) }.getOrNull() ?: return@forEach
                _musicTracks.value = _musicTracks.value + (p.uri to tracks)
                GuideSessionCache.musicTracks = _musicTracks.value
            }
        }
    }

    // MA set up (URL + token), regardless of whether the playlists have loaded yet. Search used to
    // gate on the playlists, so a search right after launch had no MUSIC row.
    val musicConfigured: StateFlow<Boolean> = musicAssistantRepository.isConfigured
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    suspend fun searchMusic(query: String): List<com.arflix.tv.music.MaMediaItem> =
        runCatching { musicAssistantRepository.searchMusic(query) }.getOrDefault(emptyList())

    fun refreshMusicLineups() {
        viewModelScope.launch {
            runCatching { musicAssistantRepository.playingLineups() }.getOrNull()?.let { _musicLineups.value = it }
        }
    }

    // After a play/transfer, re-read the queues every second until [zoneId] reports playing
    // with songs. Spotify through MA often takes 5-10s to start, and a single read at 2s left
    // the Now Playing row missing until the next 30s guide tick (2026-10-05).
    private var musicLineupWatchJob: kotlinx.coroutines.Job? = null
    private fun watchMusicLineupsFor(zoneId: String) {
        musicLineupWatchJob?.cancel()
        musicLineupWatchJob = viewModelScope.launch {
            repeat(20) {
                delay(1_000)
                val lineups = runCatching { musicAssistantRepository.playingLineups() }.getOrNull() ?: return@repeat
                _musicLineups.value = lineups
                if (lineups.any { it.queueId == zoneId && !it.paused && it.tracks.isNotEmpty() }) return@launch
            }
        }
    }

    // The Sonos this TV plays through, learned from the zone that reports "TV audio" while this
    // device plays live TV. Device-local on purpose (plain SharedPreferences, never the synced
    // blob): every TV sits in a different room.
    private val musicRoomPrefs = appContext.getSharedPreferences("music_room", android.content.Context.MODE_PRIVATE)
    private val _musicRoomZoneId = kotlinx.coroutines.flow.MutableStateFlow(musicRoomPrefs.getString("zone_id", null))
    val musicRoomZoneId: StateFlow<String?> = _musicRoomZoneId.asStateFlow()
    fun setMusicRoomZone(zoneId: String) {
        if (_musicRoomZoneId.value == zoneId) return
        _musicRoomZoneId.value = zoneId
        musicRoomPrefs.edit().putString("zone_id", zoneId).apply()
    }

    // Remote volume keys while music plays on this room's speaker. Over CEC they only moved the
    // TV's own volume, which the Sonos ignores while it plays MA's music (Joe, 2026-10-05).
    // Tracks the level locally so held-down repeats step from the last press, not MA's last report.
    // Remote volume keys in the guide. Kept here (and the toast in RoomVolumeToast) rather than
    // inline in LiveTvScreen(): that composable is at the size where the Shield's Android 11
    // verifier rejects it (VerifyError crash on launch, 2026-10-03).
    private val _roomVolumeMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val roomVolumeMessage: StateFlow<String?> = _roomVolumeMessage.asStateFlow()
    fun clearRoomVolumeMessage() { _roomVolumeMessage.value = null }

    /** Same test as the guide's TV-mute: music (not paused) on this TV's room speaker or its group. */
    private fun musicPlaysInRoom(): String? {
        val roomId = _musicRoomZoneId.value ?: return null
        val room = _musicSpeakers.value.firstOrNull { it.id == roomId }
        if (room?.isTvAudio == true) return null
        return roomId.takeIf { _musicLineups.value.any { !it.paused && (it.queueId == roomId || room?.syncedTo == it.queueId) } }
    }

    /** Volume up/down while music plays in this room: drive the room's Sonos. True = consumed. */
    fun roomVolumeKey(key: androidx.compose.ui.input.key.Key, keyDown: Boolean): Boolean {
        val up = key == androidx.compose.ui.input.key.Key.VolumeUp
        if (!up && key != androidx.compose.ui.input.key.Key.VolumeDown) return false
        val roomId = musicPlaysInRoom() ?: return false
        if (keyDown) nudgeRoomVolume(roomId, if (up) 2 else -2) { room, level -> _roomVolumeMessage.value = "$room · Volume $level" }
        return true
    }

    // After a few quiet seconds MA's own report wins again (the Sonos app may have changed it).
    private var roomVolume: Pair<String, Int>? = null
    private var roomVolumeAt = 0L
    private fun nudgeRoomVolume(zoneId: String, delta: Int, onLevel: (String, Int) -> Unit) {
        val speaker = _musicSpeakers.value.firstOrNull { it.id == zoneId } ?: return
        val recent = android.os.SystemClock.elapsedRealtime() - roomVolumeAt < 4_000
        val from = roomVolume?.takeIf { recent && it.first == zoneId }?.second ?: speaker.volume ?: return
        val level = (from + delta).coerceIn(0, 100)
        roomVolume = zoneId to level
        roomVolumeAt = android.os.SystemClock.elapsedRealtime()
        onLevel(speaker.name, level)
        viewModelScope.launch {
            runCatching {
                musicAssistantRepository.send("players/cmd/volume_set", org.json.JSONObject().put("player_id", zoneId).put("volume_level", level))
            }
        }
        volumeRefreshJob?.cancel()
        volumeRefreshJob = viewModelScope.launch { delay(4_500); refreshMusicZones() }
    }
    private var volumeRefreshJob: kotlinx.coroutines.Job? = null

    /** Guide Now Playing controls: one MA command, then re-read the queues so the row follows. */
    fun musicControl(command: String, args: org.json.JSONObject, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = runCatching { musicAssistantRepository.send(command, args) }.getOrDefault(false)
            onResult(ok)
            delay(700)
            refreshMusicLineups()
            refreshMusicZones()
        }
    }

    fun transferMusicQueue(sourceQueueId: String, targetZoneId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { musicAssistantRepository.transferQueue(sourceQueueId, targetZoneId) }.getOrDefault(false)
            if (ok) _lastMusicZoneId.value = targetZoneId
            onResult(ok)
            watchMusicLineupsFor(targetZoneId)
        }
    }

    fun playMusicOn(
        zoneId: String,
        uri: String,
        radio: Boolean = false,
        startItem: String? = null,
        queueItemId: String? = null,
        // The songs the guide showed from the picked one on. Used when MA can't find the start
        // song in its own copy of the playlist -- "500 Random tracks" reshuffles on every
        // load, so start_item there failed with "No playable items found" (2026-10-03).
        fallbackUris: List<String> = emptyList(),
        // Rooms holding MA's one Spotify stream: stop them first, or join this room to one.
        stopFirst: List<String> = emptyList(),
        joinLeaderId: String? = null,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val target = runCatching { musicAssistantRepository.clearWayFor(zoneId, stopFirst, joinLeaderId) }.getOrDefault(zoneId)
            val ok = runCatching {
                when {
                    queueItemId != null -> musicAssistantRepository.playQueueItem(target, queueItemId)
                    else -> musicAssistantRepository.playOn(target, uri, radio, startItem) ||
                        (startItem != null && musicAssistantRepository.playTracks(target, fallbackUris))
                }
            }.getOrDefault(false)
            if (ok) _lastMusicZoneId.value = target
            onResult(ok)
            if (ok) watchMusicLineupsFor(target)
            if (joinLeaderId != null) refreshMusicZones()
        }
    }

    // Long-press actions on a Shows guide row. Mark watched goes straight to Plex (the watched
    // source the guide reads), then forces a fresh guide so the row moves on immediately.
    fun markShowEpisodeWatched(entry: com.arflix.tv.data.repository.ShowGuideEntry, season: Int, episode: Int) {
        viewModelScope.launch {
            val tmdbId = entry.tvdbId?.let { resolveShowTmdbRef(it)?.second }
            runCatching {
                homeServerRepository.setPlexEpisodeWatched(
                    imdbId = null, title = entry.title, season = season, episode = episode,
                    tmdbId = tmdbId, tvdbId = entry.tvdbId, watched = true,
                )
            }
            refreshShowsGuide(forceRefresh = true)
        }
    }

    fun markMovieWatched(movie: com.arflix.tv.data.repository.LibraryMovie) {
        viewModelScope.launch {
            runCatching { homeServerRepository.setPlexMovieWatched(movie.tmdbId, movie.title, movie.year, watched = true) }
            refreshShowsGuide(forceRefresh = true)
        }
    }

    fun searchMovie(radarrId: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { radarrRepository.triggerMovieSearch(radarrId) }.getOrDefault(false))
        }
    }

    fun searchShowEpisode(tvdbId: Int, season: Int, episode: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { sonarrRepository.triggerEpisodeSearch(tvdbId.toString(), season, episode) }
                .getOrDefault(false)
            onResult(ok)
        }
    }

    init {
        refreshShowsGuide()
    }

    // Observed rather than sampled once at construction: the flag is written while the Settings
    // screen loads addons, so a one-shot read meant installing the Dispatcharr bridge had no
    // effect on the guide until the app was restarted.
    val dispatcharrCatalogAvailable: StateFlow<Boolean> =
        dispatcharrCatalogRepository.isAvailableFlow()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun refreshDispatcharrCatalogAvailability() {
    }

    val programReminders: StateFlow<List<ProgramReminder>> =
        programReminderRepository.observeReminders()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun notificationsEnabled(): Boolean = programReminderRepository.notificationsEnabled()

    /** Backs the "MOVIES & SHOWS" section of [SearchOverlay] — same TMDB search used by the
     *  (currently unreachable, retired) standalone Search screen, so Live TV is the one place
     *  left in the app where general search actually works. */
    suspend fun searchMedia(query: String): List<MediaItem> =
        runCatching { mediaRepository.search(query) }.getOrDefault(emptyList())

    fun setProgramReminder(channelId: String, channelName: String, program: IptvProgram) {
        viewModelScope.launch { programReminderRepository.schedule(channelId, channelName, program) }
    }

    fun cancelProgramReminder(channelId: String, program: IptvProgram) {
        viewModelScope.launch { programReminderRepository.cancel(channelId, program) }
    }

    fun pinProviderStream(stream: RawProviderStream) {
        viewModelScope.launch { pinnedProviderChannelsRepository.pin(stream) }
    }

    fun unpinProviderStream(id: String) {
        viewModelScope.launch { pinnedProviderChannelsRepository.unpin(id) }
    }
    private var refreshJob: Job? = null
    private var epgRefreshJob: Job? = null
    private var warmVodJob: Job? = null
    private var pendingForcedReload: Boolean = false
    private var periodicEpgJob: Job? = null
    private var iptvCloudSyncJob: Job? = null
    private var lastObservedConfigSignature: String? = null
    private var lastAutomaticEpgReloadAt: Long = 0L
    private var visibleEpgRefreshJob: Job? = null
    private var lastVisibleEpgRefreshKey: String? = null
    private var lastVisibleEpgRefreshAt: Long = 0L
    private var tvSessionSaveJob: Job? = null
    private var startupGuideWarmupKey: String? = null
    private var fullEpgWarmupJob: Job? = null
    private var lastFullEpgWarmupKey: String? = null
    private var completeEpgBackfillJob: Job? = null
    private var lastCompleteEpgBackfillKey: String? = null
    private var preparedContentJob: Job? = null
    private var preparedContentRevision: Long = 0L
    private var iptvRefreshPollJob: Job? = null
    private var lastKnownIptvRefreshAt: Long = 0L

    private val _favoriteSortMode = MutableStateFlow(FavoriteSortMode.DateAdded)
    val favoriteSortMode: StateFlow<FavoriteSortMode> = _favoriteSortMode.asStateFlow()

    /**
     * In-memory cache of the live-TV enriched channel list + category tree.
     * Persists across screen visits for the lifetime of the ViewModel (which
     * Hilt scopes to the nav backstack entry). Keeps the sub-second first
     * paint when returning to the TV screen — the costly enrichment of 52k
     * channels only runs once per session.
     */
    // Backed by process-level storage (GuideSessionCache), not this ViewModel: navigateHome pops
    // Home *inclusive*, so every return to the guide built a brand-new TvViewModel and this
    // "only once per session" cache was thrown away each time -- the full re-enrichment (and the
    // loading screen) ran again on every Discover -> Now Playing trip (Joe, 2026-09-30).
    var cachedEnrichedChannels: Any?
        get() = GuideSessionCache.enrichedChannels
        set(value) { GuideSessionCache.enrichedChannels = value }
    var cachedChannelsSignature: String?
        get() = GuideSessionCache.channelsSignature
        set(value) { GuideSessionCache.channelsSignature = value }

    private fun countBucket(count: Int): String = when {
        count < 100 -> "lt_100"
        count < 1_000 -> "lt_1k"
        count < 10_000 -> "lt_10k"
        count < 50_000 -> "lt_50k"
        else -> "gte_50k"
    }

    init {
        observeConfigAndFavorites()
        observeGroupBlacklistEnabled()
        observeTvSession()
        observeFavoriteSortModeInternal()
        viewModelScope.launch {
            runCatching { iptvRepository.warmupFromCacheOnly() }
            // Try fast non-blocking in-memory read first; fall back to mutex-guarded disk read
            val cached = iptvRepository.getMemoryCachedSnapshot()
                ?: iptvRepository.getCachedSnapshotOrNull()
            if (cached != null) {
                val config = iptvRepository.observeConfig().first()
                // The observeConfigAndFavorites() coroutine may have already read fresh
                // favorites from DataStore before this cached snapshot was loaded from disk.
                // Prefer those in-memory favorites over whatever was baked into the cache,
                // which can be stale if the user added/removed favorites since the last save.
                val liveSnapshot = _uiState.value.snapshot
                val snapshotToUse = if (liveSnapshot.favoriteChannels.isNotEmpty() || liveSnapshot.favoriteGroups.isNotEmpty()) {
                    cached.copy(
                        favoriteChannels = liveSnapshot.favoriteChannels,
                        favoriteGroups = liveSnapshot.favoriteGroups,
                        hiddenGroups = liveSnapshot.hiddenGroups,
                        groupOrder = liveSnapshot.groupOrder
                    )
                } else {
                    cached
                }
                setUiState(
                    _uiState.value.copy(
                        isLoading = false,
                        error = null,
                        snapshot = snapshotToUse,
                        loadingMessage = null,
                        loadingPercent = 0
                    )
                )
                maybeWarmStartupGuide()
                startFullEpgWarmup()
                startCompleteEpgBackfill()
                warmXtreamVodCache()
                val hasPotentialEpg = config.epgUrl.isNotBlank() || config.m3uUrl.contains("get.php", ignoreCase = true) || config.m3uUrl.contains("player_api.php", ignoreCase = true)
                val needsChannelReload = config.m3uUrl.isNotBlank() && cached.channels.isEmpty()
                // Only force EPG refresh if cached EPG is older than 2 minutes.
                // When navigating from Home, EPG was likely just loaded — no need to re-fetch.
                val epgAgeMs = iptvRepository.cachedEpgAgeMs()
                val epgIsRecent = epgAgeMs < 120_000L
                val epgCoverage = epgCoverageRatio(cached)
                if (needsChannelReload) {
                    // Only situation that still requires a blocking refresh:
                    // there are literally no channels to show.
                    refresh(force = true, showLoading = false, forceEpg = false)
                } else {
                    // In every other case render the warm cache instantly —
                    // never block the TV page on EPG. The active category
                    // will request guide data on demand once the user lands
                    // there, instead of broad startup sweeps.
                    if (iptvRepository.isSnapshotStale(cached)) {
                        refresh(force = false, showLoading = false, forceEpg = false)
                    } else {
                        System.err.println("[EPG] Startup: using warm cached EPG (age=${epgAgeMs / 1000}s)")
                    }
                }
            } else {
                refresh(force = false, showLoading = false, forceEpg = false)
            }
            startPeriodicEpgRefresh()
            startIptvRefreshFlagPoller()
        }
    }

    private fun observeTvSession() {
        viewModelScope.launch {
            iptvRepository.observeTvSessionState()
                .distinctUntilChanged()
                .collect { session ->
                    _uiState.value = _uiState.value.copy(
                        tvSession = session,
                        tvSessionLoaded = true,
                    )
                    maybeWarmStartupGuide()
                }
        }
    }

    private fun observeFavoriteSortModeInternal() {
        viewModelScope.launch {
            iptvRepository.observeFavoriteSortMode()
                .distinctUntilChanged()
                .collect { raw ->
                    _favoriteSortMode.value = runCatching { FavoriteSortMode.valueOf(raw) }
                        .getOrDefault(FavoriteSortMode.DateAdded)
                }
        }
    }

    fun cycleFavoriteSortMode() {
        val entries = FavoriteSortMode.entries
        val next = entries[(favoriteSortMode.value.ordinal + 1) % entries.size]
        _favoriteSortMode.value = next
        viewModelScope.launch { iptvRepository.saveFavoriteSortMode(next.name) }
    }

    private fun observeConfigAndFavorites() {
        viewModelScope.launch {
            combine(
                combine(iptvRepository.observeConfig(), iptvRepository.observeFavoriteGroups(), iptvRepository.observeFavoriteChannels()) { a, b, c -> Triple(a, b, c) },
                iptvRepository.observeHiddenGroups(),
                iptvRepository.observeGroupOrder(),
                iptvRepository.observeNewGroups(),
                iptvRepository.observeRemovedGroups()
            ) { triple, hiddenGroups, groupOrder, newGroups, removedGroups ->
                object {
                    val triple = triple
                    val hiddenGroups = hiddenGroups
                    val groupOrder = groupOrder
                    val newGroups = newGroups
                    val removedGroups = removedGroups
                }
            }
                .distinctUntilChanged()
                .collect { data ->
                val (config, favoriteGroups, favoriteChannels) = data.triple
                val hiddenGroups = data.hiddenGroups
                val groupOrder = data.groupOrder
                val newGroups = data.newGroups
                val removedGroups = data.removedGroups
                val newConfigSignature = config.syncSignature()
                val configChanged = lastObservedConfigSignature != null &&
                    lastObservedConfigSignature != newConfigSignature
                lastObservedConfigSignature = newConfigSignature
                val snapshot = _uiState.value.snapshot.copy(
                    favoriteGroups = favoriteGroups,
                    favoriteChannels = favoriteChannels,
                    hiddenGroups = hiddenGroups,
                    newGroups = newGroups,
                    removedGroups = removedGroups,
                    groupOrder = groupOrder
                )
                setUiState(
                    _uiState.value.copy(
                        config = config,
                        snapshot = snapshot,
                        iptvPreferencesLoaded = true,
                    )
                )
                maybeWarmStartupGuide()
                startFullEpgWarmup()

                val hasAnyIptvConfig = config.m3uUrl.isNotBlank() ||
                    config.stalkerPortalUrl.isNotBlank() ||
                    config.playlists.any { it.enabled && it.m3uUrl.isNotBlank() }

                // Auto-heal cases where the app has IPTV config but an empty in-memory snapshot.
                if (hasAnyIptvConfig && snapshot.channels.isEmpty() && refreshJob?.isActive != true) {
                    refresh(force = false, showLoading = false)
                } else if (configChanged && refreshJob?.isActive != true) {
                    cachedEnrichedChannels = null
                    cachedChannelsSignature = null
                    refresh(force = true, showLoading = false, forceEpg = false)
                }
            }
        }
    }

    private fun observeGroupBlacklistEnabled() {
        viewModelScope.launch {
            iptvRepository.observeGroupBlacklistEnabled().collect { enabled ->
                if (_uiState.value.groupBlacklistEnabled != enabled) {
                    _uiState.value = _uiState.value.copy(groupBlacklistEnabled = enabled)
                }
            }
        }
    }

    fun refresh(force: Boolean, showLoading: Boolean = true, forceEpg: Boolean = false) {
        if (refreshJob?.isActive == true) return
        if (force) {
            epgRefreshJob?.cancel()
        }

        refreshJob = viewModelScope.launch {
            val hasExistingChannels = _uiState.value.snapshot.channels.isNotEmpty()
            if (showLoading && !hasExistingChannels) {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    loadingMessage = "Starting IPTV load...",
                    loadingPercent = 2
                )
            }
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(180_000L) {
                    iptvRepository.loadSnapshot(
                        forcePlaylistReload = force,
                        forceEpgReload = forceEpg,
                        allowNetworkEpgFetch = false,
                        onProgress = { progress ->
                            if (showLoading && !hasExistingChannels) {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = true,
                                    loadingMessage = progress.message,
                                    loadingPercent = progress.percent ?: _uiState.value.loadingPercent
                                )
                            }
                        },
                        onChannelsReady = { channels ->
                            // Publish channels to UI immediately — don't wait for EPG.
                            // This makes the TV page responsive even on cold start with no cache.
                            val cachedNowNext = withContext(Dispatchers.Default) {
                                iptvRepository.reDeriveCachedNowNext(
                                    channels.asSequence().map { it.id }.toSet()
                                ).orEmpty()
                            }
                            val currentSnapshot = _uiState.value.snapshot
                            // Rebuild grouped from the new channels so that all playlist
                            // groups (Norway, Sweden, Denmark …) appear immediately rather
                            // than inheriting the stale groups from the previous snapshot.
                            val freshGrouped = channels.groupBy { it.group.ifBlank { "Uncategorized" } }
                            setUiState(
                                _uiState.value.copy(
                                    isLoading = false,
                                    error = null,
                                    snapshot = currentSnapshot.copy(
                                        channels = channels,
                                        grouped = freshGrouped,
                                        nowNext = if (cachedNowNext.isNotEmpty()) {
                                            currentSnapshot.nowNext.toMutableMap().apply { putAll(cachedNowNext) }
                                        } else {
                                            currentSnapshot.nowNext
                                        }
                                    ),
                                    loadingMessage = null,
                                    loadingPercent = 0
                                )
                            )
                            startFullEpgWarmup()
                            startCompleteEpgBackfill()
                        }
                    )
                } ?: throw IllegalStateException("IPTV load timed out")
            }.onSuccess { snapshot ->
                cachedEnrichedChannels = null
                cachedChannelsSignature = null
                setUiState(
                    _uiState.value.copy(
                        isLoading = false,
                        error = null,
                        snapshot = snapshot,
                        loadingMessage = null,
                        loadingPercent = 0
                    )
                )
                maybeWarmStartupGuide()
                startFullEpgWarmup()
                startCompleteEpgBackfill()
                warmXtreamVodCache()
                if (!force && _uiState.value.isConfigured && snapshot.channels.isEmpty()) {
                    // Soft refresh returned empty even though IPTV is configured:
                    // schedule one forced reload to bypass stale in-memory paths.
                    pendingForcedReload = true
                }
            }.onFailure { error ->
                AppLogger.recordException(
                    throwable = error,
                    context = mapOf(
                        "error_area" to "IPTV",
                        "iptv_phase" to "load_snapshot",
                        "force_playlist_reload" to force.toString(),
                        "force_epg_reload" to forceEpg.toString(),
                        "had_existing_channels" to hasExistingChannels.toString()
                    )
                )
                val fallback = runCatching {
                    iptvRepository.getMemoryCachedSnapshot() ?: iptvRepository.getCachedSnapshotOrNull()
                }.getOrNull()
                if (fallback != null && fallback.channels.isNotEmpty()) {
                    setUiState(
                        _uiState.value.copy(
                            isLoading = false,
                            error = null,
                            snapshot = fallback,
                            loadingMessage = null,
                            loadingPercent = 0
                        )
                    )
                    maybeWarmStartupGuide()
                    startFullEpgWarmup()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load IPTV",
                        loadingMessage = null,
                        loadingPercent = 0
                    )
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                refreshJob = null
                if (pendingForcedReload) {
                    pendingForcedReload = false
                    refresh(force = true, showLoading = false, forceEpg = false)
                }
            }
        }
    }

    private fun warmXtreamVodCache() {
        if (warmVodJob?.isActive == true) return
        warmVodJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { iptvRepository.warmXtreamVodCachesIfPossible() }
        }.also { job ->
            job.invokeOnCompletion { warmVodJob = null }
        }
    }

    private fun hasAnyEpgData(snapshot: IptvSnapshot): Boolean {
        if (snapshot.nowNext.isEmpty()) return false
        return snapshot.nowNext.values.any { item ->
            item.now != null ||
                item.next != null ||
                item.later != null ||
                item.upcoming.isNotEmpty() ||
                item.recent.isNotEmpty()
        }
    }

    private fun hasProgramData(item: com.arflix.tv.data.model.IptvNowNext?): Boolean {
        return item != null && (
            item.now != null ||
                item.next != null ||
                item.later != null ||
                item.upcoming.isNotEmpty() ||
                item.recent.isNotEmpty()
            )
    }

    private suspend fun refreshGuideFromCache() {
        val state = _uiState.value
        val channelIds = buildPriorityEpgChannelIds(
            state = state,
            maxChannels = if (state.snapshot.channels.size > 10_000) 1_800 else 3_200
        )
        if (channelIds.isEmpty()) return
        val updated = withContext(Dispatchers.Default) {
            iptvRepository.reDeriveCachedNowNext(channelIds)
        } ?: return
        mergeNowNext(updated)
    }

    private suspend fun refreshGuideFromCache(channelIds: Set<String>) {
        if (channelIds.isEmpty()) return
        val updated = withContext(Dispatchers.Default) {
            iptvRepository.reDeriveCachedNowNext(channelIds)
        } ?: return
        mergeNowNext(updated)
    }

    private fun mergeNowNext(updated: Map<String, com.arflix.tv.data.model.IptvNowNext>) {
        if (updated.isEmpty()) return
        val current = _uiState.value
        setUiState(
            current.copy(
                snapshot = current.snapshot.copy(
                    nowNext = current.snapshot.nowNext.toMutableMap().apply { putAll(updated) }
                )
            )
        )
    }

    private fun epgCoverageRatio(snapshot: IptvSnapshot): Float {
        if (snapshot.channels.isEmpty()) return 0f
        val covered = snapshot.channels.count { ch ->
            val item = snapshot.nowNext[ch.id]
            item != null && (item.now != null || item.next != null || item.later != null || item.upcoming.isNotEmpty())
        }
        return covered.toFloat() / snapshot.channels.size.toFloat()
    }

    private fun startPeriodicEpgRefresh() {
        if (periodicEpgJob?.isActive == true) return
        periodicEpgJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                val state = _uiState.value
                if (state.isConfigured && state.snapshot.channels.isNotEmpty()) {
                    refreshGuideFromCache()
                }
            }
        }
    }

    // Polls xadarr-server every 15 min for the IPTV refresh flag that Episeerr
    // bumps after each Dispatcharr maintenance run. Forces a playlist reload when
    // the flag timestamp is newer than the last one we saw.
    private fun startIptvRefreshFlagPoller() {
        if (iptvRefreshPollJob?.isActive == true) return
        iptvRefreshPollJob = viewModelScope.launch(Dispatchers.IO) {
            delay(5 * 60_000L) // let startup settle before first check
            while (true) {
                val ts = runCatching { cloudSyncRepository.iptvRefreshTimestamp() }.getOrNull()
                if (ts != null) {
                    if (lastKnownIptvRefreshAt == 0L) {
                        lastKnownIptvRefreshAt = ts // baseline — don't refresh on first poll
                    } else if (ts > lastKnownIptvRefreshAt) {
                        lastKnownIptvRefreshAt = ts
                        withContext(Dispatchers.Main) {
                            refresh(force = true, showLoading = false, forceEpg = false)
                        }
                    }
                }
                delay(15 * 60_000L)
            }
        }
    }

    private fun startFullEpgWarmup() {
        val state = _uiState.value
        val channels = state.snapshot.channels
        if (channels.isEmpty()) return
        val warmChannelIds = buildPriorityEpgChannelIds(
            state = state,
            maxChannels = if (channels.size > 10_000) 1_600 else 3_200
        )
        if (warmChannelIds.isEmpty()) return
        val missingCount = warmChannelIds.count { id -> !hasProgramData(state.snapshot.nowNext[id]) }
        if (missingCount == 0) return

        val warmupKey = buildString {
            append(state.config.syncSignature())
            append('|')
            append(channels.size)
            append('|')
            append(warmChannelIds.size)
            append('|')
            append(warmChannelIds.firstOrNull().orEmpty())
            append('|')
            append(warmChannelIds.lastOrNull().orEmpty())
        }
        if (warmupKey == lastFullEpgWarmupKey) return
        lastFullEpgWarmupKey = warmupKey

        fullEpgWarmupJob?.cancel()
        fullEpgWarmupJob = viewModelScope.launch(Dispatchers.IO) {
            delay(if (channels.size > 10_000) 3_000L else 1_500L)
            if (visibleEpgRefreshJob?.isActive == true) {
                visibleEpgRefreshJob?.join()
            }
            refreshGuideFromCache()

            val afterCache = _uiState.value
            val missingWarmIds = afterCache.snapshot.channels
                .asSequence()
                .map { it.id }
                .filter { id -> !hasProgramData(afterCache.snapshot.nowNext[id]) }
                .take(if (channels.size > 10_000) 1_600 else 3_200)
                .toCollection(LinkedHashSet())
            if (missingWarmIds.isEmpty()) return@launch

            System.err.println("[EPG-Warm] warming priority guide for ${missingWarmIds.size} channels, missing=$missingCount")
            val refreshed = runCatching {
                iptvRepository.refreshEpgForChannels(missingWarmIds, maxChannels = missingWarmIds.size)
            }.getOrNull()

            if (!refreshed.isNullOrEmpty()) {
                mergeNowNext(refreshed)
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (fullEpgWarmupJob === job) {
                    fullEpgWarmupJob = null
                }
            }
        }
    }

    private fun startCompleteEpgBackfill(force: Boolean = false) {
        val state = _uiState.value
        val channels = state.snapshot.channels
        if (!state.isConfigured || channels.isEmpty()) return
        if (!hasNetworkEpgSource(state.config)) return

        val coverage = epgCoverageRatio(state.snapshot)
        val ageMs = iptvRepository.cachedEpgAgeMs()
        val cacheLooksComplete = coverage >= 0.98f && ageMs < 6 * 60 * 60_000L
        if (!force && cacheLooksComplete) return
        if (completeEpgBackfillJob?.isActive == true) return

        val backfillKey = buildString {
            append(state.config.syncSignature())
            append('|')
            append(channels.size)
            append('|')
            append((coverage * 1_000).toInt())
            append('|')
            append(ageMs / (30 * 60_000L))
        }
        if (!force && backfillKey == lastCompleteEpgBackfillKey) return
        lastCompleteEpgBackfillKey = backfillKey

        completeEpgBackfillJob = viewModelScope.launch(Dispatchers.IO) {
            delay(if (channels.size > 10_000) 5_000L else 2_000L)
            val backfillResult = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(900_000L) {
                    iptvRepository.loadSnapshot(
                        forcePlaylistReload = false,
                        forceEpgReload = true,
                        allowNetworkEpgFetch = true,
                        allowBroadShortEpg = false,
                        onProgress = { progress ->
                            System.err.println("[EPG-Complete] ${progress.message} ${progress.percent ?: ""}".trim())
                        }
                    )
                }
            }
            backfillResult.onFailure { error ->
                AppLogger.recordException(
                    throwable = error,
                    context = mapOf(
                        "error_area" to "IPTV",
                        "iptv_phase" to "complete_epg_backfill",
                        "channel_count" to countBucket(channels.size),
                        "start_coverage_pct" to ((coverage * 100).toInt()).toString()
                    )
                )
            }
            val snapshot = backfillResult.getOrNull()
            if (snapshot == null) {
                AppLogger.recordException(
                    throwable = IllegalStateException("Complete EPG backfill timed out"),
                    context = mapOf(
                        "error_area" to "IPTV",
                        "iptv_phase" to "complete_epg_backfill_timeout",
                        "channel_count" to countBucket(channels.size),
                        "start_coverage_pct" to ((coverage * 100).toInt()).toString()
                    )
                )
                return@launch
            }

            if (snapshot.channels.isEmpty() || snapshot.nowNext.isEmpty()) {
                AppLogger.recordException(
                    throwable = IllegalStateException("Complete EPG backfill returned empty guide"),
                    context = mapOf(
                        "error_area" to "IPTV",
                        "iptv_phase" to "complete_epg_backfill_empty",
                        "channel_count" to countBucket(channels.size),
                        "snapshot_channels" to snapshot.channels.size.toString(),
                        "snapshot_now_next" to snapshot.nowNext.size.toString()
                    )
                )
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                val current = _uiState.value
                if (current.config.syncSignature() != state.config.syncSignature()) return@withContext
                val mergedSnapshot = snapshot.copy(
                    favoriteGroups = current.snapshot.favoriteGroups,
                    favoriteChannels = current.snapshot.favoriteChannels,
                    hiddenGroups = current.snapshot.hiddenGroups,
                    newGroups = current.snapshot.newGroups,
                    removedGroups = current.snapshot.removedGroups,
                    groupOrder = current.snapshot.groupOrder,
                )
                setUiState(current.copy(snapshot = mergedSnapshot))
                val finalCoveragePct = (epgCoverageRatio(mergedSnapshot) * 100).toInt()
                System.err.println("[EPG-Complete] merged full guide coverage=$finalCoveragePct%")
                if (finalCoveragePct < 80) {
                    AppLogger.breadcrumb(
                        tag = "IPTV",
                        message = "complete_epg_low_coverage channel_count=${countBucket(channels.size)} coverage=$finalCoveragePct",
                        severity = "warning"
                    )
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (completeEpgBackfillJob === job) {
                    completeEpgBackfillJob = null
                }
            }
        }
    }

    fun setQuery(query: String) {
        setUiState(_uiState.value.copy(query = query))
    }

    fun toggleFavoriteGroup(groupName: String) {
        viewModelScope.launch {
            iptvRepository.toggleFavoriteGroup(groupName)
            scheduleIptvCloudSync()
        }
    }

    fun toggleFavoriteChannel(channelId: String) {
        viewModelScope.launch {
            // Resolved here rather than passed in, so every favourite button in the app records
            // the name without each one having to thread it through. The name is what lets the
            // favourite survive a channel renumbering — see pruneStaleFavoriteChannels.
            val channelName = _uiState.value.channelLookup[channelId]?.name.orEmpty()
            iptvRepository.toggleFavoriteChannel(channelId, channelName)
            scheduleIptvCloudSync()
        }
    }

    fun toggleHiddenGroup(groupName: String) {
        viewModelScope.launch { iptvRepository.toggleHiddenGroup(groupName); scheduleIptvCloudSync() }
    }

    fun setGroupState(groupName: String, state: GroupState) {
        viewModelScope.launch { iptvRepository.setGroupState(groupName, state); scheduleIptvCloudSync() }
    }

    /**
     * Manual "my guide looks stale" refresh, reachable from the category sidebar. The refresh
     * runs entirely server-side (Dispatcharr's own M3U/EPG tasks, then maintenance.sql via the
     * existing webhook); Xadarr's own snapshot picks up the result the next time it reloads the
     * playlist, same as after a scheduled sync.
     *
     * The row label is still the only feedback (no separate toast — same reasoning as before:
     * Android's native Toast doesn't reliably render over this app's TV window), but two things
     * were wrong with relying on it. First, the network call just enqueues Dispatcharr's task and
     * returns near-instantly on a LAN, so "Refreshing…" could flip back to the idle label within
     * a fraction of a second — easy to never actually see. Second, there was no indication of the
     * outcome at all. A minimum visible duration plus a lingering result label ("Refresh sent" /
     * "Refresh failed") fixes both without introducing a whole toast plumbing path for one row.
     */
    fun refreshPlaylistAndEpg() {
        if (_uiState.value.isRefreshingPlaylist) return
        val MIN_REFRESH_VISIBLE_MS = 1_200L
        val REFRESH_RESULT_VISIBLE_MS = 2_500L
        // A provider-side M3U resync (dispatcharrCatalogRepository.refresh() -> Episeerr ->
        // Dispatcharr) can take tens of seconds (a real Sanctum sync logged 47s), so this button
        // used to only fire that server-side trigger and stop -- Xadarr's own cached channel
        // list never re-pulled the result, and a new upstream category (e.g. a same-day PPV
        // event) wouldn't appear in the guide until whatever independent background cycle Xadarr
        // runs on its own happened to catch up. The button is labeled "Refresh Playlist/EPG",
        // which promised both halves; it only ever did the first (Joe, 2026-09-19: "when I
        // refresh it only refresh[es] my channels?" -- Dispatcharr had the new category, his own
        // app didn't). Wait long enough for the server-side sync to realistically finish, then
        // force Xadarr's own M3U+EPG reload silently in the background.
        val SERVER_SYNC_SETTLE_MS = 60_000L
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshingPlaylist = true, playlistRefreshResult = null)
            val startedAt = System.currentTimeMillis()
            val ok = runCatching { dispatcharrCatalogRepository.refresh() }.getOrDefault(false)
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < MIN_REFRESH_VISIBLE_MS) delay(MIN_REFRESH_VISIBLE_MS - elapsed)
            _uiState.value = _uiState.value.copy(
                isRefreshingPlaylist = false,
                playlistRefreshResult = if (ok) "Refresh sent" else "Refresh failed",
            )
            delay(REFRESH_RESULT_VISIBLE_MS)
            if (_uiState.value.playlistRefreshResult != null) {
                _uiState.value = _uiState.value.copy(playlistRefreshResult = null)
            }
            if (ok) {
                delay(SERVER_SYNC_SETTLE_MS)
                refresh(force = true, showLoading = false, forceEpg = true)
            }
        }
    }

    fun prefetchVisibleCategoryEpg(
        channelIds: List<String>,
        selectedChannelId: String?,
        eagerLimit: Int = 96,
        backgroundLimit: Int = 640
    ) {
        if (channelIds.isEmpty()) return
        val firstPaintLimit = 40
        val orderedIds = buildList {
            selectedChannelId?.takeIf { it in channelIds }?.let { add(it) }
            channelIds.forEach { id ->
                if (id != selectedChannelId) add(id)
            }
        }
        if (orderedIds.isEmpty()) return

        val currentNowNext = _uiState.value.snapshot.nowNext
        val missingCount = orderedIds.count { !hasProgramData(currentNowNext[it]) }
        if (missingCount == 0) return

        val refreshKey = buildString {
            append(_uiState.value.config.syncSignature())
            append('|')
            append(selectedChannelId.orEmpty())
            append('|')
            append(orderedIds.size)
            append('|')
            append(eagerLimit)
            append('|')
            append(orderedIds.firstOrNull().orEmpty())
            append('|')
            append(orderedIds.lastOrNull().orEmpty())
        }
        val now = System.currentTimeMillis()
        if (refreshKey == lastVisibleEpgRefreshKey && now - lastVisibleEpgRefreshAt < 20_000L) return

        lastVisibleEpgRefreshKey = refreshKey
        lastVisibleEpgRefreshAt = now
        visibleEpgRefreshJob?.cancel()
        visibleEpgRefreshJob = viewModelScope.launch {
            val cacheLimit = maxOf(firstPaintLimit, eagerLimit, backgroundLimit).coerceAtMost(orderedIds.size)
            refreshGuideFromCache(orderedIds.take(cacheLimit).toCollection(LinkedHashSet()))

            val firstPaintIds = orderedIds
                .filterNot { id -> hasProgramData(_uiState.value.snapshot.nowNext[id]) }
                .take(minOf(firstPaintLimit, eagerLimit.coerceAtLeast(1)))
            val firstPaintIdSet = firstPaintIds.toHashSet()
            if (firstPaintIds.isNotEmpty()) {
                System.err.println("[EPG-Category] firstPaint=${firstPaintIds.size} totalVisible=${orderedIds.size} selected=${selectedChannelId.orEmpty()}")
                val firstPaintRefreshed = runCatching {
                    iptvRepository.refreshEpgForChannels(
                        firstPaintIds.toSet(),
                        maxChannels = firstPaintIds.size
                    )
                }.getOrNull()
                if (!firstPaintRefreshed.isNullOrEmpty()) {
                    mergeNowNext(firstPaintRefreshed)
                }
            }

            val eagerIds = orderedIds
                .filterNot { id -> id in firstPaintIdSet || hasProgramData(_uiState.value.snapshot.nowNext[id]) }
                .take((eagerLimit - firstPaintIds.size).coerceAtLeast(0))
            val eagerIdSet = eagerIds.toHashSet()
            if (eagerIds.isNotEmpty()) {
                System.err.println("[EPG-Category] eager=${eagerIds.size} totalVisible=${orderedIds.size} selected=${selectedChannelId.orEmpty()}")
                val eagerRefreshed = runCatching {
                    iptvRepository.refreshEpgForChannels(
                        eagerIds.toSet(),
                        maxChannels = eagerIds.size
                    )
                }.getOrNull()
                if (!eagerRefreshed.isNullOrEmpty()) {
                    mergeNowNext(eagerRefreshed)
                }
            }

            val backgroundIds = orderedIds
                .let { ids -> if (backgroundLimit > 0) ids.take(backgroundLimit) else ids }
                .filterNot { id ->
                    id in firstPaintIdSet || id in eagerIdSet || hasProgramData(_uiState.value.snapshot.nowNext[id])
                }
            if (backgroundIds.isEmpty()) return@launch

            System.err.println("[EPG-Category] background=${backgroundIds.size} selected=${selectedChannelId.orEmpty()}")
            val backgroundRefreshed = runCatching {
                iptvRepository.refreshEpgForChannels(
                    backgroundIds.toSet(),
                    maxChannels = backgroundIds.size
                )
            }.getOrNull()

            if (!backgroundRefreshed.isNullOrEmpty()) {
                mergeNowNext(backgroundRefreshed)
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (visibleEpgRefreshJob === job) {
                    visibleEpgRefreshJob = null
                }
            }
        }
    }

    fun moveGroupUp(groupName: String) {
        viewModelScope.launch {
            val current = currentVisiblePlaylistGroups()
            iptvRepository.moveGroupUp(groupName, current)
            scheduleIptvCloudSync()
        }
    }

    fun moveGroupToTop(groupName: String) {
        viewModelScope.launch {
            val current = currentVisiblePlaylistGroups()
            iptvRepository.moveGroupToTop(groupName, current)
            scheduleIptvCloudSync()
        }
    }

    fun moveGroupDown(groupName: String) {
        viewModelScope.launch {
            val current = currentVisiblePlaylistGroups()
            iptvRepository.moveGroupDown(groupName, current)
            scheduleIptvCloudSync()
        }
    }

    private fun currentVisiblePlaylistGroups(): List<String> {
        val snapshot = _uiState.value.snapshot
        val hidden = (snapshot.hiddenGroups + snapshot.newGroups + snapshot.removedGroups).mapTo(HashSet()) { it.trim() }
        // Library groups first, matching their default position in the sidebar, so they can be
        // moved like any IPTV group.
        val library = listOfNotNull(
            com.arflix.tv.ui.screens.tv.live.ShowsChannelGroup.takeIf { _showsGuideSchedule.value.isNotEmpty() },
            com.arflix.tv.ui.screens.tv.live.MoviesChannelGroup.takeIf {
                _movieGuide.value.movies.isNotEmpty() || _movieGuide.value.premiering.isNotEmpty()
            },
        ).filter { it !in hidden }
        return (library + snapshot.grouped.keys
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in hidden }
            .toList())
            .distinct()
    }

    fun rememberTvSession(
        lastChannelId: String?,
        lastGroupName: String?,
        lastFocusedZone: String,
        markOpened: Boolean = false
    ) {
        val current = _uiState.value.tvSession
        val normalizedChannelId = lastChannelId.orEmpty().trim().ifBlank { current.lastChannelId }
        val normalizedGroupName = lastGroupName.orEmpty().trim().ifBlank { current.lastGroupName }
        val normalizedFocusZone = lastFocusedZone.trim().ifBlank { current.lastFocusedZone.ifBlank { "GUIDE" } }
        val channelChanged = normalizedChannelId.isNotBlank() && normalizedChannelId != current.lastChannelId
        val next = current.copy(
            lastChannelId = normalizedChannelId,
            lastGroupName = normalizedGroupName,
            lastFocusedZone = normalizedFocusZone,
            lastOpenedAt = if (markOpened || channelChanged) System.currentTimeMillis() else current.lastOpenedAt
        )
        if (next == current) return

        _uiState.value = _uiState.value.copy(tvSession = next)
        maybeWarmStartupGuide()
        tvSessionSaveJob?.cancel()
        tvSessionSaveJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(if (markOpened || channelChanged) 0L else 220L)
            iptvRepository.saveTvSessionState(next)
            if (markOpened || channelChanged) {
                scheduleIptvCloudSync()
            }
        }
    }

    private fun setUiState(nextState: TvUiState) {
        val previous = _uiState.value
        if (canReusePreparedContent(previous, nextState)) {
            _uiState.value = nextState.copy(
                channelLookup = previous.channelLookup,
                groups = previous.groups,
                channelsByGroup = previous.channelsByGroup
            )
        } else {
            val revision = ++preparedContentRevision
            preparedContentJob?.cancel()
            _uiState.value = nextState.copy(
                channelLookup = previous.channelLookup,
                groups = previous.groups,
                channelsByGroup = previous.channelsByGroup
            )
            preparedContentJob = viewModelScope.launch(Dispatchers.Default) {
                val prepared = setPreparedContent(nextState)
                withContext(Dispatchers.Main.immediate) {
                    if (revision == preparedContentRevision) {
                        val latest = _uiState.value
                        _uiState.value = latest.copy(
                            channelLookup = prepared.channelLookup,
                            groups = prepared.groups,
                            channelsByGroup = prepared.channelsByGroup
                        )
                    }
                }
            }
        }
    }

    private fun canReusePreparedContent(previous: TvUiState, next: TvUiState): Boolean {
        val previousSnapshot = previous.snapshot
        val nextSnapshot = next.snapshot
        return previous.query == next.query &&
            previousSnapshot.channels === nextSnapshot.channels &&
            previousSnapshot.grouped === nextSnapshot.grouped &&
            previousSnapshot.favoriteChannels == nextSnapshot.favoriteChannels &&
            previousSnapshot.favoriteGroups == nextSnapshot.favoriteGroups &&
            previousSnapshot.hiddenGroups == nextSnapshot.hiddenGroups &&
            previousSnapshot.newGroups == nextSnapshot.newGroups &&
            previousSnapshot.removedGroups == nextSnapshot.removedGroups &&
            previousSnapshot.groupOrder == nextSnapshot.groupOrder
    }

    private fun maybeWarmStartupGuide() {
        val state = _uiState.value
        if (state.channelsByGroup.isEmpty()) return

        val warmGroups = buildStartupWarmGroups(state)
        if (warmGroups.isEmpty()) return
        val warmChannels = buildList {
            warmGroups.forEachIndexed { index, groupName ->
                val limit = if (groupName == FAVORITES_GROUP_NAME || index == 0) 96 else 56
                addAll(state.channelsByGroup[groupName].orEmpty().take(limit))
            }
        }
            .distinctBy { it.id }
        if (warmChannels.isEmpty()) return

        val coverage = warmChannels.count { hasProgramData(state.snapshot.nowNext[it.id]) }
        if (coverage >= minOf(warmChannels.size, 24)) return

        val preferredSelectedId = state.tvSession.lastChannelId
            .takeIf { id -> id.isNotBlank() && warmChannels.any { channel -> channel.id == id } }
            ?: warmChannels.firstOrNull()?.id
        val warmupKey = buildString {
            append(warmGroups.joinToString(","))
            append('|')
            append(warmChannels.firstOrNull()?.id.orEmpty())
            append('|')
            append(warmChannels.size)
            append('|')
            append(preferredSelectedId.orEmpty())
        }
        if (warmupKey == startupGuideWarmupKey) return
        startupGuideWarmupKey = warmupKey

        prefetchVisibleCategoryEpg(
            channelIds = warmChannels.map { it.id },
            selectedChannelId = preferredSelectedId,
            eagerLimit = minOf(warmChannels.size, 96),
            backgroundLimit = minOf(warmChannels.size, 520)
        )
    }

    private fun scheduleIptvCloudSync() {
        iptvCloudSyncJob?.cancel()
        iptvCloudSyncJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(350L)
            val firstAttempt = runCatching { cloudSyncRepository.pushToCloud() }.getOrNull()
            if (firstAttempt?.isFailure != false) {
                kotlinx.coroutines.delay(1_200L)
                runCatching { cloudSyncRepository.pushToCloud() }
            }
        }
    }
}

private fun buildStartupWarmGroups(state: TvUiState): List<String> {
    if (state.channelsByGroup.isEmpty()) return emptyList()
    val favoritesFirst = state.channelsByGroup[FAVORITES_GROUP_NAME]
        .orEmpty()
        .takeIf { it.isNotEmpty() }
        ?.let { listOf(FAVORITES_GROUP_NAME) }
        .orEmpty()
    val sessionGroup = state.tvSession.lastGroupName
        .takeIf { it.isNotBlank() && state.channelsByGroup[it].orEmpty().isNotEmpty() }
        ?.let(::listOf)
        .orEmpty()
    val favoriteGroups = state.snapshot.favoriteGroups
        .filter { state.channelsByGroup[it].orEmpty().isNotEmpty() }
    val netherlandsGroups = state.groups.filter { groupName ->
        state.channelsByGroup[groupName].orEmpty().isNotEmpty() && groupName.isPriorityStartupGroup()
    }
    val fallbackGroups = state.groups.filter { state.channelsByGroup[it].orEmpty().isNotEmpty() }
    return (favoritesFirst + sessionGroup + favoriteGroups + netherlandsGroups + fallbackGroups)
        .distinct()
        .take(16)
}

private fun buildPriorityEpgChannelIds(
    state: TvUiState,
    maxChannels: Int
): LinkedHashSet<String> {
    if (maxChannels <= 0 || state.channelsByGroup.isEmpty()) return LinkedHashSet()
    val selectedGroups = buildStartupWarmGroups(state)
    val result = LinkedHashSet<String>(maxChannels)
    selectedGroups.forEachIndexed { index, groupName ->
        if (result.size >= maxChannels) return@forEachIndexed
        val perGroupLimit = when {
            groupName == FAVORITES_GROUP_NAME -> 520
            index == 0 -> 420
            groupName.isPriorityStartupGroup() -> 280
            else -> 120
        }
        state.channelsByGroup[groupName].orEmpty()
            .asSequence()
            .take(perGroupLimit)
            .forEach { channel ->
                if (result.size < maxChannels) {
                    result.add(channel.id)
                }
            }
    }
    if (result.isEmpty()) {
        state.snapshot.channels.asSequence()
            .take(maxChannels)
            .forEach { result.add(it.id) }
    }
    return result
}

private fun setPreparedContent(state: TvUiState): TvUiState {
    val preparedGroups = buildPreparedGroups(state.snapshot)
    val preparedChannelsByGroup = buildPreparedChannelsByGroup(
        snapshot = state.snapshot,
        query = state.query,
        groups = preparedGroups
    )
    return state.copy(
        channelLookup = state.snapshot.channels.associateBy { it.id },
        groups = preparedGroups,
        channelsByGroup = preparedChannelsByGroup
    )
}

private fun buildPreparedGroups(snapshot: IptvSnapshot): List<String> {
    val dynamicGroups = snapshot.grouped.keys.toList()
    val hiddenSet = (snapshot.hiddenGroups + snapshot.newGroups + snapshot.removedGroups).toHashSet()
    val visibleGroups = dynamicGroups.filterNot { hiddenSet.contains(it) }
    val favorites = snapshot.favoriteGroups.filter { visibleGroups.contains(it) }
    val others = visibleGroups.filterNot { snapshot.favoriteGroups.contains(it) }
    val baseOrdered = if (snapshot.groupOrder.isNotEmpty()) {
        val orderMap = snapshot.groupOrder.withIndex().associate { (i, groupName) -> groupName to i }
        (favorites + others).sortedBy { orderMap[it] ?: Int.MAX_VALUE }
    } else {
        favorites + others
    }
    val hasFavoriteChannelsInSnapshot = snapshot.favoriteChannels
        .toHashSet()
        .let { ids -> snapshot.channels.any { ids.contains(it.id) } }
    return if (hasFavoriteChannelsInSnapshot) {
        listOf(FAVORITES_GROUP_NAME) + baseOrdered
    } else {
        baseOrdered
    }
}

private fun buildPreparedChannelsByGroup(
    snapshot: IptvSnapshot,
    query: String,
    groups: List<String>
): Map<String, List<IptvChannel>> {
    if (groups.isEmpty()) return emptyMap()
    val trimmedQuery = query.trim().lowercase()
    val favoriteChannelIds = snapshot.favoriteChannels.toHashSet()
    return buildMap(groups.size) {
        groups.forEach { group ->
            val source = if (group == FAVORITES_GROUP_NAME) {
                if (favoriteChannelIds.isEmpty()) {
                    emptyList()
                } else {
                    val favoriteOrder = snapshot.favoriteChannels
                        .withIndex()
                        .associate { (index, id) -> id to index }
                    snapshot.channels
                        .filter { favoriteChannelIds.contains(it.id) }
                        .sortedBy { favoriteOrder[it.id] ?: Int.MAX_VALUE }
                }
            } else {
                snapshot.grouped[group].orEmpty()
            }
            put(group, filterTvChannels(source, trimmedQuery))
        }
    }
}

private fun String.isNetherlandsGroup(): Boolean {
    val tokens = lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() }
        .toSet()
    return "netherlands" in tokens || "nederland" in tokens || "nl" in tokens
}

private fun String.isPriorityStartupGroup(): Boolean {
    return isNetherlandsGroup() || lowercase().contains("4k")
}

private fun filterTvChannels(
    source: List<IptvChannel>,
    trimmedQuery: String
): List<IptvChannel> {
    if (trimmedQuery.isBlank()) return source
    return source.mapNotNull { channel ->
        val name = channel.name.lowercase()
        val groupName = channel.group.lowercase()
        val score = when {
            name.startsWith(trimmedQuery) -> 100
            name.contains(trimmedQuery) -> 80
            groupName.startsWith(trimmedQuery) -> 60
            groupName.contains(trimmedQuery) -> 45
            else -> 0
        }
        if (score > 0) channel to score else null
    }
        .sortedByDescending { it.second }
        .map { it.first }
}

private fun hasNetworkEpgSource(config: IptvConfig): Boolean {
    fun looksLikeXtream(url: String): Boolean {
        return url.contains("player_api.php", ignoreCase = true) ||
            url.contains("get.php", ignoreCase = true) ||
            url.contains("xmltv.php", ignoreCase = true)
    }
    return config.epgUrl.isNotBlank() ||
        config.m3uUrl.isNotBlank() ||
        looksLikeXtream(config.m3uUrl) ||
        config.playlists.any { playlist ->
            playlist.enabled && (
                playlist.epgUrl.isNotBlank() ||
                    playlist.m3uUrl.isNotBlank() ||
                    looksLikeXtream(playlist.m3uUrl) ||
                    looksLikeXtream(playlist.epgUrl)
                )
        }
}

private fun IptvConfig.syncSignature(): String {
    val playlistsSignature = playlists
        .sortedBy { it.id }
        .joinToString("|") { playlist ->
            listOf(
                playlist.id,
                playlist.name,
                playlist.m3uUrl,
                playlist.epgUrl,
                playlist.enabled.toString()
            ).joinToString("~")
        }
    return listOf(
        m3uUrl,
        epgUrl,
        stalkerPortalUrl,
        stalkerMacAddress,
        playlistsSignature
    ).joinToString("||")
}
