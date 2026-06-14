package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SnoozeEntry(val taskId: Long, val title: String, val triggerAtMillis: Long)

/**
 * Persists pending snoozes so they survive reboots and are independent of the reminder
 * alarm sweep (which cancels and re-registers reminders on every sync).
 */
class SnoozeStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    companion object {
        private val KEY_SNOOZES = stringPreferencesKey("snoozes_json")
    }

    private val serializer = ListSerializer(SnoozeEntry.serializer())

    private fun decode(raw: String?): List<SnoozeEntry> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    suspend fun all(): List<SnoozeEntry> =
        decode(dataStore.data.first()[KEY_SNOOZES])

    suspend fun put(entry: SnoozeEntry) {
        dataStore.edit { prefs ->
            val updated = decode(prefs[KEY_SNOOZES]).filterNot { it.taskId == entry.taskId } + entry
            prefs[KEY_SNOOZES] = json.encodeToString(serializer, updated)
        }
    }

    suspend fun remove(taskId: Long) {
        dataStore.edit { prefs ->
            val updated = decode(prefs[KEY_SNOOZES]).filterNot { it.taskId == taskId }
            prefs[KEY_SNOOZES] = json.encodeToString(serializer, updated)
        }
    }
}

