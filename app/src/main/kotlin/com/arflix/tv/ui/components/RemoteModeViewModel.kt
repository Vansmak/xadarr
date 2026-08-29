package com.arflix.tv.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.repository.DPadKey
import com.arflix.tv.data.repository.LanPeer
import com.arflix.tv.data.repository.LanSyncService
import com.arflix.tv.data.repository.RemoteModeRepository
import com.arflix.tv.data.repository.tvremote.RemoteDevice
import com.arflix.tv.data.repository.tvremote.RemoteVolumeRouter
import com.arflix.tv.data.repository.tvremote.TvRemotePairingClient
import com.arflix.tv.data.repository.tvremote.TvRemoteService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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

    // ── Universal remote ────────────────────────────────────────────────────
    // The device whose buttons we're driving. Deliberately separate from `target` above: `target`
    // is the Xadarr instance that receives tune/play, and jumping the remote over to the TV to
    // dismiss a popup must not redirect playback there. Session-local — switching back is manual,
    // by design.
    private val _controlDevice = MutableStateFlow<RemoteDevice?>(null)
    val controlDevice: StateFlow<RemoteDevice?> = _controlDevice

    private val _haDevices = MutableStateFlow<List<RemoteDevice>>(emptyList())

    /**
     * One entry per physical device. An Xadarr instance and its Home Assistant twin (the Shield is
     * both a LAN peer and an `androidtv_remote` device) are the same box discovered two ways, so
     * they're merged rather than listed twice.
     */
    val devices: StateFlow<List<RemoteDevice>> = combine(peers, _haDevices) { xadarr, ha ->
        RemoteDevice.merge(xadarr.map { RemoteDevice.fromPeer(it) }, ha)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setControlDevice(device: RemoteDevice?) {
        _controlDevice.value = device
        // Picking an Xadarr device also makes it the playback target, which is what you want when
        // switching rooms. Picking a TV or speaker leaves the playback target alone.
        device?.peer?.let { remoteModeRepository.setTarget(it) }
    }

    /** Refreshes the HA half of the device list; cheap enough to call whenever the panel opens. */
    suspend fun loadDevices() {
        _haDevices.value = RemoteDevice.fromHaEntities(homeAssistantRepository.getControlEntities())
    }

    private fun activeDevice(): RemoteDevice? =
        _controlDevice.value ?: target.value?.let { RemoteDevice.fromPeer(it) }

    suspend fun sendKey(key: DPadKey): Boolean {
        val device = activeDevice() ?: return false
        val host = device.peer?.host
        if (host != null) {
            // Selecting a device should make the buttons drive that *device*, not just Xadarr's
            // own UI. System-level keys do that — they work on the launcher, in other apps and in
            // system settings — so try them first and keep the HTTP path as the fallback for
            // devices that aren't paired yet.
            if (tvRemoteService.sendDpadKey(host, key)) return true
            return remoteModeRepository.sendDpad(key)
        }
        return remoteVolumeRouter.sendKey(device, key)
    }

    suspend fun sendVolumeUp(): Boolean {
        val device = activeDevice() ?: return false
        return remoteVolumeRouter.volumeUp(device).takeIf { it }
            ?: if (device.isXadarr) remoteModeRepository.sendDpad(DPadKey.VOLUME_UP) else false
    }

    suspend fun sendVolumeDown(): Boolean {
        val device = activeDevice() ?: return false
        return remoteVolumeRouter.volumeDown(device).takeIf { it }
            ?: if (device.isXadarr) remoteModeRepository.sendDpad(DPadKey.VOLUME_DOWN) else false
    }

    suspend fun sendPower(): Boolean = activeDevice()?.let { remoteVolumeRouter.togglePower(it) } ?: false

    suspend fun selectInput(source: String): Boolean =
        activeDevice()?.let { remoteVolumeRouter.selectInput(it, source) } ?: false

    suspend fun profileFor(deviceId: String) = remoteVolumeRouter.profileFor(deviceId)
    suspend fun setProfile(deviceId: String, profile: RemoteVolumeRouter.DeviceProfile) =
        remoteVolumeRouter.setProfile(deviceId, profile)

    /**
     * Speaker choices for the volume mapping — derived from the same merged device list, so a TV
     * that HA registers twice (cast + androidtv_remote) appears once here as well.
     */
    suspend fun loadSpeakers(): List<HaSpeaker> {
        if (_haDevices.value.isEmpty()) loadDevices()
        return _haDevices.value.mapNotNull { device ->
            device.volumeEntity?.let { HaSpeaker(it, device.displayName) }
        }
    }
}
