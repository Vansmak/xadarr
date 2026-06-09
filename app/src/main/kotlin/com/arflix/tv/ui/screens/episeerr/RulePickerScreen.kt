package com.arflix.tv.ui.screens.episeerr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.repository.EpiseerrPendingItem
import com.arflix.tv.data.repository.EpiseerrRepository
import com.arflix.tv.data.repository.EpiseerrRule
import com.arflix.tv.ui.theme.XadarrTheme
import kotlinx.coroutines.launch

@Composable
fun RulePickerScreen(
    pendingItem: EpiseerrPendingItem,
    episeerrRepository: EpiseerrRepository,
    syncServerUrl: String,
    episeerrUrl: String = "",
    onDismiss: () -> Unit,
    onRuleAssigned: () -> Unit,
) {
    BackHandler { onDismiss() }

    val themeColors = XadarrTheme.colors
    val accent = themeColors.pink
    val textPrimary = themeColors.textPrimary
    val textSecondary = themeColors.textSecondary
    val bgDark = themeColors.backgroundDark
    val bgElevated = themeColors.backgroundElevated

    var rules by remember { mutableStateOf<List<EpiseerrRule>>(emptyList()) }
    var isLoadingRules by remember { mutableStateOf(true) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var isAssigning by remember { mutableStateOf(false) }
    var assignResult by remember { mutableStateOf<String?>(null) }
    var showWebview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val firstRuleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        rules = episeerrRepository.getRules()
        isLoadingRules = false
    }

    LaunchedEffect(isLoadingRules) {
        if (!isLoadingRules && rules.isNotEmpty()) {
            kotlinx.coroutines.delay(80)
            runCatching { firstRuleFocusRequester.requestFocus() }
        }
    }

    // Keep focused item visible when navigating with D-pad
    LaunchedEffect(focusedIndex) {
        if (rules.isNotEmpty()) {
            listState.animateScrollToItem(focusedIndex.coerceIn(0, rules.size))
        }
    }

    val webviewTargetUrl = when {
        episeerrUrl.isNotBlank() -> {
            val base = episeerrUrl.trimEnd('/')
            val tmdb = pendingItem.tmdbId?.takeIf { it.isNotBlank() }
            if (tmdb != null) "$base/series/$tmdb" else base
        }
        syncServerUrl.isNotBlank() -> "$syncServerUrl/episeerr"
        else -> ""
    }
    if (showWebview && webviewTargetUrl.isNotBlank()) {
        EpiseerrWebviewScreen(url = webviewTargetUrl, onBack = { showWebview = false })
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgDark)
            .onPreviewKeyEvent { evt ->
                if (evt.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (evt.key) {
                    Key.Back, Key.Escape -> { onDismiss(); true }
                    Key.DirectionUp -> {
                        if (focusedIndex > 0) focusedIndex--; true
                    }
                    Key.DirectionDown -> {
                        if (focusedIndex < rules.size) focusedIndex++; true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        when (focusedIndex) {
                            rules.size -> if (webviewTargetUrl.isNotBlank()) showWebview = true
                            else -> {
                                val rule = rules.getOrNull(focusedIndex) ?: return@onPreviewKeyEvent false
                                if (!isAssigning) {
                                    isAssigning = true
                                    scope.launch {
                                        val ok = episeerrRepository.assignRule(
                                            tmdbId = pendingItem.tmdbId ?: return@launch,
                                            ruleName = rule.name
                                        )
                                        assignResult = if (ok) "✓ ${rule.displayName}" else "Assignment failed"
                                        if (ok) {
                                            kotlinx.coroutines.delay(800L)
                                            onRuleAssigned()
                                        }
                                        isAssigning = false
                                    }
                                }
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {

            // ── Header ──────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, null, tint = textSecondary)
                }
                Spacer(Modifier.width(12.dp))
                pendingItem.poster?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 48.dp, height = 72.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Column {
                    Text(pendingItem.title, color = textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Select a rule to start watching", color = textSecondary, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Result or progress ──────────────────────────────────────────
            assignResult?.let {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (it.startsWith("✓")) Color(0xFF064E3B) else Color(0xFF7F1D1D))
                        .padding(12.dp)
                ) {
                    Text(it, color = Color.White, fontSize = 14.sp)
                }
                Spacer(Modifier.height(16.dp))
            }

            if (isLoadingRules) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
            } else {
                // ── Rules list ──────────────────────────────────────────────
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
                    itemsIndexed(rules) { idx, rule ->
                        val isFocused = focusedIndex == idx
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isFocused) Color.White.copy(alpha = 0.12f) else bgElevated)
                                .then(
                                    if (isFocused) Modifier.border(2.dp, accent, RoundedCornerShape(10.dp))
                                    else Modifier
                                )
                                .then(if (idx == 0) Modifier.focusRequester(firstRuleFocusRequester) else Modifier)
                                .focusable()
                                .onFocusChanged { if (it.hasFocus) focusedIndex = idx }
                                .padding(horizontal = 20.dp, vertical = 14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(rule.displayName, color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    if (rule.seriesCount > 0) {
                                        Text("${rule.seriesCount} series", color = textSecondary, fontSize = 12.sp)
                                    }
                                }
                                if (isFocused && isAssigning) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                    item {
                        val isFocused = focusedIndex == rules.size
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isFocused) Color.White.copy(alpha = 0.12f) else bgElevated)
                                .then(
                                    if (isFocused) Modifier.border(2.dp, textSecondary, RoundedCornerShape(10.dp))
                                    else Modifier
                                )
                                .focusable()
                                .onFocusChanged { if (it.hasFocus) focusedIndex = rules.size }
                                .padding(horizontal = 20.dp, vertical = 14.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, null, tint = textSecondary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Advanced (open web UI)", color = textSecondary, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
