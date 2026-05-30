package com.arflix.tv.ui.screens.home

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.tv.material3.Text
import com.arflix.tv.ui.skin.ArvioFocusableSurface
import com.arflix.tv.ui.skin.ArvioSkin
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppLauncherCard(
    packageName: String,
    label: String,
    isFocused: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(packageName) {
        val drawable: Drawable? = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
        icon = drawable?.toBitmap()?.asImageBitmap()
    }

    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier.width(90.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArvioFocusableSurface(
            modifier = Modifier.size(80.dp),
            shape = shape,
            backgroundColor = ArvioSkin.colors.surface,
            outlineColor = ArvioSkin.colors.focusOutline,
            outlineWidth = ArvioSkin.focus.outlineWidth,
            focusedScale = 1.08f,
            pressedScale = 0.97f,
            enableSystemFocus = false,
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
