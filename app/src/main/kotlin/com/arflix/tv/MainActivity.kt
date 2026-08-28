package com.arflix.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.arflix.tv.R
import coil.imageLoader
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.arflix.tv.ui.components.AppBottomBar
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.pm.ActivityInfo
import com.arflix.tv.util.DeviceType
import com.arflix.tv.util.DEVICE_MODE_OVERRIDE_KEY
import com.arflix.tv.util.SKIP_PROFILE_SELECTION_KEY
import com.arflix.tv.util.OLED_BLACK_BACKGROUND_KEY
import com.arflix.tv.util.FOCUS_BORDER_COLOR_KEY
import com.arflix.tv.util.THEME_KEY
import com.arflix.tv.util.LocalDeviceType
import com.arflix.tv.util.LocalHasTouchScreen
import com.arflix.tv.util.LocalAppLanguage
import com.arflix.tv.util.LAST_APP_LANGUAGE_KEY
import com.arflix.tv.util.detectDeviceType
import com.arflix.tv.data.repository.NEOLINK_URL_KEY
import com.arflix.tv.util.LocalNeolinkConfigured
import com.arflix.tv.util.deviceHasTouchScreen
import com.arflix.tv.util.settingsDataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import androidx.compose.runtime.CompositionLocalProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.arflix.tv.data.repository.AuthRepository
import com.arflix.tv.data.repository.AuthState
import com.arflix.tv.data.repository.LauncherContinueWatchingRepository
import com.arflix.tv.data.repository.LauncherContinueWatchingRequest
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.ProfileRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.WatchHistoryRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.data.repository.toLauncherContinueWatchingRequest
import com.arflix.tv.navigation.AppNavigation
import com.arflix.tv.navigation.Screen
import com.arflix.tv.ui.screens.tv.live.LiveTvPlayerViewModel
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.arflix.tv.ui.screens.login.LoginScreen
import com.arflix.tv.ui.startup.StartupViewModel
import com.arflix.tv.ui.theme.ArflixTvTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.worker.TraktSyncWorker
import dagger.hilt.android.AndroidEntryPoint
import dagger.Lazy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.arflix.tv.data.repository.AppNotification
import com.arflix.tv.data.repository.EpiseerrPollManager
import com.arflix.tv.data.repository.NotificationPollManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private sealed interface ActiveProfileLoadState {
    data object Loading : ActiveProfileLoadState
    data class Loaded(val profile: com.arflix.tv.data.model.Profile?) : ActiveProfileLoadState
}

