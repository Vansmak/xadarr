@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.tv.live

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.arflix.tv.network.OkHttpProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /**
     * Whether the live player is genuinely rendering, straight from ExoPlayer.
     *
     * MainActivity keeps the screen awake off this rather than off MiniPlayerState.isActive.
     * `isActive` describes the mini-player tile, and full-screen viewing in the guide drives the
     * same player without necessarily setting it — so keying the screen-on flag to it let the
     * screensaver come up while a channel was playing.
     */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

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
                    .setUserAgent("Xadarr/1.2.0 (Android TV)")
            )
        )
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .forceDisableMediaCodecAsynchronousQueueing()
                .experimentalSetEnableMediaCodecVideoRendererPrewarming(false)
                .setEnableDecoderFallback(true)
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

    private var errorRetryJob: Job? = null
    private var errorRetryCount = 0
    private var stallJob: Job? = null

    /**
     * Give up on a stream that buffers forever, and let the device sleep.
     *
     * A dead IPTV stream does not always raise a PlaybackException — often it simply sits in
     * STATE_BUFFERING indefinitely. That state is neither idle nor playing: ExoPlayer keeps its
     * wake lock, the mini-player still reports isActive, and the error-retry path never fires
     * because no error ever arrives. Nothing releases anything.
     *
     * It shows up after the Shield is cast to. Casting backgrounds Xadarr and pauses the live
     * player; when the cast ends and Xadarr returns, resumeIfActive() plays a stream that may be
     * many minutes stale, which buffers rather than fails. A Shield was found holding the wake
     * lock for over two hours this way with nothing on screen, and the TV could not be turned off.
     *
     * Ninety seconds is far longer than any real rebuffer on this setup and short enough that a
     * set left in that state does not stay awake all night.
     */
    private fun watchStall(playbackState: Int) {
        stallJob?.cancel()
        if (playbackState != Player.STATE_BUFFERING || !_state.value.isActive) return
        stallJob = viewModelScope.launch {
            delay(90_000L)
            if (player.playbackState == Player.STATE_BUFFERING && _state.value.isActive) {
                player.stop()
                player.clearMediaItems()
                _state.value = MiniPlayerState(isActive = false)
            }
        }
    }

    // Dispatcharr-proxied streams occasionally hiccup mid-stream (provider failover,
    // brief connection reset on the restream). Without this, ExoPlayer surfaces a
    // PlaybackException, drops to STATE_IDLE, and the last frame just freezes forever —
    // nothing else ever calls prepare() again. Retry transient IO/timeout errors with
    // backoff, same recovery classes the VOD player (PlayerScreen.kt) already handles.
    private val errorRecoveryListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) errorRetryCount = 0
            watchStall(playbackState)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            val isRecoverable = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_TIMEOUT,
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
                else -> false
            }
            if (!isRecoverable || !_state.value.isActive || errorRetryCount >= 5) return
            errorRetryCount++
            val attempt = errorRetryCount
            errorRetryJob?.cancel()
            errorRetryJob = viewModelScope.launch {
                delay(attempt * 1_000L)
                if (!_state.value.isActive) return@launch
                player.stop()
                player.prepare()
                player.play()
            }
        }
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
        player.addListener(errorRecoveryListener)
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
        errorRetryJob?.cancel()
        errorRetryCount = 0
        // Stop first so the player is in IDLE — prepare() is a no-op when already READY/PLAYING.
        player.stop()
        player.clearMediaItems()
        // RTSP streams (cameras) don't use live configuration — HLS IPTV does.
        val item = if (streamUrl.startsWith("rtsp://", ignoreCase = true)) {
            MediaItem.fromUri(streamUrl)
        } else {
            MediaItem.Builder()
                .setUri(streamUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMinPlaybackSpeed(1.0f).setMaxPlaybackSpeed(1.0f)
                        .setTargetOffsetMs(4_000).build()
                )
                .build()
        }
        player.setMediaItem(item)
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
        errorRetryJob?.cancel()
        errorRetryCount = 0
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

    /** Stop playback and clear state (e.g. before a VOD or camera player opens). */
    fun dismiss() {
        errorRetryJob?.cancel()
        stallJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _state.value = MiniPlayerState(isActive = false)
    }

    override fun onCleared() {
        errorRetryJob?.cancel()
        stallJob?.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        player.release()
        super.onCleared()
    }
}
