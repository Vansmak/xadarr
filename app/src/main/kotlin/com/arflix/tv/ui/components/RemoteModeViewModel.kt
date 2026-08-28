package com.arflix.tv.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.repository.DPadKey
import com.arflix.tv.data.repository.LanPeer
import com.arflix.tv.data.repository.LanSyncService
import com.arflix.tv.data.repository.RemoteModeRepository
import com.arflix.tv.data.repository.tvremote.RemoteVolumeRouter
import com.arflix.tv.data.repository.tvremote.TvRemotePairingClient
import com.arflix.tv.data.repository.tvremote.TvRemoteService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Activity-scoped (retrieved via `hiltViewModel()` above the NavHost, in ArflixApp) so the
 * global swipe-down Remote Control panel works identically no matter which tab/screen is
 * showing. Distinct from the Guide's own "Remote" pill (TvViewModel) and Details' play
 * redirect (DetailsViewModel) — those gate an actual browse/tap action and stay scoped to
 * their own screens; this ViewModel only drives the control-panel surface (device picker,
 * D-pad, transport/volume, text) which is meant to work everywhere, independent of whichever
 * screen you're on. All three read/write the same underlying RemoteModeRepository.target.
 */
@HiltViewModel
class RemoteModeViewModel @Inject constructor(
    private val remoteModeRepository: RemoteModeRepository,
    private val lanSyncService: LanSyncService,
    private val tvRemoteService: TvRemoteService,
    private val remoteVolumeRouter: RemoteVolumeRouter,
    private val homeAssistantRepository: com.arflix.tv.data.repository.HomeAssistantRepository,
) : ViewModel() {
    val target: StateFlow<LanPeer?> = remoteModeRepository.target

    // Live NSD-discovered peers merged with paired-but-currently-unreachable devices (asleep,
    // screen off — NSD stops seeing them well before you'd want to give up on them, and a failed
    // background settings-sync push actively prunes them from lanSyncService.peers). Live entry
    // wins on host collision since it has an accurate port/name; paired-only entries are what
    // keeps a sleeping TV selectable so its Power button is actually reachable.
    val peers: StateFlow<List<LanPeer>> = combine(lanSyncService.peers, tvRemoteService.pairedPeers) { live, paired ->
        val liveHosts = live.map { it.host }.toSet()
        live + paired.filterNot { it.host in liveHosts }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setTarget(peer: LanPeer?) = remoteModeRepository.setTarget(peer)
    suspend fun sendDpad(key: DPadKey): Boolean = remoteModeRepository.sendDpad(key)
    suspend fun sendText(text: String): Boolean = remoteModeRepository.sendText(text)

    // Android TV Remote Service — real system-level volume, for TVs that ignore the app-level
    // volume path entirely (CEC-forwarded external audio, e.g. Joe's Shield->Sonos). Falls back
    // to the existing HTTP dpad path automatically when the target isn't paired for this yet.
    suspend fun isTvRemotePaired(host: String): Boolean = tvRemoteService.isPaired(host)
    fun startTvRemotePairing(): TvRemotePairingClient = tvRemoteService.startPairing()
    suspend fun onTvRemotePairingFinished(host: String, deviceName: String) =
        tvRemoteService.onPairingFinished(host, deviceName)
    suspend fun forgetTvRemotePairing(host: String) = tvRemoteService.forgetPairing(host)

    // Router picks the room's actual speaker (via Home Assistant) when one is configured for this
    // host, since a streaming box often can't control its own room's audio at all; otherwise it
    // injects volume keys on the device. HTTP dpad stays the last resort for unpaired targets.
    suspend fun sendVolumeUp(host: String): Boolean =
        remoteVolumeRouter.volumeUp(host).takeIf { it } ?: remoteModeRepository.sendDpad(DPadKey.VOLUME_UP)

    suspend fun sendVolumeDown(host: String): Boolean =
        remoteVolumeRouter.volumeDown(host).takeIf { it } ?: remoteModeRepository.sendDpad(DPadKey.VOLUME_DOWN)

    suspend fun profileFor(host: String) = remoteVolumeRouter.profileFor(host)
    suspend fun setProfile(host: String, profile: RemoteVolumeRouter.DeviceProfile) =
        remoteVolumeRouter.setProfile(host, profile)

    suspend fun inputsFor(host: String): List<String> = remoteVolumeRouter.inputs(host)
    suspend fun selectInput(host: String, source: String): Boolean = remoteVolumeRouter.selectInput(host, source)

    /** Home Assistant media_players offerable as a capability target; empty when HA isn't configured. */
    suspend fun loadSpeakers(): List<HaSpeaker> =
        homeAssistantRepository.getMediaPlayers().map { HaSpeaker(it.entityId, it.name) }

    // Routed too: when an HA entity owns the room's power we can read real state and send an
    // explicit turn_on/turn_off instead of a blind toggle.
    suspend fun sendPower(host: String): Boolean = remoteVolumeRouter.togglePower(host)
}
