package com.arflix.tv.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// Same name and package as Xadarr's own, so the shared music package compiles unchanged here.
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings_prefs")
