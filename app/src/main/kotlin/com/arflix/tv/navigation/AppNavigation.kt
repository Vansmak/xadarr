package com.arflix.tv.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.AuthState
import com.arflix.tv.util.settingsDataStore
import kotlinx.coroutines.flow.first
import com.arflix.tv.ui.screens.cameras.CameraPlayerScreen
import com.arflix.tv.ui.screens.cameras.CamerasScreen
import com.arflix.tv.ui.screens.home.AllAppsScreen
import com.arflix.tv.ui.screens.details.DetailsScreen
import com.arflix.tv.ui.screens.library.PlexLibraryScreen
import com.arflix.tv.ui.screens.login.LoginScreen
import com.arflix.tv.ui.screens.player.PlayerScreen
import com.arflix.tv.ui.screens.collections.CollectionDetailsScreen
import com.arflix.tv.ui.screens.settings.SettingsScreen
import com.arflix.tv.ui.screens.tv.live.LiveTvPlayerViewModel
import com.arflix.tv.ui.screens.tv.live.LiveTvScreen
import com.arflix.tv.ui.screens.profile.ProfileSelectionScreen
import com.arflix.tv.util.LocalDeviceType

/**
 * Navigation destinations
 */
sealed class Screen(val route: String) {
    object Login : Screen("login")
    // Home IS the Live TV guide (TiviMate-style redesign) — folded from the old Screen.Tv
    // rather than kept as a second route to the same place, so there's exactly one
    // back-stack entry for "the guide" instead of two logically-identical ones.
    object Home : Screen("home?channelId={channelId}&streamUrl={streamUrl}") {
        fun createRoute(channelId: String? = null, streamUrl: String? = null): String {
            if (channelId == null) return "home"
            val enc = java.net.URLEncoder.encode(channelId, "UTF-8")
            val streamEnc = streamUrl?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            return if (streamEnc != null) "home?channelId=$enc&streamUrl=$streamEnc" else "home?channelId=$enc"
        }
    }
    // Search/Watchlist/Discover: retired from the nav model (Movies/Shows browse a live Plex
    // poster grid in-app now — see PlexLibrary below — instead of in-app TMDB browsing/search).
    // Routes kept as harmless redirect-to-Home stubs rather than deleted, so no stray deep
    // link or leftover call site can crash the app.
    object Search : Screen("search")
    object Watchlist : Screen("watchlist")
    object CollectionDetails : Screen("collections/{catalogId}") {
        fun createRoute(catalogId: String): String {
            return "collections/${android.net.Uri.encode(catalogId)}"
        }
    }
    object Discover : Screen("discover")
    // Live Plex library poster grid — Movies/Shows destination. Browsing happens in-app;
    // selecting a title deep-links out to Plex (PlexDeepLink.kt), it doesn't play in-app.
    object PlexLibrary : Screen("plex_library/{mediaType}") {
        fun createRoute(mediaType: MediaType): String = "plex_library/${mediaType.name.lowercase()}"
    }
    object Settings : Screen("settings")
    object Cameras : Screen("cameras")
    object SmartHome : Screen("smart_home")
    object CameraPlayer : Screen("camera_player?streamUrl={streamUrl}&cameraName={cameraName}") {
        fun createRoute(streamUrl: String, cameraName: String): String {
            val encUrl = java.net.URLEncoder.encode(streamUrl, "UTF-8")
            val encName = java.net.URLEncoder.encode(cameraName, "UTF-8")
            return "camera_player?streamUrl=$encUrl&cameraName=$encName"
        }
    }
    object ProfileSelection : Screen("profile_selection")
    object AllApps : Screen("all_apps")
    
