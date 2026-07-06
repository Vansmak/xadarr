package com.arflix.tv.ui.screens.smarthome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.arflix.tv.data.repository.HomeAssistantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

data class SmartHomeUiState(
    val allEntities: List<HomeAssistantRepository.HaEntity> = emptyList(),
    val exposedIds: Set<String> = emptySet(), // empty = show all (default, backward-compatible)
    val collapsedDomains: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val pendingEntityId: String? = null,
) {
    // What actually renders on the main screen — the curated subset (or everything,
    // if the user hasn't curated yet).
    val visibleEntities: List<HomeAssistantRepository.HaEntity>
        get() = if (exposedIds.isEmpty()) allEntities else allEntities.filter { it.entityId in exposedIds }

    fun isExposed(entityId: String): Boolean = exposedIds.isEmpty() || entityId in exposedIds
}

@HiltViewModel
class SmartHomeViewModel @Inject constructor(
    private val haRepository: HomeAssistantRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmartHomeUiState())
    val uiState: StateFlow<SmartHomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val exposedIds = haRepository.getExposedEntityIds()
            _uiState.value = when (val result = haRepository.getAllEntities()) {
                is HomeAssistantRepository.HaResult.NotConfigured ->
                    _uiState.value.copy(isLoading = false, error = "Not configured. Set Home Assistant URL and Token in settings.")
                is HomeAssistantRepository.HaResult.Error ->
                    _uiState.value.copy(isLoading = false, error = result.message)
                is HomeAssistantRepository.HaResult.Success ->
                    if (result.entities.isEmpty()) {
                        _uiState.value.copy(isLoading = false, error = "Connected, but no lights, fans, AC, or window sensors found.")
                    } else {
                        _uiState.value.copy(allEntities = result.entities, exposedIds = exposedIds, isLoading = false, error = null)
                    }
            }
        }
    }

    fun toggleEntity(entity: HomeAssistantRepository.HaEntity) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pendingEntityId = entity.entityId)
            val turningOn = entity.state != "on"
            val ok = when (entity.domain) {
                "light" -> haRepository.toggleLight(entity.entityId, turnOn = turningOn)
                "fan" -> haRepository.setFan(entity.entityId, turnOn = turningOn)
                else -> false
            }
            if (ok) {
                applyOptimisticUpdate(entity.entityId) { it.copy(state = if (turningOn) "on" else "off") }
            }
            _uiState.value = _uiState.value.copy(pendingEntityId = null)
            if (ok) settleAfterCommand()
        }
    }

    // Fan speed buckets: off / low / medium / high, mapped to HA's 0-100 percentage.
    private val fanSpeedSteps = listOf(0, 33, 66, 100)

    fun adjustFanSpeed(entity: HomeAssistantRepository.HaEntity, delta: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pendingEntityId = entity.entityId)
            val current = entity.fanPercentage ?: if (entity.state == "on") 100 else 0
            val currentIdx = fanSpeedSteps.indexOfFirst { it >= current }.let { if (it < 0) fanSpeedSteps.size - 1 else it }
            val nextPct = fanSpeedSteps[(currentIdx + delta).coerceIn(0, fanSpeedSteps.size - 1)]
            val ok = if (nextPct == 0) {
                haRepository.setFan(entity.entityId, turnOn = false)
            } else {
                haRepository.setFan(entity.entityId, turnOn = true, percentage = nextPct)
            }
            if (ok) {
                applyOptimisticUpdate(entity.entityId) {
                    it.copy(state = if (nextPct == 0) "off" else "on", fanPercentage = nextPct)
                }
            }
            _uiState.value = _uiState.value.copy(pendingEntityId = null)
            if (ok) settleAfterCommand()
        }
    }

    fun cycleClimateMode(entity: HomeAssistantRepository.HaEntity, forward: Boolean) {
        val modes = entity.hvacModes
        if (modes.isEmpty()) return
        val currentIdx = modes.indexOf(entity.state).let { if (it < 0) 0 else it }
        val nextMode = modes[(currentIdx + (if (forward) 1 else -1) + modes.size) % modes.size]
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pendingEntityId = entity.entityId)
            val ok = haRepository.setClimateMode(entity.entityId, nextMode)
            if (ok) {
                applyOptimisticUpdate(entity.entityId) { it.copy(state = nextMode) }
            }
            _uiState.value = _uiState.value.copy(pendingEntityId = null)
            if (ok) settleAfterCommand()
        }
    }

    private fun applyOptimisticUpdate(
        entityId: String,
        transform: (HomeAssistantRepository.HaEntity) -> HomeAssistantRepository.HaEntity,
    ) {
        _uiState.value = _uiState.value.copy(
            allEntities = _uiState.value.allEntities.map { if (it.entityId == entityId) transform(it) else it }
        )
    }

    // HA's REST response can race the entity's actual state (e.g. a zigbee bulb reports
    // back after the HTTP call returns), so an immediate refresh() can read stale data and
    // appear to "undo" the toggle. The optimistic update above shows the true intent right
    // away; this delayed refresh reconciles with HA's actual state once it has caught up.
    private suspend fun settleAfterCommand() {
        delay(1000)
        refresh()
    }

    fun toggleDomainCollapsed(domain: String) {
        val current = _uiState.value.collapsedDomains
        _uiState.value = _uiState.value.copy(
            collapsedDomains = if (domain in current) current - domain else current + domain
        )
    }

    fun toggleExposed(entityId: String) {
        val state = _uiState.value
        val newSet = if (state.exposedIds.isEmpty()) {
            // Was implicitly "show all" — switch to explicit mode, everything
            // stays checked except the one just unchecked.
            state.allEntities.map { it.entityId }.toMutableSet().apply { remove(entityId) }
        } else {
            state.exposedIds.toMutableSet().apply {
                if (entityId in this) remove(entityId) else add(entityId)
            }
        }
        _uiState.value = state.copy(exposedIds = newSet)
        viewModelScope.launch { haRepository.setExposedEntityIds(newSet) }
    }
}

