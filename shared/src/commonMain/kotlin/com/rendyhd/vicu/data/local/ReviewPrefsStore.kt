package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ReviewPrefs(
    val enabled: Boolean = true,
    val defaultCadenceDays: Int = 14,
    val excludeInbox: Boolean = true,
)

class ReviewPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("review_enabled")
        val KEY_DEFAULT_CADENCE = intPreferencesKey("review_default_cadence_days")
        val KEY_EXCLUDE_INBOX = booleanPreferencesKey("review_exclude_inbox")
    }

    fun getPrefs(): Flow<ReviewPrefs> = dataStore.data.map { p ->
        ReviewPrefs(
            enabled = p[KEY_ENABLED] ?: true,
            defaultCadenceDays = p[KEY_DEFAULT_CADENCE] ?: 14,
            excludeInbox = p[KEY_EXCLUDE_INBOX] ?: true,
        )
    }

    suspend fun setEnabled(v: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = v }
    }

    suspend fun setDefaultCadenceDays(v: Int) {
        dataStore.edit { it[KEY_DEFAULT_CADENCE] = v.coerceIn(1, 365) }
    }

    suspend fun setExcludeInbox(v: Boolean) {
        dataStore.edit { it[KEY_EXCLUDE_INBOX] = v }
    }
}

