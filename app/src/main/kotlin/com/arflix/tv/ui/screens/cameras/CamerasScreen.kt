@file:Suppress("UnsafeOptInUsageError")

package com.arflix.tv.ui.screens.cameras

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.NeolinkRepository
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.util.LocalNeolinkConfigured
import com.arflix.tv.ui.screens.tv.live.LiveColors
import com.arflix.tv.ui.screens.tv.live.LiveType
import com.arflix.tv.ui.theme.XadarrTheme
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.util.LocalDeviceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// mainStream (H265, often 4K) sometimes fails or renders corrupted (partial
// green frame) — subStream (H264, lower res) is far more reliably playable.
// Streams are proxied straight from Neolink's own RTSP restream, named
// "<camera>/mainStream" and "<camera>/subStream" — see NeolinkRepository.getCameras().
private fun neolinkSubStreamUrl(streamUrl: String): String? =
    if (streamUrl.endsWith("/mainStream")) streamUrl.removeSuffix("/mainStream") + "/subStream" else null

// Media3's RTSP client defaults to UDP transport, which doesn't survive the
// Docker NAT hop to Neolink reliably — the player then just hangs in
// STATE_BUFFERING forever instead of erroring or falling back to subStream.
// Every external ffmpeg test against these streams only worked with
// -rtsp_transport tcp, so force the same thing here (RTP-over-RTSP,
// TCP-interleaved) for rtsp:// sources; everything else (event clip mp4s)
// goes through the normal default factory.
private class CameraMediaSourceFactory(context: android.content.Context) : MediaSource.Factory {
    private val default = DefaultMediaSourceFactory(context)
    private val rtsp = RtspMediaSource.Factory().setForceUseRtpTcp(true)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        default.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        default.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (mediaItem.localConfiguration?.uri?.scheme == "rtsp") {
            rtsp.createMediaSource(mediaItem)
        } else {
            default.createMediaSource(mediaItem)
        }
}

// ── ViewModel ────────────────────────────────────────────────────────────────

data class CamerasUiState(
    val cameras: List<NeolinkRepository.NeolinkCamera> = emptyList(),
    val events: List<NeolinkRepository.NeolinkEvent> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class CamerasViewModel @Inject constructor(
    private val neolinkRepository: NeolinkRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CamerasUiState())
    val uiState: StateFlow<CamerasUiState> = _uiState.asStateFlow()

    init {
        loadCameras()
    }

    fun loadCameras() {
        viewModelScope.launch {
            _uiState.value = CamerasUiState(isLoading = true)
            val cameras = neolinkRepository.getCameras()
            val events = runCatching { neolinkRepository.getRecentEvents(20) }.getOrDefault(emptyList())
            _uiState.value = if (cameras.isEmpty()) {
                CamerasUiState(isLoading = false, events = events, error = "No cameras found. Check Neolink URL in settings.")
            } else {
                CamerasUiState(cameras = cameras, events = events, isLoading = false)
            }
        }
    }
}

// ── Focus zones ───────────────────────────────────────────────────────────────

private enum class FocusZone { GRID, EVENTS, PLAYER }