/**
 * Main Activity - Single activity architecture with Compose Navigation
 * Uses Android 12+ Splash Screen API for instant launch feedback
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: Lazy<AuthRepository>

    @Inject
    lateinit var profileRepository: Lazy<ProfileRepository>

    @Inject
    lateinit var traktRepository: Lazy<TraktRepository>

    @Inject
    lateinit var profileManager: Lazy<ProfileManager>

    @Inject
    lateinit var watchHistoryRepository: Lazy<WatchHistoryRepository>

    @Inject
    lateinit var watchlistRepository: Lazy<WatchlistRepository>

    @Inject
    lateinit var launcherContinueWatchingRepository: Lazy<LauncherContinueWatchingRepository>

    @Inject
    lateinit var mediaRepository: Lazy<MediaRepository>

    // Prefetch IPTV early so the TV screen opens without a loading stall.
    // IptvRepository is @Singleton; touching it at activity start warms the
    // in-memory snapshot (and will trigger a disk-cache read + silent
    // background refresh) so by the time the user navigates into the TV tab
    // everything is already resident.
    @Inject
    lateinit var iptvRepository: Lazy<com.arflix.tv.data.repository.IptvRepository>

    @Inject
    lateinit var episeerrPollManager: EpiseerrPollManager

    @Inject
    lateinit var notificationPollManager: NotificationPollManager

    @Inject
    lateinit var homeServerRepository: Lazy<com.arflix.tv.data.repository.HomeServerRepository>

    @Inject
    lateinit var navSectionRepository: Lazy<com.arflix.tv.data.repository.NavSectionRepository>

    // Remote Mode — receiving side. RemoteCommandBus carries commands from WebAppServer's
    // /api/remote/* handlers (which may fire on any thread, any screen, even backgrounded)
    // into pending state the Compose tree below reacts to, mirroring pendingReminderChannelId.
    @Inject
    lateinit var remoteCommandBus: com.arflix.tv.data.repository.RemoteCommandBus

    private var jankStats: JankStats? = null
    private var pendingLauncherRequest by mutableStateOf<LauncherContinueWatchingRequest?>(null)
    private var pendingReminderChannelId by mutableStateOf<String?>(null)
    private var pendingWidgetDeepLink by mutableStateOf<com.arflix.tv.widget.WidgetDeepLinkRequest?>(null)
    private var pendingRemotePlayRequest by mutableStateOf<com.arflix.tv.data.repository.RemoteCommand.PlayTitle?>(null)
    // Deliberately separate from pendingReminderChannelId — that one's popUpTo(ProfileSelection)
    // is a no-op once already deep in the app (ProfileSelection long since popped), which just
    // reuses the existing Home entry via launchSingleTop and silently fails to actually switch
    // channel when the guide is already showing (confirmed on-device: currentStreamUrl derives
    // from a category-scoped lookup that misses the target — see LiveTvScreen.kt). This one
    // instead pops the CURRENT Home entry too, forcing a genuinely fresh LiveTvScreen/TvViewModel
    // every time, matching the cold-start path that's confirmed to always work correctly.
    private var pendingRemoteChannelId by mutableStateOf<String?>(null)
    private var pendingRemoteSearchQuery by mutableStateOf<String?>(null)
    val navigateHomeSignal = MutableStateFlow(0)
    val navigateSettingsSignal = MutableStateFlow(0)
    val navigateSmartHomeSignal = MutableStateFlow(0)
    val navigateToSignal = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    private var wasInBackground = false

    private val goHomeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            navigateHomeSignal.value++
        }
    }

    private val goSettingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            navigateSettingsSignal.value++
        }
    }

    private val navigateToReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            val dest = intent.dataString ?: intent.getStringExtra("destination") ?: return
            navigateToSignal.tryEmit(dest)
        }
    }

    // StartupViewModel for parallel loading during splash
    private val startupViewModel: StartupViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        val tag = newBase.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        if (!tag.isNullOrEmpty()) {
            val locale = java.util.Locale.forLanguageTag(tag)
            java.util.Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate()
        // Don't use setKeepOnScreenCondition - it causes black screen on some TV devices
        // Instead, let the splash dismiss immediately and show our Compose loading screen
        installSplashScreen()

        // Detect device type before super.onCreate().
        // The splash screen's postSplashScreenTheme is Theme.ArflixTV.Mobile (no fullscreen)
        // which is correct for phones/tablets. On TV we override to the fullscreen Leanback theme.
        val initialDeviceType = detectDeviceType(this)
        if (initialDeviceType == DeviceType.TV) {
            setTheme(R.style.Theme_ArflixTV)
        }

        super.onCreate(savedInstanceState)
        val goHomeFilter = IntentFilter("com.arflix.tv.ACTION_GO_HOME").apply {
            addAction("com.xadarr.tv.ACTION_GO_HOME")
        }
        val goSettingsFilter = IntentFilter("com.xadarr.tv.OPEN_SETTINGS")
        val navigateToFilter = IntentFilter("com.xadarr.tv.NAVIGATE")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(goHomeReceiver, goHomeFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(goSettingsReceiver, goSettingsFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(navigateToReceiver, navigateToFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(goHomeReceiver, goHomeFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(goSettingsReceiver, goSettingsFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(navigateToReceiver, navigateToFilter)
        }
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        pendingLauncherRequest = parseLauncherRequest(intent)
        pendingReminderChannelId = intent.getStringExtra(
            com.arflix.tv.worker.ProgramReminderWorker.EXTRA_REMINDER_CHANNEL_ID
        )
        pendingWidgetDeepLink = com.arflix.tv.widget.parseWidgetDeepLink(intent)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }

        // Set orientation based on device type
        requestedOrientation = when (initialDeviceType) {
            DeviceType.TV -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            DeviceType.TABLET -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            DeviceType.PHONE -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }

        // Keep screen on during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // All devices use edge-to-edge (setDecorFitsSystemWindows=false).
        // TV hides the bars; mobile keeps them visible and Compose handles
        // insets via systemBarsPadding() in the root layout.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (initialDeviceType == DeviceType.TV) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            // Clear any FLAG_FULLSCREEN the Leanback theme may have set
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            // Transparent bars — the dark app background shows through them.
            // White (light) icons are used since the background is dark.
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowInsetsControllerCompat(window, window.decorView).apply {
                show(WindowInsetsCompat.Type.systemBars())
                isAppearanceLightStatusBars = false      // white icons on dark bg
                isAppearanceLightNavigationBars = false  // white icons on dark bg
            }
        }

        setContent {
            // Observe device mode override changes live from DataStore
            val deviceModeOverride by remember {
                this@MainActivity.settingsDataStore.data.map { it[DEVICE_MODE_OVERRIDE_KEY] }
            }.collectAsStateWithLifecycle(initialValue = null)
            var skipProfileSelection by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) {
                val skipSelection =
                    this@MainActivity.settingsDataStore.data.first()[SKIP_PROFILE_SELECTION_KEY] ?: false
                if (skipSelection) {
                    val profiles = profileRepository.get()
                    val activeProfile = profiles.getActiveProfile()
                    if (activeProfile == null) {
                        val fallbackProfile = profiles.getProfiles().maxByOrNull { it.lastUsedAt }
                            ?: profiles.createDefaultProfileIfNeeded()
                        if (fallbackProfile != null) {
                            profiles.setActiveProfile(fallbackProfile.id)
                        }
                    }
                }
                skipProfileSelection = skipSelection
            }
            val oledBlackBackground by remember {
                this@MainActivity.settingsDataStore.data.map { it[OLED_BLACK_BACKGROUND_KEY] ?: false }
            }.collectAsStateWithLifecycle(initialValue = false)
            val focusBorderColorName by remember {
                this@MainActivity.settingsDataStore.data.map { it[FOCUS_BORDER_COLOR_KEY] }
            }.collectAsStateWithLifecycle(initialValue = null)
            val selectedTheme by remember {
                this@MainActivity.settingsDataStore.data.map { it[THEME_KEY] ?: "Midnight" }
            }.collectAsStateWithLifecycle(initialValue = "Midnight")
            val activeProfileId by remember {
                profileRepository.get().activeProfileId
            }.collectAsStateWithLifecycle(initialValue = null)
            val appLanguage by remember(activeProfileId) {
                this@MainActivity.settingsDataStore.data.map { prefs ->
                    val fallbackLanguage = prefs[LAST_APP_LANGUAGE_KEY] ?: "en-US"
                    val profileId = activeProfileId
                    if (profileId.isNullOrBlank()) {
                        fallbackLanguage
                    } else {
                        prefs[stringPreferencesKey("profile_${profileId}_content_language")] ?: fallbackLanguage
                    }
                }
            }.collectAsStateWithLifecycle(initialValue = "en-US")
            LaunchedEffect(appLanguage) {
                mediaRepository.get().contentLanguage = if (appLanguage == "en-US") null else appLanguage
            }
            val deviceType = when (deviceModeOverride) {
                "tv" -> DeviceType.TV
                "tablet" -> DeviceType.TABLET
                "phone" -> DeviceType.PHONE
                else -> initialDeviceType
            }
            val hasTouchScreen = remember { deviceHasTouchScreen(this@MainActivity) }
            // If no touchscreen, force TV mode regardless of override setting
            // (prevents tablet/phone UI on devices with only D-pad input)
            val effectiveDeviceType = if (!hasTouchScreen && deviceType != DeviceType.TV) DeviceType.TV else deviceType
            // Wrap the Activity as a ContextWrapper that only overrides getResources() with
            // localized resources. Hilt traverses ContextWrapper chains to find the Activity,
            // so hiltViewModel() still works correctly.
            val localizedContext = remember(appLanguage) {
                val locale = com.arflix.tv.util.appLocale(appLanguage)
                java.util.Locale.setDefault(locale)
                val config = Configuration(this@MainActivity.resources.configuration)
                config.setLocale(locale)
                val localizedRes = this@MainActivity.createConfigurationContext(config).resources
                object : android.content.ContextWrapper(this@MainActivity) {
                    override fun getResources() = localizedRes
                }
            }
            val isRtl = remember(appLanguage) {
                val lang = java.util.Locale.forLanguageTag(appLanguage.replace('_', '-')).language
                lang in listOf("ar", "he", "fa", "ur")
            }
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localizedContext,
                LocalAppLanguage provides appLanguage,
                LocalDeviceType provides effectiveDeviceType,
                LocalHasTouchScreen provides hasTouchScreen,
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    if (isRtl) androidx.compose.ui.unit.LayoutDirection.Rtl
                    else androidx.compose.ui.unit.LayoutDirection.Ltr
            ) {
                ArflixTvTheme(
                    oledBlackBackground = oledBlackBackground,
                    focusBorderColorName = focusBorderColorName,
                    themeName = selectedTheme
                ) {
                    val startupState by startupViewModel.state.collectAsStateWithLifecycle()
                    ArflixApp(
                        authRepository = authRepository.get(),
                        profileRepository = profileRepository.get(),
                        traktRepository = traktRepository.get(),
                        profileManager = profileManager.get(),
                        watchHistoryRepository = watchHistoryRepository.get(),
                        watchlistRepository = watchlistRepository.get(),
                        iptvRepository = iptvRepository.get(),
                        navSectionRepository = navSectionRepository.get(),
                        launcherContinueWatchingRepository = launcherContinueWatchingRepository.get(),
                        oledBlackBackground = oledBlackBackground,
                        skipProfileSelection = skipProfileSelection,
                        pendingLauncherRequest = pendingLauncherRequest,
                        onConsumeLauncherRequest = { pendingLauncherRequest = null },
                        pendingReminderChannelId = pendingReminderChannelId,
                        onConsumeReminderRequest = { pendingReminderChannelId = null },
                        pendingWidgetDeepLink = pendingWidgetDeepLink,
                        onConsumeWidgetDeepLink = { pendingWidgetDeepLink = null },
                        pendingRemotePlayRequest = pendingRemotePlayRequest,
                        onConsumeRemotePlayRequest = { pendingRemotePlayRequest = null },
                        pendingRemoteSearchQuery = pendingRemoteSearchQuery,
                        onConsumeRemoteSearchQuery = { pendingRemoteSearchQuery = null },
                        pendingRemoteChannelId = pendingRemoteChannelId,
                        onConsumeRemoteChannelRequest = { pendingRemoteChannelId = null },
                        preloadedCategories = startupState.categories,
                        preloadedHeroItem = startupState.heroItem,
                        preloadedHeroLogoUrl = startupState.heroLogoUrl,
                        preloadedLogoCache = startupState.logoCache,
                        onExitApp = { finish() },
                        episeerrPollManager = episeerrPollManager,
                        notificationPollManager = notificationPollManager,
                        navigateHomeSignal = navigateHomeSignal,
                        navigateSettingsSignal = navigateSettingsSignal,
                        navigateSmartHomeSignal = navigateSmartHomeSignal,
                        navigateToSignal = navigateToSignal,
                        onEpisodeReady = { runCatching { homeServerRepository.get().invalidateEpisodeCache() } },
                    )
                }
            }
        }

        if (BuildConfig.DEBUG) {
            jankStats = JankStats.createAndTrack(window) { frameData ->
                if (frameData.isJank) {
                    val durationMs = frameData.frameDurationUiNanos / 1_000_000
                }
            }
            PerformanceMetricsState.getHolderForHierarchy(window.decorView)
                .state?.putState("screen", "Main")
        }

        lifecycleScope.launch {
            val launcherEnabled = settingsDataStore.data.first()[com.arflix.tv.data.repository.LAUNCHER_MODE_KEY] ?: false
            val aliasComponent = android.content.ComponentName(packageName, "com.arflix.tv.LauncherActivity")
            val state = if (launcherEnabled)
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching { packageManager.setComponentEnabledSetting(aliasComponent, state, android.content.pm.PackageManager.DONT_KILL_APP) }
        }

        lifecycleScope.launch {
            remoteCommandBus.incoming.collect { command ->
                when (command) {
                    is com.arflix.tv.data.repository.RemoteCommand.TuneChannel ->
                        pendingRemoteChannelId = command.localChannelId
                    is com.arflix.tv.data.repository.RemoteCommand.PlayTitle ->
                        pendingRemotePlayRequest = command
                    is com.arflix.tv.data.repository.RemoteCommand.TypeText ->
                        pendingRemoteSearchQuery = command.text
                    is com.arflix.tv.data.repository.RemoteCommand.DPad ->
                        dispatchRemoteDpadKey(command.key)
                }
            }
        }

        runAfterFirstDraw {
            lifecycleScope.launch {
                authRepository.get().checkAuthState()
            }
            ArflixApplication.instance.scheduleTraktSyncIfNeeded()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(60_000L)
                val repo = iptvRepository.get()
                runCatching { repo.prefetchFreshStartupData() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (wasInBackground) {
            wasInBackground = false
            navigateHomeSignal.value++
        }
    }

    override fun onStop() {
        super.onStop()
        wasInBackground = true
    }

    // Xadarr is single-activity/singleTask and never finishes when another app takes the
    // foreground (e.g. the Plex/TiviMate playback handoff — see
    // [[project_dv_atmos_passthrough_2026-07-30]]), so its whole Compose tree, ViewModels, and
    // image cache stay fully resident unless something proactively releases them. Trim the image
    // cache under real memory pressure so backgrounded Xadarr isn't a dead weight contributing to
    // OOM risk on constrained devices (measured 321MB RSS backgrounded on a 3GB Shield tonight).
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            runCatching { imageLoader.memoryCache?.clear() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        wasInBackground = false
        if (intent.action == "com.xadarr.tv.OPEN_SETTINGS") {
            navigateSettingsSignal.value++
            return
        }
        pendingLauncherRequest = parseLauncherRequest(intent)
        pendingReminderChannelId = intent.getStringExtra(
            com.arflix.tv.worker.ProgramReminderWorker.EXTRA_REMINDER_CHANNEL_ID
        )
        pendingWidgetDeepLink = com.arflix.tv.widget.parseWidgetDeepLink(intent)
        if (pendingLauncherRequest == null && pendingReminderChannelId == null && pendingWidgetDeepLink == null) {
            navigateHomeSignal.value++
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Re-apply immersive mode only for TV when window regains focus.
            // Mobile fullscreen is managed per-screen (e.g. player).
            val currentDeviceType = detectDeviceType(this)
            if (currentDeviceType == DeviceType.TV) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(goHomeReceiver) }
        runCatching { unregisterReceiver(goSettingsReceiver) }
        runCatching { unregisterReceiver(navigateToReceiver) }
        jankStats?.isTrackingEnabled = false
        jankStats = null
        super.onDestroy()
    }

    // Remote Mode D-pad popup — synthesizes a matched DOWN/UP pair through the same
    // dispatchKeyEvent path a physical remote press takes, so it reaches whatever Compose
    // composable currently has focus regardless of which screen is showing.
    private fun dispatchRemoteDpadKey(key: com.arflix.tv.data.repository.DPadKey) {
        if (key == com.arflix.tv.data.repository.DPadKey.VOLUME_UP || key == com.arflix.tv.data.repository.DPadKey.VOLUME_DOWN) {
            // Neither AudioManager.adjustStreamVolume nor Activity.dispatchKeyEvent reaches CEC-
            // forwarded audio (confirmed on-device via logcat: the physical remote's volume
            // presses show up as WindowManager.handleComboKeys — system-level interception that
            // happens BEFORE any app's window ever sees the event. dispatchKeyEvent() starts
            // downstream of that, inside this Activity's own window, so it skips the exact
            // handling that forwards to CEC). AudioManager.dispatchMediaKeyEvent() is the
            // sanctioned way for an app to inject a real system-wide media/volume key — the same
            // path a Bluetooth headset's volume button uses — and does reach that handling.
            val keyCode = if (key == com.arflix.tv.data.repository.DPadKey.VOLUME_UP) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP
            } else {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN
            }
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val now = android.os.SystemClock.uptimeMillis()
            runCatching {
                audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0))
                audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0))
            }
            return
        }
        val keyCode = when (key) {
            com.arflix.tv.data.repository.DPadKey.UP -> android.view.KeyEvent.KEYCODE_DPAD_UP
            com.arflix.tv.data.repository.DPadKey.DOWN -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            com.arflix.tv.data.repository.DPadKey.LEFT -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            com.arflix.tv.data.repository.DPadKey.RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            com.arflix.tv.data.repository.DPadKey.CENTER -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
            com.arflix.tv.data.repository.DPadKey.BACK -> android.view.KeyEvent.KEYCODE_BACK
            com.arflix.tv.data.repository.DPadKey.PLAY_PAUSE -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            com.arflix.tv.data.repository.DPadKey.STOP -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            com.arflix.tv.data.repository.DPadKey.REWIND -> android.view.KeyEvent.KEYCODE_MEDIA_REWIND
            com.arflix.tv.data.repository.DPadKey.FAST_FORWARD -> android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            com.arflix.tv.data.repository.DPadKey.VOLUME_UP, com.arflix.tv.data.repository.DPadKey.VOLUME_DOWN ->
                return // handled above
        }
        val now = android.os.SystemClock.uptimeMillis()
        dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0))
        dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0))
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val isSettingsKey = event.keyCode == android.view.KeyEvent.KEYCODE_SETTINGS ||
                event.keyCode == android.view.KeyEvent.KEYCODE_UNKNOWN
            if (isSettingsKey) {
                navigateSettingsSignal.value++
                return true
            }
            // Shield remote's Menu button — quick-access popup for Smart Home.
            // Other remotes rarely send this keycode, which is fine.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                navigateSmartHomeSignal.value++
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

private fun MainActivity.parseLauncherRequest(intent: android.content.Intent?): LauncherContinueWatchingRequest? {
    return intent?.data?.toLauncherContinueWatchingRequest()
}

private fun ComponentActivity.runAfterFirstDraw(block: () -> Unit) {
    val content = window.decorView
    content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            content.viewTreeObserver.removeOnPreDrawListener(this)
            content.post { block() }
            return true
        }
    })
}

/**
 * Xadarr loading screen - app logo + spinner
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun XadarrLoadingScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val reveal = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 920, easing = FastOutSlowInEasing)
        )
    }

    val sweep by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    val logoAlpha by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = Color.Black)

            val progress = reveal.value
            val logoCenterY = center.y - 8.dp.toPx()
            val baselineY = logoCenterY + 138.dp.toPx()

            val halfWidth = 180.dp.toPx() * progress
            val lineStartX = center.x - halfWidth
            val lineEndX = center.x + halfWidth
            drawLine(
                color = Color(0xFF00F0D0).copy(alpha = 0.32f * progress),
                start = Offset(lineStartX, baselineY),
                end = Offset(lineEndX, baselineY),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round
            )

            val sweepHalfWidth = 34.dp.toPx()
            val sweepTravel = (halfWidth - sweepHalfWidth).coerceAtLeast(0f)
            val sweepX = center.x + (sweep * sweepTravel)
            drawLine(
                color = Color.White.copy(alpha = 0.54f * progress),
                start = Offset(sweepX - sweepHalfWidth, baselineY),
                end = Offset(sweepX + sweepHalfWidth, baselineY),
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        Image(
            painter = painterResource(id = R.drawable.xadarr_loading_logo),
            contentDescription = "Xadarr",
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth(0.52f)
                .widthIn(max = 320.dp)
                .graphicsLayer {
                    alpha = reveal.value * logoAlpha
                    val scale = 0.88f + (0.12f * reveal.value)
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - reveal.value) * 18.dp.toPx()
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * Root composable for the Xadarr app
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ArflixApp(
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    traktRepository: TraktRepository,
    profileManager: ProfileManager,
    watchHistoryRepository: WatchHistoryRepository,
    watchlistRepository: WatchlistRepository,
    iptvRepository: com.arflix.tv.data.repository.IptvRepository,
    navSectionRepository: com.arflix.tv.data.repository.NavSectionRepository,
    launcherContinueWatchingRepository: LauncherContinueWatchingRepository,
    oledBlackBackground: Boolean = false,
    skipProfileSelection: Boolean? = null,
    pendingLauncherRequest: LauncherContinueWatchingRequest? = null,
    onConsumeLauncherRequest: () -> Unit = {},
    pendingReminderChannelId: String? = null,
    onConsumeReminderRequest: () -> Unit = {},
    pendingWidgetDeepLink: com.arflix.tv.widget.WidgetDeepLinkRequest? = null,
    onConsumeWidgetDeepLink: () -> Unit = {},
    pendingRemotePlayRequest: com.arflix.tv.data.repository.RemoteCommand.PlayTitle? = null,
    onConsumeRemotePlayRequest: () -> Unit = {},
    pendingRemoteSearchQuery: String? = null,
    onConsumeRemoteSearchQuery: () -> Unit = {},
    pendingRemoteChannelId: String? = null,
    onConsumeRemoteChannelRequest: () -> Unit = {},
    preloadedCategories: List<com.arflix.tv.data.model.Category> = emptyList(),
    preloadedHeroItem: com.arflix.tv.data.model.MediaItem? = null,
    preloadedHeroLogoUrl: String? = null,
    preloadedLogoCache: Map<String, String> = emptyMap(),
    onExitApp: () -> Unit = {},
    episeerrPollManager: EpiseerrPollManager? = null,
    notificationPollManager: NotificationPollManager? = null,
    navigateHomeSignal: kotlinx.coroutines.flow.StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0),
    navigateSettingsSignal: kotlinx.coroutines.flow.StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0),
    navigateSmartHomeSignal: kotlinx.coroutines.flow.StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0),
    navigateToSignal: kotlinx.coroutines.flow.SharedFlow<String> = kotlinx.coroutines.flow.MutableSharedFlow(),
    onEpisodeReady: () -> Unit = {},
) {
    val context = LocalContext.current
    val authState by authRepository.authState.collectAsStateWithLifecycle()
    val activeProfileState by remember(profileRepository) {
        profileRepository.activeProfile.map { profile ->
            ActiveProfileLoadState.Loaded(profile) as ActiveProfileLoadState
        }
    }.collectAsStateWithLifecycle(initialValue = ActiveProfileLoadState.Loading)
    var startupIntroComplete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1350)
        startupIntroComplete = true
    }
    val activeProfile = (activeProfileState as? ActiveProfileLoadState.Loaded)?.profile
    val startupReady = skipProfileSelection != null &&
        activeProfileState is ActiveProfileLoadState.Loaded &&
        authState !is AuthState.Loading

    if (!startupReady || !startupIntroComplete) {
        XadarrLoadingScreen()
        return
    }

    // Activity-scoped — survives all navigation changes. Created here (above NavHost)
    // so hiltViewModel() uses the Activity's ViewModelStoreOwner.
    val liveTvPlayerViewModel: LiveTvPlayerViewModel = hiltViewModel()

    // Belt-and-suspenders alongside LiveTvPlayerViewModel's own ProcessLifecycleOwner observer
    // (which should already pause on backgrounding, but evidently didn't reliably in practice —
    // audio kept playing after launching a different app entirely). Wired directly to this
    // Activity's own lifecycle (the same one MainActivity.onStop() observes) rather than trusting
    // the global process-level observer alone.
    val activityLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(activityLifecycleOwner, liveTvPlayerViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                liveTvPlayerViewModel.pauseForVod()
            }
        }
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { activityLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(episeerrPollManager) { episeerrPollManager?.startPolling() }
    LaunchedEffect(notificationPollManager) { notificationPollManager?.startPolling() }

    var activeAppNotification by remember { mutableStateOf<AppNotification?>(null) }
    LaunchedEffect(notificationPollManager) {
        notificationPollManager?.notificationEvents?.collect { notification ->
            // When a new episode lands in the library, drop the stale JF source cache
            // so the next play attempt queries Jellyfin fresh instead of finding nothing.
            if (notification.type == "ready" || notification.type == "episode.ready") {
                runCatching { onEpisodeReady() }
            }
            activeAppNotification = notification
            delay(4_000L)
            activeAppNotification = null
        }
    }

    val navController = rememberNavController()
    val appCoroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(navController) {
        var seen = navigateHomeSignal.value
        navigateHomeSignal.collect { count ->
            if (count > seen) {
                seen = count
                navController.navigate(Screen.Home.createRoute()) {
                    popUpTo(Screen.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }
    LaunchedEffect(navController) {
        var seen = navigateSettingsSignal.value
        navigateSettingsSignal.collect { count ->
            if (count > seen) {
                seen = count
                navController.navigate(Screen.Settings.route) {
                    launchSingleTop = true
                }
            }
        }
    }
    LaunchedEffect(navController) {
        var seen = navigateSmartHomeSignal.value
        navigateSmartHomeSignal.collect { count ->
            if (count > seen) {
                seen = count
                navController.navigate(Screen.SmartHome.route) {
                    launchSingleTop = true
                }
            }
        }
    }
    LaunchedEffect(navController) {
        navigateToSignal.collect { destination ->
            val route = when (destination) {
                "live_tv" -> Screen.Home.createRoute()
                "cameras" -> Screen.Cameras.route
                "discover" -> Screen.Discover.route
                "search" -> Screen.Search.route
                "home" -> Screen.Home.createRoute()
                "settings" -> Screen.Settings.route
                else -> null
            }
            route?.let {
                navController.navigate(it) { launchSingleTop = true }
            }
        }
    }
    var lastAddonsSyncKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authState, activeProfile?.id) {
        if (authState is AuthState.NotAuthenticated) {
            lastAddonsSyncKey = null
        }
        if (activeProfile != null) {
            launcherContinueWatchingRepository.refreshForCurrentProfile()
        } else {
            launcherContinueWatchingRepository.clearPublishedPrograms()
        }
    }

    // NavHost's startDestination matches by exact route-pattern ID (NavGraph.setStartDestination
    // hashes the literal string passed against each destination's registered `route`), unlike
    // navigate() which goes through real URI-template/deep-link matching — so this must stay the
    // raw pattern (Screen.Home.route), not a resolved Screen.Home.createRoute() value.
    val deviceType = LocalDeviceType.current
    val isMobile = deviceType.isTouchDevice()

    // Mobile lands on the Episeerr dashboard, not Home/Guide — see DashboardScreen.kt and
    // AppNavigation's postLoginRoute (the *other* entry point, when profile selection isn't
    // skipped). Home itself is untouched; the Guide bottom-bar tab still goes there.
    val startDestination = if (skipProfileSelection == true && activeProfile != null) {
        if (isMobile) Screen.Dashboard.route else Screen.Home.route
    } else {
        Screen.ProfileSelection.route
    }
    val neolinkConfigured by remember {
        context.settingsDataStore.data.map { prefs ->
            prefs[NEOLINK_URL_KEY]?.isNotBlank() == true
        }
    }.collectAsStateWithLifecycle(initialValue = false)
    val plexLauncherModeActive by remember {
        context.settingsDataStore.data.map { prefs ->
            (prefs[com.arflix.tv.data.repository.LAUNCHER_MODE_KEY] ?: false) &&
                (prefs[com.arflix.tv.data.repository.PLAY_VOD_VIA_PLEX_KEY] ?: false)
        }
    }.collectAsStateWithLifecycle(initialValue = false)
    // TiviMate-clone redesign: the nav-section list from NavSectionRepository.defaultSections()
    // (Guide/Movies/Shows/Apps/Cameras/Settings) is now the permanent, unconditional default —
    // no runtime filtering needed. This used to conditionally merge Movies+Shows into one
    // "plex_library" row and hide Search/Discover only when Plex Launcher Mode was on; that
    // whole transform is superseded now that separate Movies/Shows-launch-Plex entries and the
    // absence of Search/Discover are just how the nav model is seeded by default.
    val rawNavSections by remember(navSectionRepository) {
        navSectionRepository.observeSectionsForActiveProfile()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val navSections = rawNavSections
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    var iptvFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        if (currentRoute?.startsWith("home") != true) {
            iptvFullscreen = false
        }
    }
    // Hide bottom bar on player, profile selection, and login screens.
    // TV route shows the bottom bar on mobile (touch devices) for easy navigation;
    // the fullscreen IPTV player uses BackHandler to return to the guide.
    val showBottomBar = isMobile && activeProfile != null &&
        currentRoute != null &&
        !iptvFullscreen &&
        !currentRoute.contains("player") &&
        !currentRoute.contains("profile") &&
        !currentRoute.contains("login")

    val episeerrPendingIds by (episeerrPollManager?.pendingTmdbIds
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()))
        .collectAsState()
    val gameDayEvents by (episeerrPollManager?.gameDayEvents
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.arflix.tv.data.repository.GameDayEvent>()))
        .collectAsState()

    CompositionLocalProvider(
        LocalNeolinkConfigured provides neolinkConfigured,
        com.arflix.tv.util.LocalNavSections provides navSections,
        com.arflix.tv.util.LocalPlexLauncherMode provides plexLauncherModeActive,
        com.arflix.tv.util.LocalEpiseerrPendingIds provides episeerrPendingIds,
        com.arflix.tv.util.LocalGameDayEvents provides gameDayEvents,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Background fills edge-to-edge (including behind transparent bars).
            .background(
                brush = if (oledBlackBackground) {
                    Brush.linearGradient(colors = listOf(Color.Black, Color.Black))
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            appBackgroundDark(),
                            appBackgroundDark(),
                            appBackgroundDark()
                        )
                    )
                }
            )
            // On mobile, push content between the status bar and navigation bar.
            // Applied AFTER background so the gradient fills behind the bars.
            // systemBarsPadding() reads live WindowInsets, so it automatically
            // becomes 0 when the player hides the bars.
            .then(if (isMobile) Modifier.systemBarsPadding() else Modifier)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            AppNavigation(
                navController = navController,
                startDestination = startDestination,
                preloadedCategories = preloadedCategories,
                preloadedHeroItem = preloadedHeroItem,
                preloadedHeroLogoUrl = preloadedHeroLogoUrl,
                preloadedLogoCache = preloadedLogoCache,
                currentProfile = activeProfile,
                liveTvPlayerViewModel = liveTvPlayerViewModel,
                onSwitchProfile = {
                    appCoroutineScope.launch {
                        traktRepository.clearAllProfileCaches()
                        watchHistoryRepository.clearProfileCaches()
                        watchlistRepository.clearWatchlistCache()
                        iptvRepository.invalidateCache()
                        profileManager.setCurrentProfileId("default")
                        profileManager.setCurrentProfileName("default")
                        profileRepository.clearActiveProfile()
                    }
                },
                onTvFullscreenChanged = { fullscreen ->
                    iptvFullscreen = fullscreen
                },
                onExitApp = onExitApp
            )

            // Roaming mini-player pip (floating tile that followed live TV across other
            // screens) removed — belonged to the old Home/Movies/Shows-as-peer-screens model.
            // Live TV is its own TiviMate-style screen now: audio only plays while the Guide
            // itself is the visible screen, full stop — Joe: "no more mini player, just on the
            // guide." Every other route (Player, Cameras, Details, Settings, Movies/Shows, the
            // mobile dashboard, ...) stops it, not just the two that used to be special-cased.
            val onGuide = currentRoute == Screen.Home.route
            LaunchedEffect(onGuide) {
                if (!onGuide) liveTvPlayerViewModel.dismiss()
            }

            // Remote Mode control panel — mobile-only, works from any tab/screen: swipe down
            // from the top edge to reveal it (notification-shade style), swipe up on the panel
            // or tap the scrim to dismiss back to browsing. Distinct from the Guide's own
            // "Remote" pill, which is the explicit per-channel redirect toggle and stays scoped
            // to TvViewModel/LiveTvScreen — this is purely a control surface (pick a device,
            // D-pad, transport, volume, text) for whatever's already playing on the target, so
            // it can coexist with normal local browsing/watching. See RemoteModeViewModel.
            if (isMobile) {
                val remoteModeViewModel: com.arflix.tv.ui.components.RemoteModeViewModel = hiltViewModel()
                val remoteTarget by remoteModeViewModel.target.collectAsState()
                var showRemoteModeSheet by remember { mutableStateOf(false) }
                var showTvRemotePairingDialogFor by remember { mutableStateOf<String?>(null) }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(28.dp)
                        .zIndex(30f)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount > 12f) showRemoteModeSheet = true
                            }
                        }
                )
                if (remoteTarget != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp, end = 16.dp)
                            .zIndex(30f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable { showRemoteModeSheet = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.SettingsRemote,
                            contentDescription = "Remote Mode",
                            tint = com.arflix.tv.ui.theme.Pink,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        androidx.tv.material3.Text(
                            text = remoteTarget!!.displayName,
                            fontSize = 12.sp,
                            color = Color.White,
                        )
                    }
                }
                val remoteLanPeers by remoteModeViewModel.peers.collectAsState()
                com.arflix.tv.ui.components.RemoteModeTopPanel(
                    visible = showRemoteModeSheet,
                    peers = remoteLanPeers,
                    target = remoteTarget,
                    onSelectTarget = { remoteModeViewModel.setTarget(it) },
                    onSendDpad = { key ->
                        // Volume specifically tries the Android TV Remote Service path first
                        // (real system-level volume — reaches CEC-forwarded external audio the
                        // app-level HTTP path can't) and falls back to it automatically if the
                        // target isn't paired for that yet. See RemoteModeViewModel.
                        val host = remoteTarget?.host
                        when {
                            host == null -> remoteModeViewModel.sendDpad(key)
                            key == com.arflix.tv.data.repository.DPadKey.VOLUME_UP -> remoteModeViewModel.sendVolumeUp(host)
                            key == com.arflix.tv.data.repository.DPadKey.VOLUME_DOWN -> remoteModeViewModel.sendVolumeDown(host)
                            else -> remoteModeViewModel.sendDpad(key)
                        }
                    },
                    onSendText = { text -> remoteModeViewModel.sendText(text) },
                    onDismiss = { showRemoteModeSheet = false },
                    isTvRemotePaired = remoteTarget?.host?.let { host ->
                        androidx.compose.runtime.produceState(false, host) {
                            value = remoteModeViewModel.isTvRemotePaired(host)
                        }.value
                    } ?: false,
                    onPairTvRemote = {
                        val host = remoteTarget?.host
                        if (host != null) showTvRemotePairingDialogFor = host
                    },
                    onSendPower = {
                        remoteTarget?.host?.let { remoteModeViewModel.sendPower(it) } ?: false
                    },
                )
                val pairingHost = showTvRemotePairingDialogFor
                if (pairingHost != null) {
                    com.arflix.tv.ui.components.TvRemotePairingDialog(
                        host = pairingHost,
                        onStart = { remoteModeViewModel.startTvRemotePairing() },
                        onFinished = {
                            appCoroutineScope.launch { remoteModeViewModel.onTvRemotePairingFinished(pairingHost) }
                        },
                        onDismiss = { showTvRemotePairingDialogFor = null },
                    )
                }
            }

            // ── App notification toast ───────────────────────────────────────────
            androidx.compose.animation.AnimatedVisibility(
                visible = activeAppNotification != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
            ) {
                activeAppNotification?.let { AppNotificationToast(it) }
            }
        }
        if (showBottomBar) {
            AppBottomBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo("home") { inclusive = false }
                        launchSingleTop = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    } // end CompositionLocalProvider

    LaunchedEffect(activeProfile?.id, pendingLauncherRequest) {
        val request = pendingLauncherRequest ?: return@LaunchedEffect
        if (activeProfile == null) return@LaunchedEffect

        val route = Screen.Details.createRoute(
            mediaType = request.mediaType,
            mediaId = request.mediaId,
            initialSeason = request.season,
            initialEpisode = request.episode
        )
        navController.navigate(route) {
            popUpTo(Screen.ProfileSelection.route) { inclusive = true }
            launchSingleTop = true
        }
        onConsumeLauncherRequest()
    }

    LaunchedEffect(activeProfile?.id, pendingReminderChannelId) {
        val channelId = pendingReminderChannelId ?: return@LaunchedEffect
        if (activeProfile == null) return@LaunchedEffect
        navController.navigate(Screen.Home.createRoute(channelId = channelId)) {
            popUpTo(Screen.ProfileSelection.route) { inclusive = true }
            launchSingleTop = true
        }
        onConsumeReminderRequest()
    }

    // Widget tap deep-link — the TMDB id is pre-resolved at widget-refresh time (see
    // ActivityFeedRepository), since Glance's actionStartActivity fires a plain Intent
    // synchronously and can't do an async lookup at tap time. No match / Game Day items carry
    // no extras at all, so this is a no-op and the app just opens normally (see WidgetTapIntent).
    LaunchedEffect(activeProfile?.id, pendingWidgetDeepLink) {
        val request = pendingWidgetDeepLink ?: return@LaunchedEffect
        if (activeProfile == null) return@LaunchedEffect
        navController.navigate(Screen.Details.createRoute(request.mediaType, request.tmdbId)) {
            popUpTo(Screen.ProfileSelection.route) { inclusive = true }
            launchSingleTop = true
        }
        onConsumeWidgetDeepLink()
    }

    // Remote Mode — receiving side. A play-title command already decided to play; Details
    // reads autoPlay and invokes its own playNow() once loaded (see DetailsScreen.kt).
    LaunchedEffect(activeProfile?.id, pendingRemotePlayRequest) {
        val request = pendingRemotePlayRequest ?: return@LaunchedEffect
        if (activeProfile == null) return@LaunchedEffect
        navController.navigate(
            Screen.Details.createRoute(
                mediaType = request.mediaType,
                mediaId = request.tmdbId,
                initialSeason = request.season,
                initialEpisode = request.episode,
                autoPlay = true,
            )
        ) {
            popUpTo(Screen.ProfileSelection.route) { inclusive = true }
            launchSingleTop = true
        }
        onConsumeRemotePlayRequest()
    }

    // Remote Mode text-entry popup — routes to Home (the guide) with a searchQuery so
    // LiveTvScreen opens its SearchOverlay pre-filled, the app's only search surface.
    LaunchedEffect(activeProfile?.id, pendingRemoteSearchQuery) {
        val query = pendingRemoteSearchQuery ?: return@LaunchedEffect
        if (activeProfile == null) return@LaunchedEffect
        navController.navigate(Screen.Home.createRoute(searchQuery = query)) {
            popUpTo(Screen.Home.route) { inclusive = false }
            launchSingleTop = true
        }
        onConsumeRemoteSearchQuery()
    }

    // Remote Mode channel tune — only navigates when NOT already on Home/the guide. When
    // already there, TvViewModel's own direct RemoteCommandBus collection in LiveTvScreen
    // handles it in place (confirmed working on-device); navigating on top of that turned out
    // to be actively harmful, not just redundant — popUpTo(Screen.Home.route){inclusive=true}
    // does NOT clear rememberSaveable state the way it looks like it should (proved via the
    // on-screen debug overlay), so it was just an extra recomposition racing the direct
    // assignment, which is the likely cause of "channel changed but stayed in the guide
    // overlay instead of going fullscreen" — this still matters for the case where the app is
    // on some other screen entirely and LiveTvScreen isn't composed to receive the command
    // directly at all.
    LaunchedEffect(activeProfile?.id, pendingRemoteChannelId) {
        val channelId = pendingRemoteChannelId ?: return@LaunchedEffect
        if (activeProfile == null) return@LaunchedEffect
        if (currentRoute == Screen.Home.route) {
            onConsumeRemoteChannelRequest()
            return@LaunchedEffect
        }
        navController.navigate(Screen.Home.createRoute(channelId = channelId)) {
            popUpTo(Screen.Home.route) { inclusive = true }
        }
        onConsumeRemoteChannelRequest()
    }
}

@Composable
private fun AppNotificationToast(notification: AppNotification) {
    val badgeColor = when (notification.type) {
        "grab"    -> Color(0xFFEA580C) // orange
        "ready"   -> Color(0xFF0D9488) // teal
        "error"   -> Color(0xFFDC2626) // red
        "warning" -> Color(0xFFD97706) // amber
        else      -> Color(0xFF2563EB) // blue for info/unknown
    }
    Box(
        modifier = Modifier
            .widthIn(max = 400.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6111827))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    notification.source.ifBlank { "Notify" },
                    fontSize = 10.sp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column {
                Text(
                    notification.title.ifBlank { "Unknown" },
                    fontSize = 14.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!notification.message.isNullOrBlank()) {
                    Text(notification.message, fontSize = 11.sp, color = Color(0xFFD1D5DB))
                }
            }
        }
    }
}

private fun enqueueFullTraktSync(context: android.content.Context) {
    val request = OneTimeWorkRequestBuilder<TraktSyncWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(
            workDataOf(TraktSyncWorker.INPUT_SYNC_MODE to TraktSyncWorker.SYNC_MODE_FULL)
        )
        .addTag(TraktSyncWorker.TAG)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        "trakt_sync_after_auth",
        ExistingWorkPolicy.REPLACE,
        request
    )
}
