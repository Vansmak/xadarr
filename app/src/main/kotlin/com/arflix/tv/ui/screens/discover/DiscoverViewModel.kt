package com.arflix.tv.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogKind
import com.arflix.tv.data.model.CatalogPlacement
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.CatalogRepository
import com.arflix.tv.data.repository.ContinueWatchingHolder
import com.arflix.tv.data.repository.CW_PLACEMENT_KEY
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.WATCHLIST_PLACEMENT_KEY
import com.arflix.tv.data.repository.WATCHLIST_SORT_ORDER_KEY
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val cwHolder: ContinueWatchingHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadDiscoverData() }
    }

    init {
        reload()
        viewModelScope.launch {
            catalogRepository.observeCatalogs().drop(1).collect { _ -> reload() }
        }
        viewModelScope.launch {
            watchlistRepository.watchlistItems.drop(1).collect { _ -> reload() }
        }
        viewModelScope.launch {
            cwHolder.cwCategory.drop(1).collect { _ -> reload() }
        }
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { prefs ->
                    Triple(prefs[WATCHLIST_PLACEMENT_KEY], prefs[WATCHLIST_SORT_ORDER_KEY], prefs[CW_PLACEMENT_KEY])
                }
                .drop(1)
                .distinctUntilChanged()
                .collect { reload() }
        }
    }

    fun refresh() { reload() }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun loadDiscoverData() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            val prefs = context.settingsDataStore.data.first()
            val watchlistPlacement = prefs[WATCHLIST_PLACEMENT_KEY]
                ?.let { runCatching { CatalogPlacement.valueOf(it) }.getOrNull() }
                ?: CatalogPlacement.HOME
            val sortOrder = prefs[WATCHLIST_SORT_ORDER_KEY]?.toIntOrNull() ?: 0
            val isWatchlistHidden = prefs[com.arflix.tv.data.repository.WATCHLIST_HIDDEN_KEY] ?: false
            val cwPlacement = prefs[CW_PLACEMENT_KEY]
                ?.let { runCatching { CatalogPlacement.valueOf(it) }.getOrNull() }
                ?: CatalogPlacement.HOME
            val cwSortOrder = prefs[com.arflix.tv.data.repository.CW_SORT_ORDER_KEY]?.toIntOrNull() ?: 0
            val isCwHidden = prefs[com.arflix.tv.data.repository.CW_HIDDEN_KEY] ?: false

            val allCatalogs = catalogRepository.getCatalogs()
            // Individual COLLECTIONs (Netflix, Action, etc.) are rendered via their
            // COLLECTION_RAIL parent row; exclude them here.
            val discoverCatalogs = allCatalogs
                .filter { !it.isHidden && it.placement == CatalogPlacement.DISCOVER && it.kind != CatalogKind.COLLECTION }

            // Show cached preinstalled categories immediately — no network call needed.
            val cachedById = mediaRepository.cachedHomeCategories.associateBy { it.id }
            val instantCategories = discoverCatalogs.mapNotNull { catalog ->
                when {
                    catalog.kind == CatalogKind.COLLECTION_RAIL ->
                        buildCollectionTileRow(catalog, allCatalogs)
                    catalog.isPreinstalled && catalog.sourceUrl.isNullOrBlank() ->
                        cachedById[catalog.id]?.let { cat ->
                            if (catalog.title.isNotBlank() && catalog.title != cat.title)
                                cat.copy(title = catalog.title) else cat
                        }
                    else -> null
                }
            }.toMutableList()
            injectWatchlist(instantCategories, watchlistPlacement, sortOrder, isWatchlistHidden)
            injectContinueWatching(instantCategories, cwPlacement, cwSortOrder, isCwHidden)
            if (instantCategories.isNotEmpty()) {
                _uiState.value = DiscoverUiState(isLoading = false, categories = instantCategories)
            }

            // Now load everything (preinstalled from fresh network + custom catalogs).
            val allHomeCategories = withContext(Dispatchers.IO) {
                runCatching { mediaRepository.getHomeCategories() }.getOrDefault(emptyList())
            }
            val homeCategoryById = allHomeCategories.associateBy { it.id }

            val categories = withContext(Dispatchers.IO) {
                val jobs = discoverCatalogs.map { catalog ->
                    async {
                        when {
                            catalog.kind == CatalogKind.COLLECTION_RAIL ->
                                buildCollectionTileRow(catalog, allCatalogs)
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

            injectWatchlist(categories, watchlistPlacement, sortOrder, isWatchlistHidden)
            injectContinueWatching(categories, cwPlacement, cwSortOrder, isCwHidden)

            _uiState.value = DiscoverUiState(
                isLoading = false,
                categories = categories,
                error = if (categories.isEmpty()) "No Discover content yet. Assign catalogues to Discover in Settings → Catalogs." else null,
            )
        } catch (e: Exception) {
            _uiState.value = DiscoverUiState(isLoading = false, error = "Failed to load: ${e.message}")
        }
    }

    private fun buildCollectionTileRow(catalog: CatalogConfig, allCatalogs: List<CatalogConfig>): Category? {
        val group = catalog.collectionGroup ?: return null
        val children = allCatalogs.filter {
            it.kind == CatalogKind.COLLECTION && it.collectionGroup == group && !it.isHidden
        }
        val fakeItems = children.mapIndexed { index, config ->
            val fakeId = (config.id.hashCode() and Int.MAX_VALUE).let { if (it == 0) index + 1 else it }
            MediaItem(
                id = fakeId,
                title = config.title,
                overview = "",
                mediaType = MediaType.MOVIE,
                image = config.collectionCoverImageUrl.orEmpty(),
                backdrop = config.collectionFocusGifUrl
                    ?: config.collectionHeroImageUrl
                    ?: config.collectionCoverImageUrl,
                status = "collection:${config.id}",
                collectionGroup = config.collectionGroup,
                collectionTileShape = config.collectionTileShape,
                collectionHideTitle = config.collectionHideTitle
            )
        }
        return if (fakeItems.isEmpty()) null else Category(
            id = "collection_row_${group.name.lowercase()}",
            title = catalog.title,
            items = fakeItems
        )
    }

    private fun injectContinueWatching(
        categories: MutableList<Category>,
        placement: CatalogPlacement,
        sortOrder: Int = 0,
        isHidden: Boolean = false,
    ) {
        categories.removeAll { it.id == "continue_watching" }
        if (!isHidden && placement == CatalogPlacement.DISCOVER) {
            val cwCat = cwHolder.cwCategory.value?.takeIf { it.items.isNotEmpty() } ?: return
            val insertIdx = sortOrder.coerceIn(0, categories.size)
            categories.add(insertIdx, cwCat)
        }
    }

    private fun injectWatchlist(
        categories: MutableList<Category>,
        placement: CatalogPlacement,
        sortOrder: Int = 0,
        isHidden: Boolean = false,
    ) {
        categories.removeAll { it.id == "my_watchlist" }
        if (!isHidden && placement == CatalogPlacement.DISCOVER) {
            val items = watchlistRepository.watchlistItems.value
            if (items.isNotEmpty()) {
                val insertIdx = sortOrder.coerceIn(0, categories.size)
                categories.add(insertIdx, Category(id = "my_watchlist", title = "My Watchlist", items = items))
            }
        }
    }
}