// ── Cameras grid screen ───────────────────────────────────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CamerasScreen(
    viewModel: CamerasViewModel = hiltViewModel(),
    currentProfile: Profile? = null,
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToDiscover: () -> Unit = {},
    onNavigateToTv: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val deviceType = LocalDeviceType.current
    val isTouchDevice = deviceType.isTouchDevice()

    val cameras = uiState.cameras
    val events = uiState.events
    val colCount = when (cameras.size) {
        0 -> 3
        1 -> 1
        2, 4 -> 2
        else -> 3
    }
    val rowCount = if (cameras.isEmpty()) 0 else (cameras.size + colCount - 1) / colCount

    val neolinkConfigured = LocalNeolinkConfigured.current
    val navSections = com.arflix.tv.util.LocalNavSections.current
    // Focus state — all navigation is keyboard-driven (no system focus on cards)
    var focusZone by remember { mutableStateOf(FocusZone.GRID) }
    val isNavRailOpen = com.arflix.tv.ui.components.rememberNavRailOpen()
    // Driven directly by this screen's own key handler below rather than NavRail's
    // internal FocusRequester — see NavRail.kt's doc comment / HomeScreen.kt's
    // identical fix (real Compose focus never reliably lands inside NavRail).
    val navRailFocusedIndex = remember { mutableStateOf(0) }
    LaunchedEffect(isNavRailOpen.value) {
        if (isNavRailOpen.value) navRailFocusedIndex.value = 0
    }
    var focusedCameraIndex by remember { mutableIntStateOf(0) }
    var focusedEventIndex by remember { mutableIntStateOf(0) }

    // Which stream is playing fullscreen (URL + display name — covers cameras and event clips)
    var playerUrl by remember { mutableStateOf<String?>(null) }
    var playerDisplayName by remember { mutableStateOf("") }
    val playerFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }

    // ExoPlayer for the embedded fullscreen player — reuse across camera selections
    val embeddedPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(CameraMediaSourceFactory(context))
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    .forceDisableMediaCodecAsynchronousQueueing()
                    .experimentalSetEnableMediaCodecVideoRendererPrewarming(false)
                    .setEnableDecoderFallback(true)
            )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(4_000, 20_000, 750, 2_000)
                    .setTargetBufferBytes(24 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
            .apply {
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
    }
    DisposableEffect(Unit) {
        onDispose { embeddedPlayer.release() }
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (playerUrl != null) embeddedPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (playerUrl != null) embeddedPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // Explicit playback failures fall back to subStream immediately.
    DisposableEffect(embeddedPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val current = playerUrl
                android.util.Log.e("Cameras", "playback error for $current: ${error.errorCodeName}", error)
                val fallback = current?.let { neolinkSubStreamUrl(it) }
                if (fallback != null) playerUrl = fallback
            }
        }
        embeddedPlayer.addListener(listener)
        onDispose { embeddedPlayer.removeListener(listener) }
    }
    LaunchedEffect(playerUrl) {
        if (playerUrl != null) {
            android.util.Log.d("Cameras", "playing: $playerUrl")
            embeddedPlayer.stop()
            embeddedPlayer.clearMediaItems()
            embeddedPlayer.setMediaItem(MediaItem.fromUri(playerUrl!!))
            embeddedPlayer.prepare()
            embeddedPlayer.play()
            focusZone = FocusZone.PLAYER
            runCatching { playerFocusRequester.requestFocus() }

            // mainStream sometimes stalls silently (no onPlayerError,
            // just never leaves STATE_BUFFERING) — fall back to subStream if it hasn't
            // become ready within a few seconds.
            val urlAtLaunch = playerUrl
            delay(8_000)
            if (playerUrl == urlAtLaunch && embeddedPlayer.playbackState != Player.STATE_READY) {
                val fallback = neolinkSubStreamUrl(urlAtLaunch!!)
                if (fallback != null) {
                    android.util.Log.w("Cameras", "playback stalled for $urlAtLaunch, falling back to $fallback")
                    playerUrl = fallback
                }
            }
        } else {
            embeddedPlayer.stop()
            embeddedPlayer.clearMediaItems()
            focusZone = FocusZone.GRID
            runCatching { rootFocusRequester.requestFocus() }
        }
    }

    // Give root initial focus so key events are received
    LaunchedEffect(Unit) {
        runCatching { rootFocusRequester.requestFocus() }
    }

    BackHandler {
        if (playerUrl != null) playerUrl = null else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(XadarrTheme.colors.backgroundDark)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (isNavRailOpen.value) {
                    val railEntries = com.arflix.tv.ui.components.computeNavRailEntries(
                        currentScreen = com.arflix.tv.data.model.NavSectionKind.CAMERAS,
                        navSections = navSections,
                        neolinkConfigured = neolinkConfigured,
                    )
                    com.arflix.tv.ui.components.navRailHandleKey(
                        event = event,
                        entries = railEntries,
                        focusedIndex = navRailFocusedIndex,
                        onClose = { isNavRailOpen.value = false },
                        actions = com.arflix.tv.ui.components.NavRailActions(
                            onNavigateToHome = onNavigateToHome,
                            onNavigateToSearch = onNavigateToSearch,
                            onNavigateToDiscover = onNavigateToDiscover,
                            onNavigateToTv = onNavigateToTv,
                            onNavigateToWatchlist = onNavigateToWatchlist,
                            onNavigateToSettings = onNavigateToSettings,
                        ),
                    )
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.Back, Key.Escape -> {
                        if (playerUrl != null) { playerUrl = null; true }
                        else { onBack(); true }
                    }
                    Key.DirectionUp -> when {
                        focusZone == FocusZone.PLAYER -> false
                        focusZone == FocusZone.EVENTS -> {
                            focusZone = FocusZone.GRID
                            val bottomRowStart = ((cameras.size - 1) / colCount) * colCount
                            focusedCameraIndex = bottomRowStart.coerceIn(0, (cameras.size - 1).coerceAtLeast(0))
                            true
                        }
                        // No more top bar to escalate into — Up at row 0 just stops
                        // there now, same as Home post-redesign.
                        focusZone == FocusZone.GRID && focusedCameraIndex < colCount -> true
                        focusZone == FocusZone.GRID -> {
                            focusedCameraIndex -= colCount
                            true
                        }
                        else -> false
                    }
                    Key.DirectionDown -> when {
                        focusZone == FocusZone.PLAYER -> false
                        focusZone == FocusZone.EVENTS -> false
                        focusZone == FocusZone.GRID && focusedCameraIndex + colCount < cameras.size -> {
                            focusedCameraIndex += colCount
                            true
                        }
                        focusZone == FocusZone.GRID && events.isNotEmpty() -> {
                            focusZone = FocusZone.EVENTS
                            focusedEventIndex = 0
                            true
                        }
                        else -> false
                    }
                    Key.DirectionLeft -> when {
                        focusZone == FocusZone.PLAYER -> false
                        focusZone == FocusZone.GRID && focusedCameraIndex % colCount > 0 -> {
                            focusedCameraIndex--; true
                        }
                        // Leftmost column, one more Left opens the rail — same gesture
                        // every other screen uses.
                        focusZone == FocusZone.GRID -> { isNavRailOpen.value = true; true }
                        focusZone == FocusZone.EVENTS && focusedEventIndex > 0 -> {
                            focusedEventIndex--; true
                        }
                        focusZone == FocusZone.EVENTS -> { isNavRailOpen.value = true; true }
                        else -> false
                    }
                    Key.DirectionRight -> when {
                        focusZone == FocusZone.PLAYER -> false
                        focusZone == FocusZone.GRID
                            && focusedCameraIndex % colCount < colCount - 1
                            && focusedCameraIndex + 1 < cameras.size -> {
                            focusedCameraIndex++; true
                        }
                        focusZone == FocusZone.EVENTS && focusedEventIndex < events.size - 1 -> {
                            focusedEventIndex++; true
                        }
                        else -> false
                    }
                    Key.Enter, Key.DirectionCenter -> when {
                        focusZone == FocusZone.PLAYER -> false
                        focusZone == FocusZone.GRID && focusedCameraIndex < cameras.size -> {
                            val cam = cameras[focusedCameraIndex]
                            playerUrl = cam.streamUrl
                            playerDisplayName = cam.displayName
                            true
                        }
                        focusZone == FocusZone.EVENTS && focusedEventIndex < events.size -> {
                            val ev = events[focusedEventIndex]
                            playerUrl = ev.clipUrl
                            playerDisplayName = ev.camera.replace('_', ' ')
                                .split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } } +
                                " · ${ev.label.replaceFirstChar { it.uppercaseChar() }}"
                            true
                        }
                        else -> false
                    }
                    else -> false
                }
            }
    ) {
        // ── Grid / loading / error + events row ───────────────────────────
        val eventsRowHeight = if (events.isNotEmpty()) 185.dp else 0.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = AppTopBarContentTopInset),
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Pink)
                    }
                }
                uiState.error != null -> {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Outlined.Videocam, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(uiState.error!!, color = Color.White.copy(0.6f), fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Pink.copy(0.15f))
                                .border(1.dp, Pink.copy(0.4f), RoundedCornerShape(8.dp))
                                .clickable { viewModel.loadCameras() }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) { Text("Retry", color = Pink, fontSize = 14.sp) }
                    }
                }
                else -> {
                    CameraGrid(
                        cameras = cameras,
                        colCount = colCount,
                        rowCount = rowCount,
                        focusZone = focusZone,
                        focusedIndex = focusedCameraIndex,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
            if (events.isNotEmpty()) {
                CameraEventsRow(
                    events = events,
                    focusedIndex = if (focusZone == FocusZone.EVENTS) focusedEventIndex else -1,
                    modifier = Modifier.fillMaxWidth().height(eventsRowHeight),
                )
            }
        }

        // Top chrome + NavRail — hidden only when in fullscreen player
        if (!isTouchDevice && focusZone != FocusZone.PLAYER) {
            com.arflix.tv.ui.components.MinimalTopChrome(profile = currentProfile)

            // zIndex forces this above the grid/events content regardless of
            // composition order — see HomeScreen.kt/SettingsScreen.kt's identical fix.
            Box(modifier = Modifier.zIndex(10f)) {
                com.arflix.tv.ui.components.NavRail(
                    isOpen = isNavRailOpen.value,
                    onClose = {
                        isNavRailOpen.value = false
                        runCatching { rootFocusRequester.requestFocus() }
                    },
                    currentScreen = com.arflix.tv.data.model.NavSectionKind.CAMERAS,
                    navSections = navSections,
                    neolinkConfigured = neolinkConfigured,
                    actions = com.arflix.tv.ui.components.NavRailActions(
                        onNavigateToHome = onNavigateToHome,
                        onNavigateToSearch = onNavigateToSearch,
                        onNavigateToDiscover = onNavigateToDiscover,
                        onNavigateToTv = onNavigateToTv,
                        onNavigateToWatchlist = onNavigateToWatchlist,
                        onNavigateToSettings = onNavigateToSettings,
                    ),
                    focusedIndex = navRailFocusedIndex.value,
                )
            }
        }

        // Embedded fullscreen player — drawn on top, covers the chrome above
        if (playerUrl != null) {
            EmbeddedCameraPlayer(
                streamUrl = playerUrl!!,
                displayName = playerDisplayName,
                player = embeddedPlayer,
                focusRequester = playerFocusRequester,
                onBack = { playerUrl = null },
                modifier = Modifier.fillMaxSize().zIndex(10f),
            )
        }
    }
}

