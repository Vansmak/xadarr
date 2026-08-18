package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.settingsDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val PINNED_PROVIDER_CHANNELS_KEY = stringPreferencesKey("pinned_provider_channels_json")

/**
 * Persists channels the user pinned from a full-provider catalog search (see
 * [DispatcharrCatalogRepository]) so they keep showing up in the Live TV guide as ordinary
 * playable channels across app restarts, without needing to search again. Kept deliberately
 * separate from [IptvRepository]'s M3U-derived snapshot/cache pipeline — these channels don't
 * come from the M3U at all, so they're merged in only at the guide-rendering layer
 * (see LiveTvScreen.kt), not into IptvRepository's cached playlist.
 */
@Singleton
class PinnedProviderChannelsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    fun observePinned(): Flow<List<RawProviderStream>> =
        context.settingsDataStore.data.map { prefs -> decode(prefs[PINNED_PROVIDER_CHANNELS_KEY].orEmpty()) }

    suspend fun pin(stream: RawProviderStream) {
        context.settingsDataStore.edit { prefs ->
            val existing = decode(prefs[PINNED_PROVIDER_CHANNELS_KEY].orEmpty()).toMutableList()
            existing.removeAll { it.id == stream.id }
            existing.add(0, stream)
            prefs[PINNED_PROVIDER_CHANNELS_KEY] = gson.toJson(existing)
        }
    }

    suspend fun unpin(id: String) {
        context.settingsDataStore.edit { prefs ->
            val existing = decode(prefs[PINNED_PROVIDER_CHANNELS_KEY].orEmpty()).toMutableList()
            existing.removeAll { it.id == id }
            prefs[PINNED_PROVIDER_CHANNELS_KEY] = gson.toJson(existing)
        }
    }

    private fun decode(raw: String): List<RawProviderStream> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val type = TypeToken.getParameterized(List::class.java, RawProviderStream::class.java).type
            gson.fromJson<List<RawProviderStream>>(raw, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
