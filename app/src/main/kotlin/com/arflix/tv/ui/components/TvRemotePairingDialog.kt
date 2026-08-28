package com.arflix.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.arflix.tv.data.repository.tvremote.TvRemotePairingClient
import com.arflix.tv.ui.theme.BackgroundDark
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private sealed interface PairingStage {
    data object Connecting : PairingStage
    data object EnterCode : PairingStage
    data object Finishing : PairingStage
    data object Success : PairingStage
    data class Error(val message: String) : PairingStage
}

/**
 * PIN-entry flow for one-time Android TV Remote Service pairing. Owns the [TvRemotePairingClient]
 * lifecycle: starts it on entry, always closes it on exit regardless of outcome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvRemotePairingDialog(
    host: String,
    onStart: () -> TvRemotePairingClient,
    onFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<PairingStage>(PairingStage.Connecting) }
    var code by remember { mutableStateOf("") }
    val client = remember { onStart() }

    DisposableEffect(Unit) {
        onDispose { client.close() }
    }

    LaunchedEffect(Unit) {
        try {
            client.start(host)
            stage = PairingStage.EnterCode
        } catch (e: Exception) {
            stage = PairingStage.Error(e.message ?: "Couldn't connect")
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BackgroundDark)
                .padding(24.dp)
        ) {
            androidx.tv.material3.Text(text = "Pair with $host", fontSize = 18.sp, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))

            when (val s = stage) {
                is PairingStage.Connecting -> {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Pink)
                        Spacer(modifier = Modifier.width(12.dp))
                        androidx.tv.material3.Text(text = "Connecting…", fontSize = 14.sp, color = TextSecondary)
                    }
                }
                is PairingStage.EnterCode -> {
                    androidx.tv.material3.Text(
                        text = "Enter the 6-digit code now showing on the TV",
                        fontSize = 13.sp,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { if (it.length <= 6) code = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Pink,
                            unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                        ),
                    )
                }
                is PairingStage.Finishing -> {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Pink)
                        Spacer(modifier = Modifier.width(12.dp))
                        androidx.tv.material3.Text(text = "Verifying…", fontSize = 14.sp, color = TextSecondary)
                    }
                }
                is PairingStage.Success -> {
                    androidx.tv.material3.Text(text = "Paired — volume control is ready.", fontSize = 14.sp, color = Pink)
                }
                is PairingStage.Error -> {
                    androidx.tv.material3.Text(text = s.message, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFE57373))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    androidx.tv.material3.Text(text = if (stage is PairingStage.Success) "Done" else "Cancel", color = TextSecondary)
                }
                if (stage is PairingStage.EnterCode) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        enabled = code.length == 6,
                        onClick = {
                            stage = PairingStage.Finishing
                            scope.launch {
                                try {
                                    client.finish(code)
                                    onFinished()
                                    stage = PairingStage.Success
                                } catch (e: Exception) {
                                    stage = PairingStage.Error(e.message ?: "Pairing failed")
                                }
                            }
                        },
                    ) {
                        androidx.tv.material3.Text(text = "Pair", color = Pink)
                    }
                }
            }
        }
    }
}
