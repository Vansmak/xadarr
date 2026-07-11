package com.arflix.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.model.Profile

/**
 * Non-Home screens' top chrome under the NavRail model: no persistent nav
 * chips (that's the rail's job now, opened via LEFT at the leftmost element —
 * see NavRail/NavRailTrigger) — just a status readout (network/clock/profile)
 * and a visual hint that Back leaves the screen. Purely decorative: nothing
 * here is focusable, so it never competes with the screen's own D-pad focus.
 * The actual back action stays wired through each screen's existing
 * BackHandler/Key.Back handling.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MinimalTopChrome(
    profile: Profile? = null,
    clockFormat: String = "24h",
    modifier: Modifier = Modifier,
) {
    val currentTime = rememberTopBarTime(clockFormat)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AppTopBarContentTopInset)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.72f),
                        Color.Black.copy(alpha = 0.36f),
                        Color.Transparent
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppTopBarHeight)
                .padding(start = AppTopBarHorizontalPadding, end = AppTopBarHorizontalPadding, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                text = "Back",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.45f),
            )

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (profile != null) {
                        TopBarProfileAvatar(profile = profile, isFocused = false)
                    }
                    TopBarNetworkStatus()
                    Text(
                        text = currentTime,
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}
