package com.arflix.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary

/**
 * Generic single-surface confirm/cancel dialog for TV — one focusable Column
 * catching D-pad events (Left/Right toggles Confirm/Cancel, Enter activates),
 * not two independently-focusable buttons. Extracted from the settings
 * UiModeWarningDialog pattern so other destructive/confirm flows (e.g. delete
 * from library) don't need to hand-roll this D-pad handling again.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    cancelLabel: String = "Cancel",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var focusedIndex by remember { mutableIntStateOf(0) } // 0 = Confirm, 1 = Cancel
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        ConfirmDialogScrim(onDismiss = onDismiss) {
            Column(
                modifier = Modifier
                    .width(400.dp)
                    .background(BackgroundElevated, RoundedCornerShape(16.dp))
                    .padding(28.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.Back, Key.Escape -> { onDismiss(); true }
                                Key.DirectionLeft -> { if (focusedIndex > 0) focusedIndex--; true }
                                Key.DirectionRight -> { if (focusedIndex < 1) focusedIndex++; true }
                                Key.Enter, Key.DirectionCenter -> {
                                    if (focusedIndex == 0) onConfirm() else onDismiss()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
            ) {
                Text(text = title, style = ArflixTypography.sectionTitle, color = TextPrimary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = message, style = ArflixTypography.body, color = TextSecondary)
                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val isConfirmFocused = focusedIndex == 0
                    val confirmColor = if (isDestructive) Color(0xFFDC2626) else Color(0xFF22C55E)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isConfirmFocused) confirmColor else confirmColor.copy(alpha = 0.6f))
                            .clickable { onConfirm() }
                            .border(
                                width = if (isConfirmFocused) 2.dp else 0.dp,
                                color = if (isConfirmFocused) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = confirmLabel, style = ArflixTypography.button, color = Color.White)
                    }

                    val isCancelFocused = focusedIndex == 1
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCancelFocused) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f))
                            .clickable { onDismiss() }
                            .border(
                                width = if (isCancelFocused) 2.dp else 0.dp,
                                color = if (isCancelFocused) Pink else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = cancelLabel,
                            style = ArflixTypography.button,
                            color = if (isCancelFocused) TextPrimary else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialogScrim(
    onDismiss: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val contentInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.clickable(interactionSource = contentInteraction, indication = null, onClick = {}),
            content = content
        )
    }
}
