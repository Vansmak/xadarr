package com.arflix.tv.ui.screens.home

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.arflix.tv.ui.skin.XadarrFocusableSurface
import com.arflix.tv.ui.skin.XadarrSkin
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// Session-scoped icon cache — icons loaded from PackageManager once per process lifetime.
// Eliminates the grey-box placeholder flash on every visit to the apps row or AllAppsScreen.
internal val appIconCache = ConcurrentHashMap<String, ImageBitmap>()

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppLauncherCard(
    packageName: String,
    label: String,
    isFocused: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    enableSystemFocus: Boolean = false,
) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf(appIconCache[packageName]) }

    LaunchedEffect(packageName) {
        if (appIconCache.containsKey(packageName)) {
            icon = appIconCache[packageName]
            return@LaunchedEffect
        }
        val drawable: Drawable? = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
        val bmp = drawable?.toBitmap()?.asImageBitmap()
        if (bmp != null) appIconCache[packageName] = bmp
        icon = bmp
    }

    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier.width(90.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        XadarrFocusableSurface(
            modifier = Modifier.size(80.dp),
            shape = shape,
            backgroundColor = XadarrSkin.colors.surface,
            outlineColor = XadarrSkin.colors.focusOutline,
            outlineWidth = XadarrSkin.focus.outlineWidth,
            focusedScale = 1.08f,
            pressedScale = 0.97f,
            enableSystemFocus = enableSystemFocus,
            isFocusedOverride = isFocused,
            onClick = onClick,
            onFocusChanged = { focused -> if (focused) onFocused() },
        ) { _ ->
            val bmp = icon
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = label,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label.take(1).uppercase(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isFocused) TextPrimary else TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(88.dp)
        )
    }
}

// Same tile shape/size as AppLauncherCard so a bookmark (a web shortcut with no Android app of
// its own — Sonarr, Radarr, Home Assistant, etc.) sits indistinguishably among real app icons
// in the same grid, instead of reading as a separate "not really an app" chip row.
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BookmarkTileCard(
    iconUrl: String?,
    label: String,
    isFocused: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    enableSystemFocus: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier.width(90.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        XadarrFocusableSurface(
            modifier = Modifier.size(80.dp),
            shape = shape,
            backgroundColor = XadarrSkin.colors.surface,
            outlineColor = XadarrSkin.colors.focusOutline,
            outlineWidth = XadarrSkin.focus.outlineWidth,
            focusedScale = 1.08f,
            pressedScale = 0.97f,
            enableSystemFocus = enableSystemFocus,
            isFocusedOverride = isFocused,
            onClick = onClick,
            onFocusChanged = { focused -> if (focused) onFocused() },
        ) { _ ->
            if (!iconUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = iconUrl,
                    contentDescription = label,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label.take(1).uppercase(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isFocused) TextPrimary else TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(88.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AllAppsCard(
    isFocused: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconTileCard(
        icon = Icons.Default.Apps,
        label = "All Apps",
        isFocused = isFocused,
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier,
    )
}

/**
 * Square icon-over-label tile — shared by the Apps row's "All Apps"/"Settings"
 * entries and Home's "Your library" tile row (the navSectionsByProfile
 * presentation, see HomeViewModel.buildLibraryTilesCategory). One visual
 * pattern for "a tile that launches something," not tied to package icons
 * the way AppLauncherCard is.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IconTileCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isFocused: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier.width(90.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        XadarrFocusableSurface(
            modifier = Modifier.size(80.dp),
            shape = shape,
            backgroundColor = XadarrSkin.colors.surface,
            outlineColor = XadarrSkin.colors.focusOutline,
            outlineWidth = XadarrSkin.focus.outlineWidth,
            focusedScale = 1.08f,
            pressedScale = 0.97f,
            enableSystemFocus = false,
            isFocusedOverride = isFocused,
            onClick = onClick,
            onFocusChanged = { focused -> if (focused) onFocused() },
        ) { _ ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = TextSecondary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isFocused) TextPrimary else TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(88.dp)
        )
    }
}

/**
 * Landscape "Browse" tile — icon top-left, title + subtitle bottom-left, over
 * either real backdrop/poster art (when the destination has representative
 * content — e.g. a real movie for the Movies tile, a live channel snapshot
 * for TV) or a flat per-destination color as a fallback (Discover has no
 * single representative item). Matches the concept.png/Screenshothome.png
 * mockups for Home's nav-shortcut row (Live TV/Movies/Shows/Cameras/...):
 * unlike IconTileCard (icon-over-label, used by Apps), each tile here carries
 * its own dynamic status line (e.g. "Now: <channel>", "12 movies") baked into
 * item.subtitle by HomeViewModel.buildLibraryTilesCategory. `artUrl` is that
 * same category's `sourceItem?.backdrop ?: sourceItem?.image` (Joe,
 * 2026-07-11: "can the tiles be art ninot just a color").
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseTileCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    backgroundColor: androidx.compose.ui.graphics.Color,
    isFocused: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 210.dp,
    artUrl: String? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    XadarrFocusableSurface(
        modifier = modifier
            .width(width)
            .height(width * 9f / 16f),
        shape = shape,
        backgroundColor = backgroundColor,
        outlineColor = XadarrSkin.colors.focusOutline,
        outlineWidth = XadarrSkin.focus.outlineWidth,
        focusedScale = 1.05f,
        pressedScale = 0.97f,
        enableSystemFocus = false,
        isFocusedOverride = isFocused,
        onClick = onClick,
        onFocusChanged = { focused -> if (focused) onFocused() },
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (!artUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = artUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Tinted scrim keeps the section's color identity (Movies=blue,
                // Shows=purple, ...) while still showing the art underneath, and
                // keeps icon/title/subtitle legible over busy backdrops.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    backgroundColor.copy(alpha = 0.55f),
                                    backgroundColor.copy(alpha = 0.85f),
                                )
                            )
                        )
                )
            }
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
