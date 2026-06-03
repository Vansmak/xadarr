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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.FrigateRepository
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.ui.components.AppTopBar
import com.arflix.tv.ui.components.AppTopBarContentTopInset
import com.arflix.tv.ui.components.SidebarItem
import com.arflix.tv.ui.components.topBarFocusedItem
import com.arflix.tv.ui.components.topBarMaxIndex
import com.arflix.tv.ui.components.topBarSelectedIndex
import com.arflix.tv.util.LocalFrigateConfigured
import com.arflix.tv.ui.screens.tv.live.LiveColors
import com.arflix.tv.ui.screens.tv.live.LiveType
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.util.LocalDeviceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

data class CamerasUiState(
    val cameras: List<FrigateRepository.FrigateCamera> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class CamerasViewModel @Inject constructor(
    private val frigateRepository: FrigateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CamerasUiState())
    val uiState: StateFlow<CamerasUiState> = _uiState.asStateFlow()

    init {
        loadCameras()
    }

    fun loadCameras() {
        viewModelScope.launch {
            _uiState.value = CamerasUiState(isLoading = true)
            val cameras = frigateRepository.getCameras()
            _uiState.value = if (cameras.isEmpty()) {
                CamerasUiState(isLoading = false, error = "No cameras found. Check Frigate URL in settings.")
            } else {
                CamerasUiState(cameras = cameras, isLoading = false)
            }
        }
    }
}

// ── Focus zones ───────────────────────────────────────────────────────────────

private enum class FocusZone { TOPBAR, GRID, PLAYER }