// ── No-scroll camera grid (state-driven, no system focus) ────────────────────

@Composable
private fun CameraGrid(
    cameras: List<NeolinkRepository.NeolinkCamera>,
    colCount: Int,
    rowCount: Int,
    focusZone: FocusZone,
    focusedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val hPad = 48.dp
    val vPad = 12.dp
    val hGap = 14.dp
    val vGap = 12.dp

    BoxWithConstraints(modifier = modifier.padding(horizontal = hPad, vertical = vPad)) {
        val cardWidth: Dp = if (colCount > 0) (maxWidth - hGap * (colCount - 1)) / colCount else maxWidth
        val cardHeight: Dp = if (rowCount > 0) (maxHeight - vGap * (rowCount - 1)) / rowCount else 200.dp

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(vGap),
        ) {
            for (row in 0 until rowCount) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(cardHeight),
                    horizontalArrangement = Arrangement.spacedBy(hGap),
                ) {
                    for (col in 0 until colCount) {
                        val idx = row * colCount + col
                        if (idx < cameras.size) {
                            CameraGridCard(
                                camera = cameras[idx],
                                isFocused = focusZone == FocusZone.GRID && focusedIndex == idx,
                                modifier = Modifier.width(cardWidth).fillMaxHeight(),
                            )
                        } else {
                            Spacer(Modifier.width(cardWidth))
                        }
                    }
                }
            }
        }
    }
}

