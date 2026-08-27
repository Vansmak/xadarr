package com.arflix.tv.ui.screens.episeerr

import android.annotation.SuppressLint
import android.view.ViewGroup
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

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
                        webViewClient = WebViewClient()
                        loadUrl(url)
                    }
                }
            )
        }
    }
}
