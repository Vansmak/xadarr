package com.arflix.tv.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.CatalogKind
import com.arflix.tv.data.model.CatalogPlacement
import com.arflix.tv.data.model.CatalogSourceType
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.repository.CatalogRepository
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.WATCHLIST_PLACEMENT_KEY
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.util.settingsDataStore
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DiscoverUiState(
    val isLoading: Boolean = true,
    val categories: List<Category> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogRepository: CatalogRepository,
    private val mediaRepository: MediaRepository,
    private val watchlistRepository: WatchlistRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            catalogRepository.observeCatalogs().collect { _ ->
                loadDiscoverData()
            }
        }
        viewModelScope.launch {
            watchlistRepository.watchlistItems.collect { _ ->
                loadDiscoverData()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { loadDiscoverData() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun loadDiscoverData() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            val prefs = context.settingsDataStore.data.first()
            val watchlistPlacement = prefs[WATCHLIST_PLACEMENT_KEY]
                ?.let { runCatching { CatalogPlacement.valueOf(it) }.getOrNull() }
                ?: CatalogPlacement.HOME

            val discoverCatalogs = catalogRepository.getCatalogs()
                .filter { !it.isHidden && it.placement == CatalogPlacement.DISCOVER }

            // Load preinstalled TMDB categories in bulk, then match by ID
            val allHomeCategories = withContext(Dispatchers.IO) {
                runCatching { mediaRepository.getHomeCategories() }.getOrDefault(emptyList())
            }
            val homeCategoryById = allHomeCategories.associateBy { it.id }

            val categories = withContext(Dispatchers.IO) {
                val jobs = discoverCatalogs.map { catalog ->
                    async {
                        when {
                            catalog.kind == CatalogKind.COLLECTION_RAIL -> null
                            catalog.isPreinstalled && catalog.sourceUrl.isNullOrBlank() ->
                                homeCategoryById[catalog.id]?.let { cat ->
                                    if (catalog.title.isNotBlank() && catalog.title != cat.title)
                                        cat.copy(title = catalog.title)
                                    else cat
                                }
                            else -> runCatching {
                                mediaRepository.loadCustomCatalog(catalog, maxItems = 40)
                            }.getOrNull()
                        }
                    }
                }
                jobs.mapNotNull { it.await() }
            }.toMutableList()

            // Inject watchlist row if placed on Discover
            if (watchlistPlacement == CatalogPlacement.DISCOVER) {
                val items = watchlistRepository.watchlistItems.value
                if (items.isNotEmpty()) {
                    categories.add(0, Category(
                        id = "my_watchlist",
                        title = "My Watchlist",
                        items = items
                    ))
                }
            }

            _uiState.value = DiscoverUiState(
                isLoading = false,
                categories = categories,
                error = if (categories.isEmpty()) "No Discover content yet. Assign catalogues to Discover in Settings → Catalogs." else null,
            )
        } catch (e: Exception) {
            _uiState.value = DiscoverUiState(isLoading = false, error = "Failed to load: ${e.message}")
        }
    }
}
