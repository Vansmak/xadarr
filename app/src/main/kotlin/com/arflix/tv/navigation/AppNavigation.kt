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
import com.arflix.tv.ui.screens.cameras.CameraPlayerScreen
import com.arflix.tv.ui.screens.cameras.CamerasScreen
import com.arflix.tv.ui.screens.home.AllAppsScreen
import com.arflix.tv.ui.screens.details.DetailsScreen
import com.arflix.tv.ui.screens.discover.DiscoverScreen
import com.arflix.tv.ui.screens.home.HomeScreen
import com.arflix.tv.ui.screens.login.LoginScreen
import com.arflix.tv.ui.screens.player.PlayerScreen
import com.arflix.tv.ui.screens.collections.CollectionDetailsScreen
import com.arflix.tv.ui.screens.search.SearchScreen
import com.arflix.tv.ui.screens.settings.SettingsScreen
import com.arflix.tv.ui.screens.tv.live.LiveTvPlayerViewModel
import com.arflix.tv.ui.screens.tv.live.LiveTvScreen
import com.arflix.tv.ui.screens.watchlist.WatchlistScreen
import com.arflix.tv.ui.screens.profile.ProfileSelectionScreen
import com.arflix.tv.util.LocalDeviceType

/**
 * Navigation destinations
 */
sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object Search : Screen("search")
    object Watchlist : Screen("watchlist")
    object CollectionDetails : Screen("collections/{catalogId}") {
        fun createRoute(catalogId: String): String {
            return "collections/${android.net.Uri.encode(catalogId)}"
        }
    }
    object Tv : Screen("tv?channelId={channelId}&streamUrl={streamUrl}") {
        fun createRoute(channelId: String? = null, streamUrl: String? = null): String {
            if (channelId == null) return "tv"
            val enc = java.net.URLEncoder.encode(channelId, "UTF-8")
            val streamEnc = streamUrl?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            return if (streamEnc != null) "tv?channelId=$enc&streamUrl=$streamEnc" else "tv?channelId=$enc"
        }
    }
    object Discover : Screen("discover")
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
        navController.navigate(route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Dismiss mini-player first if active; otherwise allow normal back navigation.
    val goBack: () -> Unit = {
        if (liveTvPlayerViewModel.state.value.isActive) {
            liveTvPlayerViewModel.dismiss()
        } else {
            navController.popBackStack()
        }
    }

    val navigateHome: () -> Unit = {
        // Navigate to Home clearing the entire back stack above it.
        // Uses navigate() instead of popBackStack() because popBackStack can
        // silently fail if Home is not found, and restoreState on other
        // navigateTopLevel calls can bring back stale Details pages.
        navController.navigate(Screen.Home.route) {
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
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }
        
        // Home screen
        composable(Screen.Home.route) {
            HomeScreen(
                preloadedCategories = preloadedCategories,
                preloadedHeroItem = preloadedHeroItem,
                preloadedHeroLogoUrl = preloadedHeroLogoUrl,
                preloadedLogoCache = preloadedLogoCache,
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId, initialSeason, initialEpisode ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId, initialSeason, initialEpisode))
                },
                onNavigateToCollection = { catalogId ->
                    navController.navigate(Screen.CollectionDetails.createRoute(catalogId))
                },
                onNavigateToSearch = {
                    navigateTopLevel(Screen.Search.route)
                },
                onNavigateToDiscover = {
                    navigateTopLevel(Screen.Discover.route)
                },
                onNavigateToTv = { channelId, streamUrl ->
                    navigateTopLevel(Screen.Tv.createRoute(channelId, streamUrl))
                },
                onNavigateToCameras = {
                    navigateTopLevel(Screen.Cameras.route)
                },
                onNavigateToCameraPlayer = { streamUrl, cameraName ->
                    navController.navigate(Screen.CameraPlayer.createRoute(streamUrl, cameraName))
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
                onExitApp = onExitApp,
                onNavigateToAllApps = {
                    navController.navigate(Screen.AllApps.route)
                },
                onInterceptBack = {
                    val active = liveTvPlayerViewModel.state.value.isActive
                    if (active) liveTvPlayerViewModel.dismiss()
                    active
                },
                onDismissMiniPlayer = { liveTvPlayerViewModel.dismiss() },
                onPlayChannelInMiniPlayer = { streamUrl, channelId, channelName, programTitle ->
                    liveTvPlayerViewModel.playFromHome(
                        channelId = channelId,
                        streamUrl = streamUrl,
                        channelName = channelName,
                        programTitle = programTitle,
                    )
                },
            )
        }

        // Search screen
        composable(Screen.Search.route) {
            SearchScreen(
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToDiscover = { navigateTopLevel(Screen.Discover.route) },
                onNavigateToTv = { navigateTopLevel(Screen.Tv.createRoute()) },
                onNavigateToCameras = { navigateTopLevel(Screen.Cameras.route) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onNavigateToCollection = { catalogId ->
                    navController.navigate(Screen.CollectionDetails.createRoute(catalogId))
                },
                onBack = goBack
            )
        }

        // Watchlist route redirects to Discover (watchlist is now a catalogue row, not a tab)
        composable(Screen.Watchlist.route) {
            LaunchedEffect(Unit) {
                navController.navigate(Screen.Discover.route) {
                    popUpTo(Screen.Home.route) { saveState = false }
                    launchSingleTop = true
                    restoreState = false
                }
            }
        }

        // Discover screen
        composable(Screen.Discover.route) {
            DiscoverScreen(
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onNavigateToCollection = { catalogId ->
                    navController.navigate(Screen.CollectionDetails.createRoute(catalogId))
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToTv = { navigateTopLevel(Screen.Tv.createRoute()) },
                onNavigateToCameras = { navigateTopLevel(Screen.Cameras.route) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = goBack
            )
        }

        // TV screen
        composable(
            route = Screen.Tv.route,
            arguments = listOf(
                navArgument("channelId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("streamUrl") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val initialChannelId = backStackEntry.arguments?.getString("channelId")
            val initialStreamUrl = backStackEntry.arguments?.getString("streamUrl")
            LiveTvScreen(
                playerViewModel = liveTvPlayerViewModel,
                currentProfile = currentProfile,
                initialChannelId = initialChannelId,
                initialStreamUrl = initialStreamUrl,
                onFullscreenChanged = onTvFullscreenChanged,
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToDiscover = { navigateTopLevel(Screen.Discover.route) },
                onNavigateToCameras = { navigateTopLevel(Screen.Cameras.route) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = goBack
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
                onNavigateToTv = { navigateTopLevel(Screen.Tv.createRoute()) },
                onNavigateToDiscover = { navigateTopLevel(Screen.Discover.route) },
                onNavigateToCameras = { navigateTopLevel(Screen.Cameras.route) },
                onNavigateToSmartHome = { navController.navigate(Screen.SmartHome.route) },
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
                    navController.navigate(Screen.Home.route) {
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
                onNavigateToTv = { navigateTopLevel(Screen.Tv.createRoute()) },
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
                    navigateTopLevel(Screen.Tv.createRoute())
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
                onNavigateToTv = { navigateTopLevel(Screen.Tv.createRoute()) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
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
