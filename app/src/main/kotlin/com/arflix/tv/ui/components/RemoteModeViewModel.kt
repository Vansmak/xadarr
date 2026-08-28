package com.arflix.tv.ui.components

import androidx.lifecycle.ViewModel
import com.arflix.tv.data.repository.DPadKey
import com.arflix.tv.data.repository.LanPeer
import com.arflix.tv.data.repository.LanSyncService
import com.arflix.tv.data.repository.RemoteModeRepository
import com.arflix.tv.data.repository.tvremote.TvRemotePairingClient
import com.arflix.tv.data.repository.tvremote.TvRemoteService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {
    val target: StateFlow<LanPeer?> = remoteModeRepository.target
    val peers: StateFlow<List<LanPeer>> = lanSyncService.peers
    fun setTarget(peer: LanPeer?) = remoteModeRepository.setTarget(peer)
    suspend fun sendDpad(key: DPadKey): Boolean = remoteModeRepository.sendDpad(key)
    suspend fun sendText(text: String): Boolean = remoteModeRepository.sendText(text)

    // Android TV Remote Service — real system-level volume, for TVs that ignore the app-level
    // volume path entirely (CEC-forwarded external audio, e.g. Joe's Shield->Sonos). Falls back
    // to the existing HTTP dpad path automatically when the target isn't paired for this yet.
    suspend fun isTvRemotePaired(host: String): Boolean = tvRemoteService.isPaired(host)
    fun startTvRemotePairing(): TvRemotePairingClient = tvRemoteService.startPairing()
    suspend fun onTvRemotePairingFinished(host: String) = tvRemoteService.onPairingFinished(host)
    suspend fun forgetTvRemotePairing(host: String) = tvRemoteService.forgetPairing(host)

    suspend fun sendVolumeUp(host: String): Boolean =
        tvRemoteService.sendVolumeUp(host).takeIf { it } ?: remoteModeRepository.sendDpad(DPadKey.VOLUME_UP)

    suspend fun sendVolumeDown(host: String): Boolean =
        tvRemoteService.sendVolumeDown(host).takeIf { it } ?: remoteModeRepository.sendDpad(DPadKey.VOLUME_DOWN)
}