// ── Cameras grid screen ───────────────────────────────────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CamerasScreen(
    viewModel: CamerasViewModel = hiltViewModel(),
    currentProfile: Profile? = null,
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
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
    val hasProfile = currentProfile != null

    val cameras = uiState.cameras
    val colCount = when (cameras.size) {
        0 -> 3
        1 -> 1
        2, 4 -> 2
        else -> 3
    }
    val rowCount = if (cameras.isEmpty()) 0 else (cameras.size + colCount - 1) / colCount

    val frigateConfigured = LocalFrigateConfigured.current
    // Focus state — all navigation is keyboard-driven (no system focus on cards)
    var focusZone by remember { mutableStateOf(FocusZone.GRID) }
    var topBarFocusIndex by remember {
        mutableIntStateOf(topBarSelectedIndex(SidebarItem.CAMERAS, hasProfile, frigateConfigured))
    }
    val maxTopBarIndex = remember(hasProfile, frigateConfigured) { topBarMaxIndex(hasProfile, frigateConfigured) }
    var focusedCameraIndex by remember { mutableIntStateOf(0) }

    // Which camera is playing fullscreen
    var playerCamera by remember { mutableStateOf<FrigateRepository.FrigateCamera?>(null) }
    val playerFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }

    // ExoPlayer for the embedded fullscreen player — reuse across camera selections
    val embeddedPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
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
                Lifecycle.Event.ON_PAUSE -> if (playerCamera != null) embeddedPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (playerCamera != null) embeddedPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(playerCamera) {
        if (playerCamera != null) {
            android.util.Log.d("Cameras", "playing stream: ${playerCamera!!.streamUrl}")
            embeddedPlayer.stop()
            embeddedPlayer.clearMediaItems()
            embeddedPlayer.setMediaItem(MediaItem.fromUri(playerCamera!!.streamUrl))
            embeddedPlayer.prepare()
            embeddedPlayer.play()
            focusZone = FocusZone.PLAYER
            runCatching { playerFocusRequester.requestFocus() }
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
        if (playerCamera != null) playerCamera = null else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF060609))
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back, Key.Escape -> {
                        if (playerCamera != null) { playerCamera = null; true }
                        else { onBack(); true }
                    }
                    Key.DirectionUp -> when {
                        focusZone == FocusZone.PLAYER -> false
                        focusZone == FocusZone.GRID && focusedCameraIndex < colCount -> {
                            // First row → go to TopBar
                            focusZone = FocusZone.TOPBAR
                            topBarFocusIndex = topBarSelectedIndex(SidebarItem.CAMERAS, hasProfile, frigateConfigured)
                            true
                        }
                        focusZone == FocusZone.GRID -> {
                            focusedCameraIndex -= colCount
                            true
                        }
                        else -> false
                    }
                    Key.DirectionDown -> when {
                        focusZone == FocusZone.PLAYER -> false
                        focusZone == FocusZone.TOPBAR -> {
                            focusZone = FocusZone.GRID
                            focusedCameraIndex = 0
                            true
                        }
                        focusZone == FocusZone.GRID && focusedCameraIndex + colCount < cameras.size -> {
                            focusedCameraIndex += colCount
                            true
                        }
                        else -> false
                    }
                    Key.DirectionLeft -> when {
                        focusZone == FocusZone.PLAYER -> false
                        focusZone == FocusZone.TOPBAR && topBarFocusIndex > 0 -> {
                            topBarFocusIndex--; true
                        }
                        focusZone == FocusZone.GRID && focusedCameraIndex % colCount > 0 -> {
                            focusedCameraIndex--; true
                        }
                        else -> false
                    }
                    Key.DirectionRight -> when {
                        focusZone == FocusZone.PLAYER -> false
                        focusZone == FocusZone.TOPBAR && topBarFocusIndex < maxTopBarIndex -> {
                            topBarFocusIndex++; true
                        }
                        focusZone == FocusZone.GRID
                            && focusedCameraIndex % colCount < colCount - 1
                            && focusedCameraIndex + 1 < cameras.size -> {
                            focusedCameraIndex++; true
                        }
                        else -> false
                    }
                    Key.Enter, Key.DirectionCenter -> when {
                        focusZone == FocusZone.PLAYER -> false
                        focusZone == FocusZone.TOPBAR -> {
                            when (topBarFocusedItem(topBarFocusIndex, hasProfile, frigateConfigured)) {
                                SidebarItem.SEARCH -> onNavigateToSearch()
                                SidebarItem.HOME -> onNavigateToHome()
                                SidebarItem.WATCHLIST -> onNavigateToWatchlist()
                                SidebarItem.TV -> onNavigateToTv()
                                SidebarItem.CAMERAS -> Unit
                                SidebarItem.SETTINGS -> onNavigateToSettings()
                                null -> onSwitchProfile()
                            }
                            true
                        }
                        focusZone == FocusZone.GRID && focusedCameraIndex < cameras.size -> {
                            playerCamera = cameras[focusedCameraIndex]
                            true
                        }
                        else -> false
                    }
                    else -> false
                }
            }
    ) {
        // ── Grid / loading / error ─────────────────────────────────────────
        when {
            uiState.isLoading -> {
                Box(
                    Modifier.fillMaxSize().padding(top = AppTopBarContentTopInset),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Pink)
                }
            }
            uiState.error != null -> {
                Column(
                    Modifier.fillMaxSize().padding(top = AppTopBarContentTopInset + 32.dp),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = AppTopBarContentTopInset),
                )
            }
        }

        // AppTopBar — hidden only when in fullscreen player
        if (!isTouchDevice && focusZone != FocusZone.PLAYER) {
            AppTopBar(
                selectedItem = SidebarItem.CAMERAS,
                isFocused = focusZone == FocusZone.TOPBAR,
                focusedIndex = if (focusZone == FocusZone.TOPBAR) topBarFocusIndex else -1,
                profile = currentProfile,
            )
        }

        // Embedded fullscreen player — drawn on top, covers AppTopBar
        if (playerCamera != null) {
            EmbeddedCameraPlayer(
                camera = playerCamera!!,
                player = embeddedPlayer,
                focusRequester = playerFocusRequester,
                onBack = { playerCamera = null },
                modifier = Modifier.fillMaxSize().zIndex(10f),
            )
        }
    }
}

// ── No-scroll camera grid (state-driven, no system focus) ────────────────────

@Composable
private fun CameraGrid(
    cameras: List<FrigateRepository.FrigateCamera>,
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
    camera: FrigateRepository.FrigateCamera,
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
                AsyncImage(
                    model = camera.snapshotUrl,
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
    camera: FrigateRepository.FrigateCamera,
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
                Text(camera.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    .forceDisableMediaCodecAsynchronousQueueing()
                    .experimentalSetEnableMediaCodecVideoRendererPrewarming(false)
                    .setEnableDecoderFallback(true)
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
            }
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
