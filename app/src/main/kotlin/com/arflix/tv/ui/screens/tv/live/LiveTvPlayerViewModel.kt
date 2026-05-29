@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv.live

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.arflix.tv.network.OkHttpProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Activity-scoped ViewModel that owns the IPTV ExoPlayer instance.
 * Created above the NavHost in ArflixApp so it survives all navigation changes.
 * Audio keeps playing when the user navigates away from LiveTvScreen;
 * the mini-player overlay in ArflixApp observes [state] to show the pip tile.
 */
@HiltViewModel
class LiveTvPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class MiniPlayerState(
        val isActive: Boolean = false,
        val channelId: String? = null,
        val channelName: String = "",
        val programTitle: String = "",
        val streamUrl: String? = null,
    )

    private val _state = MutableStateFlow(MiniPlayerState())
    val state: StateFlow<MiniPlayerState> = _state.asStateFlow()

    private val iptvHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .dns(OkHttpProvider.dns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context).setDataSourceFactory(
                OkHttpDataSource.Factory(iptvHttpClient)
                    .setUserAgent("ARVIO/1.2.0 (Android TV)")
            )
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(4_000, 20_000, 750, 1_500)
                .setTargetBufferBytes(24 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(2_000, false)
                .build()
        )
        .build().apply {
            playWhenReady = true
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }

    // Pause/resume based on process lifecycle so audio stops when the whole app backgrounds.
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            player.pause()
        }
        override fun onStart(owner: LifecycleOwner) {
            if (_state.value.isActive) player.play()
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    }

    /**
     * Start playback of a channel from outside LiveTvScreen (e.g. Home On Now row).
     * Sets the media item on the ExoPlayer and updates state so the mini-player overlay appears.
     * When the user navigates back to the TV screen it will detect the channel change
     * via [state.channelId] and pick up the already-playing stream.
     */
    fun playFromHome(
        channelId: String,
        streamUrl: String,
        channelName: String = "",
        programTitle: String = "",
    ) {
        _state.value = MiniPlayerState(
            isActive = true,
            channelId = channelId,
            channelName = channelName,
            programTitle = programTitle,
            streamUrl = streamUrl,
        )
        // Stop first so the player is in IDLE — prepare() is a no-op when already READY/PLAYING.
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(streamUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMinPlaybackSpeed(1.0f).setMaxPlaybackSpeed(1.0f)
                        .setTargetOffsetMs(4_000).build()
                )
                .build()
        )
        player.prepare()
        player.play()
    }

    /** Called by LiveTvScreen when a live channel starts playing (screen-driven playback). */
    fun setActiveChannel(
        channelId: String,
        streamUrl: String,
        channelName: String = "",
        programTitle: String = "",
    ) {
        _state.value = MiniPlayerState(
            isActive = true,
            channelId = channelId,
            channelName = channelName,
            programTitle = programTitle,
            streamUrl = streamUrl,
        )
    }

    /** Called by LiveTvScreen when EPG refreshes to keep mini-player label current. */
    fun updateNowPlaying(channelName: String, programTitle: String) {
        if (!_state.value.isActive) return
        _state.value = _state.value.copy(channelName = channelName, programTitle = programTitle)
    }

    /** Pause the IPTV stream without clearing active state (e.g. when a VOD player opens). */
    fun pauseForVod() {
        player.pause()
    }

    /** Resume if a stream was previously active (called when returning from VOD). */
    fun resumeIfActive() {
        if (_state.value.isActive) player.play()
    }

    /** Stop playback and clear mini-player state (user explicitly dismissed). */
    fun dismiss() {
        player.stop()
        player.clearMediaItems()
        _state.value = MiniPlayerState(isActive = false)
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        player.release()
        super.onCleared()
    }
}