// ── Domain grouping ──────────────────────────────────────────────────────────

private fun domainGroupLabel(domain: String): String = when (domain) {
    "light" -> "Lights"
    "fan" -> "Fans"
    "climate" -> "AC"
    "binary_sensor" -> "Windows"
    else -> domain
}

private fun domainIcon(domain: String): ImageVector = when (domain) {
    "light" -> Icons.Default.Lightbulb
    "fan" -> Icons.Default.Air
    "climate" -> Icons.Default.Thermostat
    "binary_sensor" -> Icons.Default.Sensors
    else -> Icons.Default.Sensors
}

// entities with a simple on/off toggle; climate uses mode cycling instead, window sensors are read-only
private fun isActionable(domain: String): Boolean = domain in setOf("light", "fan")

// entities adjustable via D-pad left/right (fan speed, climate mode)
private fun supportsDirectionalAdjust(domain: String): Boolean = domain in setOf("fan", "climate")

private sealed class SmartHomeRow {
    data class DomainHeader(val domain: String, val count: Int, val collapsed: Boolean) : SmartHomeRow()
    data class Entity(val entity: HomeAssistantRepository.HaEntity) : SmartHomeRow()
}

private fun domainSortOrder(domain: String): Int = when (domain) {
    "light" -> 0
    "fan" -> 1
    "climate" -> 2
    "binary_sensor" -> 3
    else -> 9
}

private fun buildRows(
    entities: List<HomeAssistantRepository.HaEntity>,
    collapsedDomains: Set<String>,
): List<SmartHomeRow> {
    val rows = mutableListOf<SmartHomeRow>()
    entities.groupBy { it.domain }.toSortedMap(compareBy { domainSortOrder(it) }).forEach { (domain, items) ->
        val collapsed = domain in collapsedDomains
        rows.add(SmartHomeRow.DomainHeader(domain, items.size, collapsed))
        if (!collapsed) items.forEach { rows.add(SmartHomeRow.Entity(it)) }
    }
    return rows
}

// ── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun SmartHomeScreen(
    viewModel: SmartHomeViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    BackHandler { onBack() }

    val uiState by viewModel.uiState.collectAsState()
    val visibleEntities = uiState.visibleEntities
    val rows = remember(visibleEntities, uiState.collapsedDomains) { buildRows(visibleEntities, uiState.collapsedDomains) }
    val navigableIndices = remember(rows) {
        rows.indices.filter { rows[it] is SmartHomeRow.DomainHeader || rows[it] is SmartHomeRow.Entity }
    }

    // -1 is a sentinel meaning "the header Edit button is focused" — the header
    // icons aren't part of the row list, so they need an explicit spot in the
    // up/down sequence or the remote can never reach them.
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var showPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rootFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { rootFocusRequester.requestFocus() } }
    LaunchedEffect(focusedIndex) {
        if (focusedIndex >= 0 && rows.isNotEmpty()) listState.animateScrollToItem(focusedIndex.coerceIn(0, rows.size - 1))
    }

    fun moveFocus(delta: Int) {
        if (focusedIndex == -1) {
            if (delta > 0) navigableIndices.firstOrNull()?.let { focusedIndex = it }
            return
        }
        if (navigableIndices.isEmpty()) return
        val currentPos = navigableIndices.indexOf(focusedIndex).let { if (it < 0) 0 else it }
        val nextPos = currentPos + delta
        focusedIndex = if (nextPos < 0) -1 else navigableIndices[nextPos.coerceAtMost(navigableIndices.size - 1)]
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111827))
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { evt ->
                if (evt.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (evt.key) {
                    Key.Back, Key.Escape -> { onBack(); true }
                    Key.DirectionUp -> { moveFocus(-1); true }
                    Key.DirectionDown -> { moveFocus(1); true }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (focusedIndex == -1) {
                            showPicker = true
                        } else {
                            when (val row = rows.getOrNull(focusedIndex)) {
                                is SmartHomeRow.DomainHeader -> viewModel.toggleDomainCollapsed(row.domain)
                                is SmartHomeRow.Entity -> when (row.entity.domain) {
                                    "light", "fan" -> viewModel.toggleEntity(row.entity)
                                    "climate" -> viewModel.cycleClimateMode(row.entity, forward = true)
                                    else -> Unit
                                }
                                null -> Unit
                            }
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        (rows.getOrNull(focusedIndex) as? SmartHomeRow.Entity)?.let { row ->
                            when (row.entity.domain) {
                                "fan" -> viewModel.adjustFanSpeed(row.entity, delta = 1)
                                "climate" -> viewModel.cycleClimateMode(row.entity, forward = true)
                                else -> Unit
                            }
                        }
                        true
                    }
                    Key.DirectionLeft -> {
                        (rows.getOrNull(focusedIndex) as? SmartHomeRow.Entity)?.let { row ->
                            when (row.entity.domain) {
                                "fan" -> viewModel.adjustFanSpeed(row.entity, delta = -1)
                                "climate" -> viewModel.cycleClimateMode(row.entity, forward = false)
                                else -> Unit
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text("Smart Home", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (focusedIndex == -1) Modifier.border(2.dp, Color(0xFF4F7FB0), RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .background(if (focusedIndex == -1) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                ) {
                    IconButton(onClick = { showPicker = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Choose entities", tint = Color(0xFF9CA3AF))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            when {
                uiState.isLoading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF4F7FB0))
                }
                uiState.error != null -> Text(uiState.error!!, color = Color(0xFF9CA3AF), fontSize = 14.sp)
                visibleEntities.isEmpty() -> Text(
                    "No entities selected. Tap the edit icon above to choose which ones show here.",
                    color = Color(0xFF9CA3AF),
                    fontSize = 14.sp,
                )
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    rows.forEachIndexed { idx, row ->
                        item(key = idx) {
                            when (row) {
                                is SmartHomeRow.DomainHeader -> {
                                    SmartHomeDomainHeaderRow(
                                        domain = row.domain,
                                        count = row.count,
                                        collapsed = row.collapsed,
                                        isFocused = focusedIndex == idx,
                                        onFocusChanged = { focusedIndex = idx },
                                    )
                                }
                                is SmartHomeRow.Entity -> {
                                    SmartHomeEntityRow(
                                        entity = row.entity,
                                        isFocused = focusedIndex == idx,
                                        isPending = uiState.pendingEntityId == row.entity.entityId,
                                        onFocusChanged = { focusedIndex = idx },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        EntityPickerOverlay(
            entities = uiState.allEntities,
            isExposed = { uiState.isExposed(it) },
            onToggle = { viewModel.toggleExposed(it) },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun SmartHomeDomainHeaderRow(
    domain: String,
    count: Int,
    collapsed: Boolean,
    isFocused: Boolean,
    onFocusChanged: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (isFocused) Modifier.border(2.dp, Color(0xFF4F7FB0), RoundedCornerShape(8.dp)) else Modifier)
            .background(if (isFocused) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .focusable()
            .onFocusChanged { if (it.hasFocus) onFocusChanged() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(
            if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            contentDescription = null,
            tint = Color(0xFF9CA3AF),
            modifier = Modifier.height(16.dp).width(16.dp),
        )
        Text(
            text = "${domainGroupLabel(domain).uppercase()} ($count)",
            color = Color(0xFF9CA3AF),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SmartHomeEntityRow(
    entity: HomeAssistantRepository.HaEntity,
    isFocused: Boolean,
    isPending: Boolean,
    onFocusChanged: () -> Unit,
) {
    val actionable = isActionable(entity.domain)
    val adjustable = supportsDirectionalAdjust(entity.domain)
    val statusText = when (entity.domain) {
        "binary_sensor" -> if (entity.state == "on") "OPEN" else "CLOSED"
        "climate" -> buildString {
            append(entity.state.replaceFirstChar { it.uppercase() })
            entity.currentTemperature?.let { append(" · ${it.toInt()}° now") }
            entity.targetTemperature?.let { append(" · target ${it.toInt()}°") }
        }
        "fan" -> if (entity.state == "on") {
            val pct = entity.fanPercentage
            when {
                pct == null -> "On"
                pct <= 33 -> "Low"
                pct <= 66 -> "Medium"
                else -> "High"
            }
        } else "Off"
        else -> if (entity.state == "on") "On" else "Off"
    }
    val statusColor = when {
        entity.domain == "binary_sensor" && entity.state == "on" -> Color(0xFFDC2626)
        entity.domain == "binary_sensor" -> Color(0xFF16A34A)
        entity.state == "on" -> Color(0xFF4F7FB0)
        else -> Color(0xFF9CA3AF)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.12f) else Color(0xFF1F2937))
            .then(
                if (isFocused) Modifier.border(2.dp, Color(0xFF4F7FB0), RoundedCornerShape(10.dp))
                else Modifier
            )
            .focusable()
            .onFocusChanged { if (it.hasFocus) onFocusChanged() }
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(domainIcon(entity.domain), contentDescription = null, tint = Color(0xFF9CA3AF))
            Text(entity.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (isPending) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), color = Color(0xFF4F7FB0), strokeWidth = 2.dp)
            } else {
                if (isFocused && adjustable) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color(0xFF9CA3AF), modifier = Modifier.height(16.dp).width(16.dp))
                }
                Text(statusText, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (isFocused && adjustable) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF9CA3AF), modifier = Modifier.height(16.dp).width(16.dp))
                }
                if (actionable) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (entity.state == "on") Icons.Default.ToggleOn else Icons.Default.ToggleOff,
                        contentDescription = null,
                        tint = statusColor,
                    )
                }
            }
        }
    }
}

// ── Entity picker overlay ────────────────────────────────────────────────────
// Curates which entities show up on the main screen. Checked = exposed.

private data class PickerRow(val domain: String? = null, val entity: HomeAssistantRepository.HaEntity? = null)

@Composable
private fun EntityPickerOverlay(
    entities: List<HomeAssistantRepository.HaEntity>,
    isExposed: (String) -> Boolean,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val rows = remember(entities) {
        val out = mutableListOf<PickerRow>()
        entities.groupBy { it.domain }.toSortedMap(compareBy { domainSortOrder(it) }).forEach { (domain, items) ->
            out.add(PickerRow(domain = domain))
            items.forEach { out.add(PickerRow(entity = it)) }
        }
        out
    }
    var focusedIndex by remember { mutableIntStateOf(rows.indexOfFirst { it.entity != null }.coerceAtLeast(0)) }
    val listState = rememberLazyListState()
    val rootFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { rootFocusRequester.requestFocus() } }
    LaunchedEffect(focusedIndex) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(focusedIndex.coerceIn(0, rows.size - 1))
    }

    fun moveFocus(delta: Int) {
        var next = focusedIndex + delta
        while (next in rows.indices && rows[next].entity == null) next += delta
        if (next in rows.indices) focusedIndex = next
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { evt ->
                if (evt.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (evt.key) {
                    Key.Back, Key.Escape -> { onDismiss(); true }
                    Key.DirectionUp -> { moveFocus(-1); true }
                    Key.DirectionDown -> { moveFocus(1); true }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        rows.getOrNull(focusedIndex)?.entity?.let { onToggle(it.entityId) }
                        true
                    }
                    else -> false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Done", tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text("Choose entities", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                rows.forEachIndexed { idx, row ->
                    item(key = idx) {
                        if (row.domain != null) {
                            Text(
                                text = domainGroupLabel(row.domain).uppercase(),
                                color = Color(0xFF9CA3AF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        } else if (row.entity != null) {
                            val checked = isExposed(row.entity.entityId)
                            val isFocused = focusedIndex == idx
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isFocused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                                    .then(if (isFocused) Modifier.border(2.dp, Color(0xFF4F7FB0), RoundedCornerShape(10.dp)) else Modifier)
                                    .focusable()
                                    .onFocusChanged { if (it.hasFocus) focusedIndex = idx }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = if (checked) Color(0xFF4F7FB0) else Color(0xFF9CA3AF),
                                )
                                Text(row.entity.name, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
