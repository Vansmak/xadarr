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
    // (customId/target are always explicit) — the CUSTOM defaults below
    // (Movies/Shows/Apps) are seeded directly, not derived from this list.
    // HOME/SEARCH/DISCOVER are also excluded: retired by the TiviMate-clone
    // redesign (Home IS the guide now; Movies/Shows launch Plex directly, no
    // in-app TMDB browsing/search) — excluding them here stops the upgrade-merge
    // path below from ever re-adding them to a profile that doesn't have them.
    private val retiredKinds = setOf(NavSectionKind.HOME, NavSectionKind.SEARCH, NavSectionKind.DISCOVER)
    private val fixedKinds = NavSectionKind.entries.filter { it != NavSectionKind.CUSTOM && it !in retiredKinds }

    // Kept for backward-compat parsing of pre-redesign persisted profiles; no
    // longer used to gate anything, since HOME/SEARCH/DISCOVER are filtered out
    // entirely rather than hidden-but-present now.
    private fun defaultVisibility(kind: NavSectionKind): Boolean = kind != NavSectionKind.DISCOVER

    // The CUSTOM defaults every profile should have — seeded directly since
    // CUSTOM has no single default instance the way fixedKinds does. Shared
    // with the upgrade-merge path in readSectionsFromPrefs() below so profiles
    // that predate these get them retrofitted instead of silently missing entries.
    // Movies/Shows launch Plex directly (no in-app deep link to a specific
    // library section exists — see PlexDeepLink.kt); Apps opens Screen.AllApps.
    private fun defaultCustomEntries(startOrder: Int): List<NavSectionConfig> = listOf(
        NavSectionConfig(kind = NavSectionKind.CUSTOM, customId = "movies", label = "Movies", order = startOrder),
        NavSectionConfig(kind = NavSectionKind.CUSTOM, customId = "shows", label = "Shows", order = startOrder + 1),
        NavSectionConfig(kind = NavSectionKind.CUSTOM, customId = "apps", label = "Apps", order = startOrder + 2),
        // Direct, generic launch into the native Plex app — not tied to a specific title (that's
        // Movies/Shows -> a title's own "Open in Plex"/long-press). Joe: "plex is also where I
        // may go to discover stuff" — browsing Plex's own recommendations, separate from Xadarr's
        // own Movies/Shows grid. Could live under the generic "Apps" screen instead, but he wants
        // it as its own rail entry rather than buried there.
        NavSectionConfig(kind = NavSectionKind.CUSTOM, customId = "plex", label = "Plex Discover", order = startOrder + 3),
    )

    // TiviMate-clone side menu: Guide (Home IS the guide) / Movies / Shows / Apps /
    // Plex Discover / Cameras / Settings — used whenever a profile has no stored config yet.
    fun defaultSections(): List<NavSectionConfig> {
        val customs = defaultCustomEntries(startOrder = 1)
        return listOf(
            NavSectionConfig(kind = NavSectionKind.TV, label = "Guide", order = 0),
            customs[0],
            customs[1],
            customs[2],
            customs[3],
            NavSectionConfig(kind = NavSectionKind.CAMERAS, order = 5),
            NavSectionConfig(kind = NavSectionKind.SETTINGS, order = 6),
        )
    }

    // Retired by the TiviMate-clone redesign — filtered here (not just excluded from
    // defaultSections()) so a profile with pre-redesign persisted JSON doesn't
    // resurrect Home/Search/Discover/the old Watchlist entry on the next read.
    private fun isRetiredEntry(section: NavSectionConfig): Boolean =
        section.kind in retiredKinds ||
            (section.kind == NavSectionKind.CUSTOM && section.customId == "watchlist")

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
            // Same idea for the CUSTOM defaults (Movies/Shows/Apps) — a stored
            // list from before they existed has no CUSTOM entries at all, and CUSTOM
            // isn't in fixedKinds, so the merge above alone would never add them.
            val storedCustomIds = stored.filter { it.kind == NavSectionKind.CUSTOM }.mapNotNull { it.customId }.toSet()
            val missingCustom = defaultCustomEntries(startOrder = stored.size + missingFixed.size)
                .filter { it.customId !in storedCustomIds }
            (stored + missingFixed + missingCustom)
                .filterNot { isRetiredEntry(it) }
                .sortedBy { it.order }
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
