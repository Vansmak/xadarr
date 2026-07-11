package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.data.model.HomeLayoutConfig
import com.arflix.tv.util.settingsDataStore
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile home_layout config (hero + footer) — mirrors NavSectionRepository's
 * shape/DataStore pattern. Rows and nav stay in catalogsByProfile/navSectionsByProfile
 * respectively; this only covers the two zones that had no config home before.
 */
@Singleton
class HomeLayoutRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
) {
    private val gson = Gson()
    private fun homeLayoutKey(profileId: String) = stringPreferencesKey("profile_${profileId}_home_layout_v1")

    fun defaultLayout(): HomeLayoutConfig = HomeLayoutConfig()

    private fun readLayoutFromPrefs(profileId: String, prefs: Preferences): HomeLayoutConfig {
        val raw = prefs[homeLayoutKey(profileId)]
        if (raw.isNullOrBlank()) return defaultLayout()
        return runCatching { gson.fromJson(raw, HomeLayoutConfig::class.java) }.getOrNull() ?: defaultLayout()
    }

    suspend fun getLayoutForProfile(profileId: String): HomeLayoutConfig {
        val prefs = context.settingsDataStore.data.first()
        return readLayoutFromPrefs(profileId, prefs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLayoutForActiveProfile(): Flow<HomeLayoutConfig> {
        return profileManager.activeProfileId
            .flatMapLatest { profileId ->
                context.settingsDataStore.data.map { prefs -> readLayoutFromPrefs(profileId, prefs) }
            }
            .distinctUntilChanged()
    }

    suspend fun saveLayoutForProfile(profileId: String, layout: HomeLayoutConfig) {
        context.settingsDataStore.edit { prefs ->
            prefs[homeLayoutKey(profileId)] = gson.toJson(layout)
        }
    }
}