    object Details : Screen("details/{mediaType}/{mediaId}?initialSeason={initialSeason}&initialEpisode={initialEpisode}") {
        fun createRoute(
            mediaType: MediaType,
            mediaId: Int,
            initialSeason: Int? = null,
            initialEpisode: Int? = null
        ): String {
            val base = "details/${mediaType.name.lowercase()}/$mediaId"
            val params = mutableListOf<String>()
            initialSeason?.let { params.add("initialSeason=$it") }
            initialEpisode?.let { params.add("initialEpisode=$it") }
            return if (params.isNotEmpty()) "$base?${params.joinToString("&")}" else base
        }
    }
    
    object Player : Screen("player/{mediaType}/{mediaId}?seasonNumber={seasonNumber}&episodeNumber={episodeNumber}&imdbId={imdbId}&streamUrl={streamUrl}&preferredAddonId={preferredAddonId}&preferredSourceName={preferredSourceName}&preferredBingeGroup={preferredBingeGroup}&startPositionMs={startPositionMs}") {
        fun createRoute(
            mediaType: MediaType,
            mediaId: Int,
            seasonNumber: Int? = null,
            episodeNumber: Int? = null,
            imdbId: String? = null,
            streamUrl: String? = null,
            preferredAddonId: String? = null,
            preferredSourceName: String? = null,
            preferredBingeGroup: String? = null,
            startPositionMs: Long? = null
        ): String {
            val base = "player/${mediaType.name.lowercase()}/$mediaId"
            val params = mutableListOf<String>()
            seasonNumber?.let { params.add("seasonNumber=$it") }
            episodeNumber?.let { params.add("episodeNumber=$it") }
            imdbId?.let { params.add("imdbId=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            streamUrl?.let { params.add("streamUrl=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            preferredAddonId?.let { params.add("preferredAddonId=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            preferredSourceName?.let { params.add("preferredSourceName=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            preferredBingeGroup?.let { params.add("preferredBingeGroup=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            startPositionMs?.let { params.add("startPositionMs=$it") }
            return if (params.isNotEmpty()) "$base?${params.joinToString("&")}" else base
        }
    }
}

/**
 * Main navigation graph
 */
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Login.route,
    preloadedCategories: List<Category> = emptyList(),
    preloadedHeroItem: MediaItem? = null,
    preloadedHeroLogoUrl: String? = null,
    preloadedLogoCache: Map<String, String> = emptyMap(),
    currentProfile: Profile? = null,
    liveTvPlayerViewModel: LiveTvPlayerViewModel,
    onSwitchProfile: () -> Unit = {},
    onTvFullscreenChanged: (Boolean) -> Unit = {},
    onExitApp: () -> Unit = {}
) {
    val navigateTopLevel: (String) -> Unit = { route ->
        // Every destination this reaches (Movies, Shows, Cameras, Settings; Search/Discover/
        // Watchlist are harmless redirect-to-Home stubs) is somewhere other than the guide —
        // Home is reached via navigateHome() below, not this. Live TV audio should only play on
        // the guide or in an actual fullscreen show, so pause it here rather than let it keep
        // playing silently behind the side-menu screens.
        liveTvPlayerViewModel.pauseForVod()
        // launchSingleTop/saveState/restoreState used to be set here (the standard "bottom nav
        // tabs" pattern) — but Movies and Shows both resolve to the *same* parameterized
        // destination pattern (Screen.PlexLibrary = "plex_library/{mediaType}", differing only
        // in the mediaType argument), and Navigation-Compose's launchSingleTop/restoreState
        // matching operates on the destination pattern, not the fully-resolved argument-specific
        // route. Dpad-ing out of Movies into the nav rail then picking Shows landed back on
        // Movies — it treated "plex_library/{mediaType}" as already at the top and silently
        // no-op'd instead of navigating to the new argument. Going via Back first worked because
        // that actually popped the destination off first, leaving nothing to collide with.
        // popUpTo(Home) below already clears the stack back to Home on every call, so there's no
        // real back-stack-bloat risk from dropping the singleTop/restore optimization — the only
        // cost is not remembering scroll position when returning to a tab, an acceptable trade
        // for switching between tabs actually working.
        navController.navigate(route) {
            popUpTo(Screen.Home.route)
        }
    }
    val navigateToAllApps: () -> Unit = {
        liveTvPlayerViewModel.pauseForVod()
        navController.navigate(Screen.AllApps.route)
    }

    // Used to dismiss the roaming mini-player pip first (now removed — see
    // [[project_tivimate_redesign_2026-08-08]]) before allowing normal back navigation. That
    // indirection made Back from the live guide's windowed-grid state look like it froze: the
    // first press silently stopped playback (isActive was true, so it hit the dismiss branch and
    // never navigated), and only a second press actually went back.
    val goBack: () -> Unit = {
        navController.popBackStack()
    }

    val navigateHome: () -> Unit = {
        // Navigate to Home clearing the entire back stack above it.
        // Uses navigate() instead of popBackStack() because popBackStack can
        // silently fail if Home is not found, and restoreState on other
        // navigateTopLevel calls can bring back stale Details pages.
        // Screen.Home.createRoute() (not Screen.Home.route) — Home's route is now a
        // parameterized pattern, navigating to the raw pattern string would pass the
        // literal "{channelId}" placeholder text as an argument value.
        navController.navigate(Screen.Home.createRoute()) {
            popUpTo(Screen.Home.route) { inclusive = true; saveState = false }
            launchSingleTop = true
            restoreState = false
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Premium screen transitions — subtle fade + slight depth push.
        // Netflix TV uses ~250ms fade; this is tuned for Android TV's 60fps.
        // Pure crossfade — no horizontal slides (those feel mobile, not TV).
        // Netflix TV uses ~250ms crossfade for all screen transitions.
        enterTransition = { fadeIn(androidx.compose.animation.core.tween(250)) },
        exitTransition = { fadeOut(androidx.compose.animation.core.tween(200)) },
        popEnterTransition = { fadeIn(androidx.compose.animation.core.tween(250)) },
        popExitTransition = { fadeOut(androidx.compose.animation.core.tween(200)) }
    ) {
        // Login screen
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.createRoute()) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        // Home screen — IS the Live TV guide (folded from the old Screen.Tv route).
        composable(
            route = Screen.Home.route,
            arguments = listOf(
                navArgument("channelId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("streamUrl") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val initialChannelId = backStackEntry.arguments?.getString("channelId")
            val initialStreamUrl = backStackEntry.arguments?.getString("streamUrl")
            val tvContext = LocalContext.current
            // Hand off to TiviMate instead, when enabled: mirrors the Plex VOD handoff in
            // PlayerScreen.kt for the same reason (Xadarr's own player can hang on Dolby Vision
            // content a native app plays fine) — see [[project_dv_atmos_passthrough_2026-07-30]].
            // Gated on initialChannelId != null (an explicit "resume this channel" request, e.g.
            // the mini-player's expand action) rather than every Home mount — Home now IS the
            // guide, so an unconditional check here bounced every cold launch and every plain
            // return-to-Home straight to TiviMate before Xadarr's own UI ever rendered, locking
            // out Movies/Shows/Cameras/Settings entirely since they're all reached via the guide's
            // side menu. Falls through to Xadarr's own LiveTvScreen otherwise.
            var tvHandoffChecked by remember(backStackEntry) { mutableStateOf(false) }
            LaunchedEffect(backStackEntry) {
                val playViaTivimate = initialChannelId != null &&
                    (tvContext.settingsDataStore.data.first()[com.arflix.tv.data.repository.PLAY_LIVETV_VIA_TIVIMATE_KEY] ?: false)
                val tivimateIntent = if (playViaTivimate) {
                    tvContext.packageManager.getLaunchIntentForPackage("ar.tvplayer.tv")
                } else null
                if (tivimateIntent != null) {
                    liveTvPlayerViewModel.pauseForVod()
                    tvContext.startActivity(tivimateIntent)
                    // No goBack() here — this route IS Home/root now, nothing to pop back to.
                    // The composable stays blank until the user returns from TiviMate.
                } else {
                    tvHandoffChecked = true
                }
            }
            if (tvHandoffChecked) {
                LiveTvScreen(
                    playerViewModel = liveTvPlayerViewModel,
                    currentProfile = currentProfile,
                    initialChannelId = initialChannelId,
                    initialStreamUrl = initialStreamUrl,
                    onFullscreenChanged = onTvFullscreenChanged,
                    onNavigateToHome = { /* already home */ },
                    onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                    onNavigateToDiscover = { navigateTopLevel(Screen.Discover.route) },
                    onNavigateToCameras = { navigateTopLevel(Screen.Cameras.route) },
                    onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                    onNavigateToAllApps = navigateToAllApps,
                    onNavigateToMovies = { navigateTopLevel(Screen.PlexLibrary.createRoute(MediaType.MOVIE)) },
                    onNavigateToShows = { navigateTopLevel(Screen.PlexLibrary.createRoute(MediaType.TV)) },
                    onNavigateToDetails = { type, id ->
                        navController.navigate(Screen.Details.createRoute(type, id))
                    },
                    onSwitchProfile = {
                        onSwitchProfile()
                        navController.navigate(Screen.ProfileSelection.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                    onBack = goBack
                )
            }
        }

        // Search/Watchlist/Discover — retired, kept as harmless redirect-to-Home stubs
        // (Movies/Shows browse a live Plex poster grid in-app now — see PlexLibrary below).
        composable(Screen.Search.route) {
            LaunchedEffect(Unit) { navigateHome() }
        }
        composable(Screen.Watchlist.route) {
            LaunchedEffect(Unit) { navigateHome() }
        }
        composable(Screen.Discover.route) {
            LaunchedEffect(Unit) { navigateHome() }
        }

        // Plex library poster grid — Movies/Shows destination
        composable(
            route = Screen.PlexLibrary.route,
            arguments = listOf(navArgument("mediaType") { type = NavType.StringType })
        ) { backStackEntry ->
            val mediaTypeStr = backStackEntry.arguments?.getString("mediaType") ?: "movie"
            val mediaType = if (mediaTypeStr == "tv") MediaType.TV else MediaType.MOVIE
            PlexLibraryScreen(
                mediaType = mediaType,
                currentProfile = currentProfile,
                liveTvPlayerViewModel = liveTvPlayerViewModel,
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToDiscover = { navigateTopLevel(Screen.Discover.route) },
                onNavigateToTv = { navigateTopLevel(Screen.Home.createRoute()) },
                onNavigateToCameras = { navigateTopLevel(Screen.Cameras.route) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onNavigateToAllApps = navigateToAllApps,
                onNavigateToMovies = { navigateTopLevel(Screen.PlexLibrary.createRoute(MediaType.MOVIE)) },
                onNavigateToShows = { navigateTopLevel(Screen.PlexLibrary.createRoute(MediaType.TV)) },
                onNavigateToDetails = { type, id ->
                    navController.navigate(Screen.Details.createRoute(type, id))
                },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = goBack,
            )
        }

        // Settings screen
        composable(
            route = "settings?autoCloudAuth={autoCloudAuth}",
            arguments = listOf(
                navArgument("autoCloudAuth") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val autoCloudAuth = backStackEntry.arguments?.getBoolean("autoCloudAuth") ?: false
            SettingsScreen(
                currentProfile = currentProfile,
                autoStartCloudAuth = autoCloudAuth,
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToTv = { navigateTopLevel(Screen.Home.createRoute()) },
                onNavigateToDiscover = { navigateTopLevel(Screen.Discover.route) },
                onNavigateToCameras = { navigateTopLevel(Screen.Cameras.route) },
                onNavigateToSmartHome = { navController.navigate(Screen.SmartHome.route) },
                onNavigateToAllApps = navigateToAllApps,
                onNavigateToMovies = { navigateTopLevel(Screen.PlexLibrary.createRoute(MediaType.MOVIE)) },
                onNavigateToShows = { navigateTopLevel(Screen.PlexLibrary.createRoute(MediaType.TV)) },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = goBack
            )
        }

        // Profile selection screen
        composable(Screen.ProfileSelection.route) {
            ProfileSelectionScreen(
                onProfileSelected = {
                    navController.navigate(Screen.Home.createRoute()) {
                        popUpTo(Screen.ProfileSelection.route) { inclusive = true }
                    }
                },
                onShowAddProfile = { /* Handled internally by ProfileSelectionScreen */ }
            )
        }

        // Details screen
        composable(
            route = Screen.CollectionDetails.route,
            arguments = listOf(navArgument("catalogId") { type = NavType.StringType })
        ) { backStackEntry ->
            val catalogId = backStackEntry.arguments?.getString("catalogId").orEmpty()
            if (catalogId.isBlank()) {
                navigateHome()
                return@composable
            }
            CollectionDetailsScreen(
                catalogId = catalogId,
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToTv = { navigateTopLevel(Screen.Home.createRoute()) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onBack = goBack
            )
        }

        // Details screen
        composable(
            route = Screen.Details.route,
            arguments = listOf(
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("mediaId") { type = NavType.IntType },
                navArgument("initialSeason") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("initialEpisode") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            )
        ) { backStackEntry ->
            val mediaTypeStr = backStackEntry.arguments?.getString("mediaType") ?: "movie"
            val mediaId = backStackEntry.arguments?.getInt("mediaId") ?: 0
            if (mediaId <= 0) {
                navigateHome()
                return@composable
            }
            val initialSeason = backStackEntry.arguments?.getInt("initialSeason")?.takeIf { it >= 0 }
            val initialEpisode = backStackEntry.arguments?.getInt("initialEpisode")?.takeIf { it >= 0 }
            val mediaType = if (mediaTypeStr == "tv") MediaType.TV else MediaType.MOVIE

            DetailsScreen(
                mediaType = mediaType,
                mediaId = mediaId,
                initialSeason = initialSeason,
                initialEpisode = initialEpisode,
                currentProfile = currentProfile,
                liveTvPlayerViewModel = liveTvPlayerViewModel,
                onNavigateToPlayer = { type, id, season, episode, imdbId, url, preferredAddonId, preferredSourceName, startPositionMs ->
                    navController.navigate(
                        Screen.Player.createRoute(
                            mediaType = type,
                            mediaId = id,
                            seasonNumber = season,
                            episodeNumber = episode,
                            imdbId = imdbId,
                            streamUrl = url,
                            preferredAddonId = preferredAddonId,
                            preferredSourceName = preferredSourceName,
                            startPositionMs = startPositionMs
                        )
                    )
                },
                onNavigateToDetails = { type, id ->
                    navController.navigate(Screen.Details.createRoute(type, id))
                },
                onNavigateToCollection = { catalogId ->
                    navController.navigate(Screen.CollectionDetails.createRoute(catalogId))
                },
                onNavigateToHome = {
                    navigateHome()
                },
                onNavigateToSearch = {
                    navigateTopLevel(Screen.Search.route)
                },
                onNavigateToTv = {
                    navigateTopLevel(Screen.Home.createRoute())
                },
                onNavigateToDiscover = {
                    navigateTopLevel(Screen.Discover.route)
                },
                onNavigateToCameras = {
                    navigateTopLevel(Screen.Cameras.route)
                },
                onNavigateToSettings = {
                    navigateTopLevel(Screen.Settings.route)
                },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = goBack
            )
        }
        
        // Player screen
        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("mediaId") { type = NavType.IntType },
                navArgument("seasonNumber") { 
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("episodeNumber") { 
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("imdbId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("streamUrl") { 
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("preferredAddonId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("preferredSourceName") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("preferredBingeGroup") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("startPositionMs") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { backStackEntry ->
            val mediaTypeStr = backStackEntry.arguments?.getString("mediaType") ?: "movie"
            val mediaId = backStackEntry.arguments?.getInt("mediaId") ?: 0
            val seasonNumber = backStackEntry.arguments?.getInt("seasonNumber")?.takeIf { it >= 0 }
            val episodeNumber = backStackEntry.arguments?.getInt("episodeNumber")?.takeIf { it >= 0 }
            val imdbId = backStackEntry.arguments?.getString("imdbId")?.takeIf { it.isNotBlank() }
            val streamUrl = backStackEntry.arguments?.getString("streamUrl")?.takeIf { it.isNotEmpty() }
            val preferredAddonId = backStackEntry.arguments?.getString("preferredAddonId")?.takeIf { it.isNotBlank() }
            val preferredSourceName = backStackEntry.arguments?.getString("preferredSourceName")?.takeIf { it.isNotBlank() }
            val preferredBingeGroup = backStackEntry.arguments?.getString("preferredBingeGroup")?.takeIf { it.isNotBlank() }
            val startPositionMs = backStackEntry.arguments?.getLong("startPositionMs")?.takeIf { it >= 0L }
            val mediaType = if (mediaTypeStr == "tv") MediaType.TV else MediaType.MOVIE
            
            PlayerScreen(
                mediaType = mediaType,
                mediaId = mediaId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                imdbId = imdbId,
                streamUrl = streamUrl,
                preferredAddonId = preferredAddonId,
                preferredSourceName = preferredSourceName,
                preferredBingeGroup = preferredBingeGroup,
                startPositionMs = startPositionMs,
                onBack = goBack,
                onPlayNext = { nextSeason, nextEpisode, nextPreferredAddonId, nextPreferredSourceName, nextPreferredBingeGroup ->
                    // Navigate to next episode
                    navController.navigate(
                        Screen.Player.createRoute(
                            mediaType = mediaType,
                            mediaId = mediaId,
                            seasonNumber = nextSeason,
                            episodeNumber = nextEpisode,
                            preferredAddonId = nextPreferredAddonId,
                            preferredSourceName = nextPreferredSourceName,
                            preferredBingeGroup = nextPreferredBingeGroup
                        )
                    ) {
                        popUpTo(Screen.Player.route) { inclusive = true }
                    }
                }
            )
        }

        // Cameras screen
        composable(Screen.Cameras.route) {
            CamerasScreen(
                currentProfile = currentProfile,
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToDiscover = { navigateTopLevel(Screen.Discover.route) },
                onNavigateToTv = { navigateTopLevel(Screen.Home.createRoute()) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onNavigateToAllApps = navigateToAllApps,
                onNavigateToMovies = { navigateTopLevel(Screen.PlexLibrary.createRoute(MediaType.MOVIE)) },
                onNavigateToShows = { navigateTopLevel(Screen.PlexLibrary.createRoute(MediaType.TV)) },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = goBack,
            )
        }

        // Smart Home status/control screen
        composable(Screen.SmartHome.route) {
            com.arflix.tv.ui.screens.smarthome.SmartHomeScreen(onBack = goBack)
        }

        // All Apps grid — full alphabetical list of installed apps.
        // Instant transitions so the opaque background covers the launcher
        // from frame 1 instead of fading in transparent over it.
        composable(
            route = Screen.AllApps.route,
            enterTransition = { fadeIn(tween(0)) },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(0)) },
            popExitTransition = { fadeOut(tween(150)) },
        ) {
            AllAppsScreen(onBack = goBack)
        }

        // Camera fullscreen player
        composable(
            route = Screen.CameraPlayer.route,
            arguments = listOf(
                navArgument("streamUrl") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("cameraName") { type = NavType.StringType; nullable = true; defaultValue = null },
            )
        ) { backStackEntry ->
            val streamUrl = backStackEntry.arguments?.getString("streamUrl").orEmpty()
            val cameraName = backStackEntry.arguments?.getString("cameraName").orEmpty()
            CameraPlayerScreen(
                streamUrl = streamUrl,
                cameraName = cameraName,
                onBack = goBack,
            )
        }
    }
}
