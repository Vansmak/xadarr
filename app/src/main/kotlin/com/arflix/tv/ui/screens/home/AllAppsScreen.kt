package com.arflix.tv.ui.screens.home

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.appBackgroundDark
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val packageName: String, val label: String)

private fun launchApp(context: android.content.Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        ?: if (packageName == "com.android.tv.settings") {
            Intent(android.provider.Settings.ACTION_SETTINGS)
        } else null
    intent?.let { context.startActivity(it) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AllAppsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            (pm.queryIntentActivities(leanback, 0) + pm.queryIntentActivities(launcher, 0))
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != context.packageName }
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark())
            .onKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.Escape) {
                    onBack(); true
                } else false
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "All Apps",
                fontSize = 22.sp,
                color = TextPrimary,
                modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 16.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppLauncherCard(
                        packageName = app.packageName,
                        label = app.label,
                        isFocused = false,
                        onFocused = {},
                        onClick = { launchApp(context, app.packageName) }
                    )
                }
            }
        }
    }
}
