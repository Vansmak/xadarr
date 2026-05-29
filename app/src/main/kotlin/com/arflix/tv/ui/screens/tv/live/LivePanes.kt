package com.arflix.tv.ui.screens.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LoadingPane(message: String?, percent: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = LiveColors.Accent)
        if (!message.isNullOrBlank()) {
            Box(Modifier.padding(top = 20.dp)) {
                Text(message, style = LiveType.CellTitle.copy(color = LiveColors.FgDim))
            }
        }
        if (percent > 0) {
            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.padding(top = 12.dp).width(260.dp),
                color = LiveColors.Accent,
                trackColor = LiveColors.Divider,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EmptyStatePane(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = LiveType.ProgramTitle.copy(color = LiveColors.FgDim))
        Box(
            modifier = Modifier
                .padding(top = 18.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LiveColors.Accent)
                .clickable { onAction() }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(actionLabel, style = LiveType.CatLabel.copy(color = LiveColors.Bg))
        }
    }
}
