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
/**
 * How Scan & Pay hands a payment to the UPI app.
 * Some UPI apps (CRED, BHIM, some bank apps) accept person-to-person payments started by FinFox; others
 * (Google Pay, PhonePe) refuse them with "payment not allowed". The user picks what works with their apps.
 */
enum class PayMode(val title: String, val subtitle: String) {
    AUTO("Automatic", "Shops open directly; for people, FinFox copies the UPI ID and opens your app"),
    DIRECT("Open UPI app with details", "Amount and payee filled in. Works with apps like CRED and BHIM"),
    COPY("Copy UPI ID & open app", "You paste the UPI ID and pay inside the app. Works with every UPI app"),
}

class AppearanceRepository(private val store: DataStore<Preferences>, private val settings: SettingsRepository) {
    private val key = stringPreferencesKey("theme_mode")

    private val stored: Flow<ThemeMode?> = store.data.map { p -> p[key]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } }

    val themeMode: Flow<ThemeMode> = combine(stored, settings.settings) { ds, room -> ds ?: room.themeMode }

    private val payKey = stringPreferencesKey("pay_mode")
    val payMode: Flow<PayMode> = store.data.map { p -> p[payKey]?.let { runCatching { PayMode.valueOf(it) }.getOrNull() } ?: PayMode.AUTO }
    suspend fun setPayMode(mode: PayMode) { store.edit { it[payKey] = mode.name } }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[key] = mode.name }
        settings.update { it.copy(themeMode = mode) }
    }
}
