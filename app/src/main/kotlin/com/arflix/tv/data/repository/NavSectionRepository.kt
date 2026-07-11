package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.data.model.NavSectionConfig
import com.arflix.tv.data.model.NavSectionKind
import com.arflix.tv.util.settingsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavSectionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
) {
    private val gson = Gson()
    private fun navSectionsKey(profileId: String) = stringPreferencesKey("profile_${profileId}_nav_sections_v1")
    private val listType = TypeToken.getParameterized(List::class.java, NavSectionConfig::class.java).type

    // CUSTOM is excluded from fixedKinds: it has no single default instance
    // (customId/target are always explicit) — the three CUSTOM defaults below
    // (Movies/Shows/Watchlist) are seeded directly, not derived from this list.
    private val fixedKinds = NavSectionKind.entries.filter { it != NavSectionKind.CUSTOM }

    // Discover has no tile/rail slot in the current design — kept as a real,
    // resolvable kind (route still works) but hidden by default rather than
    // removed, so it's a one-toggle re-enable rather than lost functionality.
    private fun defaultVisibility(kind: NavSectionKind): Boolean = kind != NavSectionKind.DISCOVER

    // The three CUSTOM defaults every profile should have — seeded directly
    // since CUSTOM has no single default instance the way fixedKinds does.
    // Shared with the upgrade-merge path in readSectionsFromPrefs() below so
    // profiles that predate these (Movies/Shows/Watchlist) get them retrofitted
    // instead of silently missing three of the six "Your library" tiles.
    private fun defaultCustomEntries(startOrder: Int): List<NavSectionConfig> = listOf(
        NavSectionConfig(kind = NavSectionKind.CUSTOM, customId = "movies", label = "Movies", target = "seeall:trending_movies", order = startOrder),
        NavSectionConfig(kind = NavSectionKind.CUSTOM, customId = "shows", label = "Shows", target = "seeall:trending_tv", order = startOrder + 1),
        NavSectionConfig(kind = NavSectionKind.CUSTOM, customId = "watchlist", label = "Watchlist", target = "watchlist", order = startOrder + 2),
    )

    // Matches the Home "Your library" tiles / NavRail mockup exactly — used
    // whenever a profile has no stored config yet (fresh install or a device
    // from before this existed).
    fun defaultSections(): List<NavSectionConfig> {
        val customs = defaultCustomEntries(startOrder = 2)
        return listOf(
            NavSectionConfig(kind = NavSectionKind.HOME, order = 0),
            NavSectionConfig(kind = NavSectionKind.TV, label = "Live TV", order = 1),
            customs[0],
            customs[1],
            NavSectionConfig(kind = NavSectionKind.CAMERAS, order = 4),
            NavSectionConfig(kind = NavSectionKind.SEARCH, order = 5),
            customs[2],
            NavSectionConfig(kind = NavSectionKind.SETTINGS, order = 7),
            NavSectionConfig(kind = NavSectionKind.DISCOVER, visible = defaultVisibility(NavSectionKind.DISCOVER), order = 8),
        )
    }

    private fun readSectionsFromPrefs(profileId: String, prefs: Preferences): List<NavSectionConfig> {
        val raw = prefs[navSectionsKey(profileId)]
        if (raw.isNullOrBlank()) return defaultSections()
        return try {
            val stored = gson.fromJson<List<NavSectionConfig>>(raw, listType) ?: emptyList()
            if (stored.isEmpty()) return defaultSections()
            // Merge in any NavSectionKind not present in an older stored list (e.g. a
            // later release adds a new destination) so upgrades don't silently drop a
            // nav item the user never chose to hide.
            val storedKinds = stored.map { it.kind }.toSet()
            val missingFixed = fixedKinds.filter { it !in storedKinds }
                .mapIndexed { i, kind -> NavSectionConfig(kind = kind, visible = defaultVisibility(kind), order = stored.size + i) }
            // Same idea for the CUSTOM defaults (Movies/Shows/Watchlist) — a stored
            // list from before they existed has no CUSTOM entries at all, and CUSTOM
            // isn't in fixedKinds, so the merge above alone would never add them.
            val storedCustomIds = stored.filter { it.kind == NavSectionKind.CUSTOM }.mapNotNull { it.customId }.toSet()
            val missingCustom = defaultCustomEntries(startOrder = stored.size + missingFixed.size)
                .filter { it.customId !in storedCustomIds }
            (stored + missingFixed + missingCustom).sortedBy { it.order }
        } catch (_: Exception) {
            defaultSections()
        }
    }

    suspend fun getSectionsForProfile(profileId: String): List<NavSectionConfig> {
        val prefs = context.settingsDataStore.data.first()
        return readSectionsFromPrefs(profileId, prefs)
    }

    // Reactive source for AppTopBar/Sidebar — updates live when Settings changes
    // the config or the active profile switches, mirroring CatalogRepository.observeCatalogs().
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSectionsForActiveProfile(): Flow<List<NavSectionConfig>> {
        return profileManager.activeProfileId
            .flatMapLatest { profileId ->
                context.settingsDataStore.data.map { prefs -> readSectionsFromPrefs(profileId, prefs) }
            }
            .distinctUntilChanged()
    }

    suspend fun saveSectionsForProfile(profileId: String, sections: List<NavSectionConfig>) {
        context.settingsDataStore.edit { prefs ->
            prefs[navSectionsKey(profileId)] = gson.toJson(sections)
        }
    }

    suspend fun updateSection(
        profileId: String,
        kind: NavSectionKind,
        transform: (NavSectionConfig) -> NavSectionConfig,
    ) {
        val current = getSectionsForProfile(profileId)
        saveSectionsForProfile(profileId, current.map { if (it.kind == kind) transform(it) else it })
    }

    suspend fun reorderSection(profileId: String, kind: NavSectionKind, delta: Int) {
        val current = getSectionsForProfile(profileId).sortedBy { it.order }
        val index = current.indexOfFirst { it.kind == kind }
        val targetIndex = (index + delta).coerceIn(0, current.size - 1)
        if (index < 0 || targetIndex == index) return
        val mutable = current.toMutableList()
        val moved = mutable.removeAt(index)
        mutable.add(targetIndex, moved)
        saveSectionsForProfile(profileId, mutable.mapIndexed { i, section -> section.copy(order = i) })
    }

    // ── Active-profile convenience wrappers — used by the nav customization
    // Settings screen so callers don't need to resolve a profile id themselves. ──

    private suspend fun activeProfileId(): String =
        profileManager.getProfileIdSync().ifBlank { profileManager.getProfileId() }.ifBlank { "default" }

    suspend fun toggleVisibleForActiveProfile(kind: NavSectionKind) {
        val profileId = activeProfileId()
        // Settings is never hideable (always rendered as the gear icon), and hiding
        // the last remaining visible item would leave the nav bar completely empty.
        if (kind == NavSectionKind.SETTINGS) return
        val current = getSectionsForProfile(profileId)
        val visibleCount = current.count { it.visible && it.kind != NavSectionKind.SETTINGS }
        val target = current.firstOrNull { it.kind == kind } ?: return
        if (target.visible && visibleCount <= 1) return
        updateSection(profileId, kind) { it.copy(visible = !it.visible) }
    }

    suspend fun toggleIconOnlyForActiveProfile(kind: NavSectionKind) {
        updateSection(activeProfileId(), kind) { it.copy(iconOnly = !it.iconOnly) }
    }

    suspend fun renameForActiveProfile(kind: NavSectionKind, label: String?) {
        val trimmed = label?.trim()
        updateSection(activeProfileId(), kind) { it.copy(label = trimmed?.takeIf { l -> l.isNotBlank() }) }
    }

    suspend fun reorderForActiveProfile(kind: NavSectionKind, delta: Int) {
        reorderSection(activeProfileId(), kind, delta)
    }
}