// ── Camera grid card (visual only — no click/focus, parent handles input) ────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CameraGridCard(
    camera: NeolinkRepository.NeolinkCamera,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "scale",
    )
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    val borderColor = if (isFocused) Pink.copy(pulseAlpha) else Color.White.copy(0.06f)

    // snapshotUrl is a static string, so Coil's in-memory cache would otherwise
    // keep showing whatever bitmap it first decoded for that exact URL for as
    // long as the app process stays alive — never re-hitting the network even
    // though the server now serves a fresh live frame each time. A changing
    // cache-busting query param forces a real refetch; matches the server's
    // own ~15s snapshot TTL so this doesn't just hammer ffmpeg for nothing.
    var snapshotNonce by remember { mutableStateOf(0L) }
    LaunchedEffect(camera.snapshotUrl) {
        while (true) {
            snapshotNonce = System.currentTimeMillis()
            delay(15_000)
        }
    }

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .border(if (isFocused) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .background(Color(0xFF0E0E14)),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF111118)),
            contentAlignment = Alignment.Center,
        ) {
            if (camera.snapshotUrl.isNotBlank()) {
                val separator = if (camera.snapshotUrl.contains("?")) "&" else "?"
                AsyncImage(
                    model = "${camera.snapshotUrl}${separator}_t=$snapshotNonce",
                    contentDescription = camera.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.2f)))
                    )
                )
            } else {
                Icon(Icons.Outlined.Videocam, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(48.dp))
            }

            val dotAlpha by pulseTransition.animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "dot",
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(LiveColors.LiveRed)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(6.dp).background(Color.White.copy(dotAlpha), CircleShape))
                Text("LIVE", style = LiveType.Badge.copy(color = Color.White, fontSize = 9.sp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0A10))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Videocam, null, tint = LiveColors.Accent.copy(0.7f), modifier = Modifier.size(13.dp))
            Text(
                text = camera.displayName,
                color = Color.White.copy(if (isFocused) 1f else 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Embedded fullscreen camera player ────────────────────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmbeddedCameraPlayer(
    streamUrl: String,
    displayName: String,
    player: ExoPlayer,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Escape)
                ) { onBack(); true } else false
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(0.6f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Videocam, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(18.dp))
                Text(displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(0.6f))
                .clickable { onBack() }
                .padding(10.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Recent events row ─────────────────────────────────────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CameraEventsRow(
    events: List<NeolinkRepository.NeolinkEvent>,
    focusedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val nowMs = remember { System.currentTimeMillis() }
    Column(
        modifier = modifier
            .background(Color(0xFF0A0A10))
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Videocam, null, tint = LiveColors.Accent.copy(0.7f), modifier = Modifier.size(13.dp))
            Text(
                text = "Recent Events",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        val listState = rememberLazyListState()
        LaunchedEffect(focusedIndex) {
            if (focusedIndex >= 0) listState.animateScrollToItem(focusedIndex)
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 12.dp),
        ) {
            itemsIndexed(events) { index, event ->
                val diff = nowMs - event.startTimeMs
                val timeAgo = when {
                    diff < 60_000L -> "Just now"
                    diff < 3_600_000L -> "${diff / 60_000}m ago"
                    diff < 86_400_000L -> "${diff / 3_600_000}h ago"
                    else -> "${diff / 86_400_000}d ago"
                }
                val camName = event.camera.replace('_', ' ')
                    .split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
                CameraEventCard(
                    thumbnailUrl = event.thumbnailUrl,
                    cameraName = camName,
                    label = event.label.replaceFirstChar { it.uppercaseChar() },
                    timeAgo = timeAgo,
                    isFocused = index == focusedIndex,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CameraEventCard(
    thumbnailUrl: String,
    cameraName: String,
    label: String,
    timeAgo: String,
    isFocused: Boolean,
) {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "event_scale",
    )
    val borderColor = if (isFocused) Pink.copy(alpha = 0.9f) else Color.White.copy(0.06f)

    Column(
        modifier = Modifier
            .scale(scale)
            .width(175.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(if (isFocused) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(Color(0xFF0E0E14)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(98.dp)
                .background(Color(0xFF111118)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = cameraName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Outlined.Videocam, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(32.dp))
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(0.25f))
                    )
                )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0A10))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$label · $cameraName",
                color = Color.White.copy(if (isFocused) 1f else 0.75f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = timeAgo,
                color = Color.White.copy(0.45f),
                fontSize = 9.sp,
            )
        }
    }
}

// ── Standalone camera player (navigated to from home row fallback) ────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CameraPlayerScreen(
    streamUrl: String,
    cameraName: String,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusRequester = remember { FocusRequester() }
    var currentUrl by remember(streamUrl) { mutableStateOf(streamUrl) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(CameraMediaSourceFactory(context))
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    .forceDisableMediaCodecAsynchronousQueueing()
                    .experimentalSetEnableMediaCodecVideoRendererPrewarming(false)
                    .setEnableDecoderFallback(true)
            )
            .build()
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            exoPlayer.release()
        }
    }
    // Explicit playback failures fall back to subStream immediately.
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("Cameras", "playback error for $currentUrl: ${error.errorCodeName}", error)
                val fallback = neolinkSubStreamUrl(currentUrl)
                if (fallback != null) currentUrl = fallback
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }
    LaunchedEffect(currentUrl) {
        android.util.Log.d("Cameras", "playing: $currentUrl")
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(MediaItem.fromUri(currentUrl))
        exoPlayer.prepare()
        exoPlayer.play()

        // mainStream sometimes stalls silently (no onPlayerError,
        // just never leaves STATE_BUFFERING) — fall back to subStream if it hasn't
        // become ready within a few seconds.
        val urlAtLaunch = currentUrl
        delay(8_000)
        if (currentUrl == urlAtLaunch && exoPlayer.playbackState != Player.STATE_READY) {
            val fallback = neolinkSubStreamUrl(urlAtLaunch)
            if (fallback != null) {
                android.util.Log.w("Cameras", "playback stalled for $urlAtLaunch, falling back to $fallback")
                currentUrl = fallback
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Escape)
                ) { onBack(); true } else false
            },
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = false } },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart).padding(20.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(0.6f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Videocam, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(18.dp))
                Text(cameraName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd).padding(20.dp)
                .clip(CircleShape).background(Color.Black.copy(0.6f))
                .clickable { onBack() }.padding(10.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
