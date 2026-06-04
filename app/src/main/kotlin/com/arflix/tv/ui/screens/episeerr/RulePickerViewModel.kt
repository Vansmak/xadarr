package com.arflix.tv.ui.screens.episeerr

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.repository.EpiseerrPendingItem
import com.arflix.tv.data.repository.EpiseerrRepository
import com.arflix.tv.data.repository.EpiseerrRule
import com.arflix.tv.data.repository.SYNC_SERVER_URL_KEY
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RulePickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val episeerrRepository: EpiseerrRepository,
) : ViewModel() {

    private val _rules = MutableStateFlow<List<EpiseerrRule>>(emptyList())
    val rules: StateFlow<List<EpiseerrRule>> = _rules.asStateFlow()

    private val _syncServerUrl = MutableStateFlow("")
    val syncServerUrl: StateFlow<String> = _syncServerUrl.asStateFlow()

    init {
        viewModelScope.launch {
            _rules.value = episeerrRepository.getRules()
            val prefs = context.settingsDataStore.data.first()
            _syncServerUrl.value = prefs[SYNC_SERVER_URL_KEY]?.trimEnd('/').orEmpty()
        }
    }

    fun refreshRules() {
        viewModelScope.launch { _rules.value = episeerrRepository.getRules() }
    }
}
