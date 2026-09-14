package com.arflix.tv.ui.screens.episeerr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpiseerrWebviewScreen(
    url: String,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    // Mobile already closes this via the system back gesture/button (BackHandler above) and
    // Xadarr's own bottom nav is one tap away, so the on-screen header bar is just wasted
    // vertical space there — kept only for TV, which has no swipe-back gesture.
    val isTouchDevice = com.arflix.tv.util.LocalDeviceType.current.isTouchDevice()

    // Full-screen on mobile — a bookmark like Home Assistant or Discord wants every pixel it can
    // get, same reasoning that already hides Xadarr's own bottom bar for these (Joe: "some sites
    // I'd rather let use all the real estate"). Same pattern PlayerScreen.kt already uses for
    // mobile video playback; restored on exit. TV is already fullscreen, so no-op there.
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(Unit) {
        val window = activity?.window
        if (window != null && isTouchDevice) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null && isTouchDevice) {
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111827))
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            if (!isTouchDevice) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1F2937))
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Some sites (Discord's own web app is the one that surfaced this) detect
                        // a mobile user agent and serve a "download our app" marketing page
                        // instead of the real site, regardless of whether the app-escape trick
                        // above even fires. Presenting as a desktop browser is the standard way
                        // around that — this in-app browser is precisely the case where the real
                        // desktop-style site is what's wanted, not a mobile-optimized one.
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                        // A plain WebViewClient has no shouldOverrideUrlLoading override, so a
                        // page that navigates to a non-http(s) URI (an intent:// redirect, or a
                        // custom app scheme like discord://) falls through to the system's own
                        // Intent resolution — which is the entire purpose of intent://, invented
                        // specifically so a mobile web page can escape any WebView into its native
                        // app. Discord's own web app does exactly this, and there was nothing here
                        // to stop it: tapping a Discord bookmark left this in-app browser
                        // immediately for the real Discord app instead of showing the page.
                        // Only allow the WebView to keep navigating for schemes it can actually
                        // render itself; swallow everything else so the page just stays put.
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val scheme = request.url.scheme?.lowercase()
                                return scheme != "http" && scheme != "https"
                            }
                        }
                        loadUrl(url)
                    }
                }
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
