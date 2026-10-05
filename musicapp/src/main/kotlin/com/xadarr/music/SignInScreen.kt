package com.xadarr.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arflix.tv.music.MA_DEFAULT_URL
import com.arflix.tv.music.MusicAssistantRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign in by borrowing Xadarr's synced Music Assistant login (one press on a TV), or with the
 * MA username/password. Plain text fields, so the remote's D-pad and a touch keyboard both work.
 */
@Composable
fun SignInScreen(repo: MusicAssistantRepository) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var syncUrl by remember { mutableStateOf(DEFAULT_SYNC_URL) }
    var url by remember { mutableStateOf(MA_DEFAULT_URL) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val xadarrFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { xadarrFocus.requestFocus() } }

    fun signInWithXadarr() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = importMusicAssistantFromXadarr(context, syncUrl)
            if (error == null) repo.reconnectNow()
            busy = false
        }
    }

    fun signIn() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            error = repo.login(url.trim(), username.trim(), password)
            busy = false
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.onSurfaceVariant,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.onSurfaceVariant,
        cursorColor = colors.primary,
    )
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(24.dp)
                .clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Xadarr Music", color = colors.onSurface, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text("Use the Music Assistant login Xadarr already has", color = colors.onSurfaceVariant, fontSize = 15.sp)
            OutlinedTextField(
                value = syncUrl, onValueChange = { syncUrl = it }, singleLine = true,
                label = { androidx.compose.material3.Text("Xadarr sync server") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { signInWithXadarr() }),
                modifier = Modifier.fillMaxWidth(), colors = fieldColors,
            )
            Button(
                onClick = { signInWithXadarr() },
                enabled = !busy && syncUrl.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(xadarrFocus),
            ) { androidx.compose.material3.Text(if (busy) "Signing in…" else "Sign in with Xadarr") }
            Spacer(Modifier.height(8.dp))
            Text("Or sign in to Music Assistant directly", color = colors.onSurfaceVariant, fontSize = 15.sp)
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { androidx.compose.material3.Text("Server") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(), colors = fieldColors,
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it }, singleLine = true,
                label = { androidx.compose.material3.Text("Username") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(), colors = fieldColors,
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { androidx.compose.material3.Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { signIn() }),
                modifier = Modifier.fillMaxWidth(), colors = fieldColors,
            )
            error?.let { Text(it, color = colors.error, fontSize = 14.sp) }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { signIn() },
                enabled = !busy && url.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                modifier = Modifier.fillMaxWidth(),
            ) { androidx.compose.material3.Text(if (busy) "Signing in…" else "Sign in") }
        }
    }
}
