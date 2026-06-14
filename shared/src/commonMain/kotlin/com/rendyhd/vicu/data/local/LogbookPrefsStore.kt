package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Logbook retention preferences. When [LogbookPrefs.enabled], completed tasks finished more
 * than [LogbookPrefs.retentionDays] days ago are hidden from the Logbook view (a non-destructive
 * display filter — nothing is deleted from the server). Off by default. See issue #6.
 */
data class LogbookPrefs(
    val enabled: Boolean = false,
    val retentionDays: Int = 90,
)

class LogbookPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("logbook_retention_enabled")
        val KEY_RETENTION_DAYS = intPreferencesKey("logbook_retention_days")
    }

    fun getPrefs(): Flow<LogbookPrefs> = dataStore.data.map { p ->
        LogbookPrefs(
            enabled = p[KEY_ENABLED] ?: false,
            retentionDays = p[KEY_RETENTION_DAYS] ?: 90,
        )
    }

    suspend fun setEnabled(v: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = v }
    }

    suspend fun setRetentionDays(v: Int) {
        dataStore.edit { it[KEY_RETENTION_DAYS] = v.coerceIn(1, 3650) }
    }
}

