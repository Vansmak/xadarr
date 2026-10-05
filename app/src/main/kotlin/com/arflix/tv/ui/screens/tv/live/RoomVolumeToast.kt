package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.arflix.tv.ui.screens.tv.TvViewModel
import kotlinx.coroutines.delay

/**
 * "Family Room · Volume 24" while the remote's volume keys drive the room's Sonos. Its own file
 * on purpose: LiveTvScreen() must not grow (Shield VerifyError, 2026-10-03). Styled like the
 * guide's own messages.
 */
@Composable
fun RoomVolumeToast(viewModel: TvViewModel) {
    val msg by viewModel.roomVolumeMessage.collectAsStateWithLifecycle()
    LaunchedEffect(msg) {
        if (msg != null) {
            delay(2_500L)
            viewModel.clearRoomVolumeMessage()
        }
    }
    val text = msg ?: return
    Box(
        modifier = Modifier.fillMaxSize().zIndex(301f).padding(bottom = 48.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = text,
            style = LiveType.CellTitle.copy(color = LiveColors.Fg, fontSize = 16.sp),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(LiveColors.PanelRaised)
                .border(1.dp, LiveColors.Divider, RoundedCornerShape(10.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
