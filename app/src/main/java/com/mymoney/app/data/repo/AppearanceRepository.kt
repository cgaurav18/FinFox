package com.mymoney.app.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mymoney.app.data.db.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Settings → Appearance: Light / Dark / Follow System, persisted in DataStore and applied immediately
 * (the root composable observes [themeMode]). Users upgrading from a version that stored the theme in
 * the Room settings row keep their choice: that value is used until a DataStore value is written.
 * The Room column is still updated so backups carry the preference.
 */
class AppearanceRepository(private val store: DataStore<Preferences>, private val settings: SettingsRepository) {
    private val key = stringPreferencesKey("theme_mode")

    private val stored: Flow<ThemeMode?> = store.data.map { p -> p[key]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } }

    val themeMode: Flow<ThemeMode> = combine(stored, settings.settings) { ds, room -> ds ?: room.themeMode }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[key] = mode.name }
        settings.update { it.copy(themeMode = mode) }
    }
}
