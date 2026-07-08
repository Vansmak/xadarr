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

    // Matches today's fixed order/visibility exactly — used whenever a profile
    // has no stored config yet (fresh install or a device from before this existed).
    fun defaultSections(): List<NavSectionConfig> =
        NavSectionKind.entries.mapIndexed { index, kind -> NavSectionConfig(kind = kind, order = index) }

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
            val missing = NavSectionKind.entries.filter { it !in storedKinds }
                .mapIndexed { i, kind -> NavSectionConfig(kind = kind, order = stored.size + i) }
            (stored + missing).sortedBy { it.order }
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
