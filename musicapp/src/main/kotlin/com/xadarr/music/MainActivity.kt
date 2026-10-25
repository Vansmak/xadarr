package com.xadarr.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import com.arflix.tv.music.MusicAssistantRepository
import com.arflix.tv.ui.glass.GlassMode
import com.arflix.tv.ui.glass.GlassNight
import com.arflix.tv.ui.glass.LocalGlass
import com.arflix.tv.ui.glass.glassColorScheme
import com.arflix.tv.ui.glass.rememberGlassPalette
import com.arflix.tv.music.MusicScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Xadarr Music: sign in once, then it's Xadarr's Music screen full-screen. A rejected token
 * (MA user removed, token revoked) drops back to sign-in rather than an endless "offline".
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var repo: MusicAssistantRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Joe, 2026-10-12: the phone's status bar sat over the top row -- hide it while the app
        // is open; a swipe down from the top shows it briefly.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setContent {
            XadarrMusicTheme {
                val configured by repo.isConfigured.collectAsState(initial = null)
                val connection by repo.state.collectAsState()
                // Android 15 draws edge to edge for targetSdk 35: keep the navy behind the bars but
                // the screen inside them. The status bar is hidden (below), so this is mostly the
                // nav bar and the keyboard. Zero on TVs.
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
                ) {
                    when (configured) {
                        null -> Unit
                        false -> SignInScreen(repo)
                        true -> if (connection == MusicAssistantRepository.ConnectionState.AUTH_FAILED) {
                            SignInScreen(repo)
                        } else {
                            MusicScreen(onBack = { finish() })
                        }
                    }
                }
            }
        }
    }
}

// Xadarr's glass theme, like the home launcher (Joe, 2026-10-25): the Music screen follows day
// and night (Auto: dark from local sunset to sunrise); sign-in stays on the night colours.
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun XadarrMusicTheme(content: @Composable () -> Unit) {
    val glass = rememberGlassPalette(GlassMode.AUTO)
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(colorScheme = glassColorScheme(GlassNight), content = content)
    }
}
