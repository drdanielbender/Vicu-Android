package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.domain.model.CustomList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class CustomListStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_LISTS = stringPreferencesKey("custom_lists_json")
        private val json = Json { ignoreUnknownKeys = true }
    }

    fun getAll(): Flow<List<CustomList>> =
        dataStore.data.map { prefs ->
            val raw = prefs[KEY_LISTS] ?: return@map emptyList()
            try {
                json.decodeFromString(ListSerializer(CustomList.serializer()), raw)
            } catch (_: Exception) {
                emptyList()
            }
        }

    fun getById(id: String): Flow<CustomList?> =
        getAll().map { lists -> lists.find { it.id == id } }

    suspend fun save(list: CustomList) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_LISTS]?.let {
                try {
                    json.decodeFromString(ListSerializer(CustomList.serializer()), it).toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }
            } ?: mutableListOf()

            val index = current.indexOfFirst { it.id == list.id }
            if (index >= 0) {
                current[index] = list
            } else {
                current.add(list)
            }

            prefs[KEY_LISTS] = json.encodeToString(ListSerializer(CustomList.serializer()), current)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    suspend fun reorder(fromIndex: Int, toIndex: Int) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_LISTS]?.let {
                try {
                    json.decodeFromString(ListSerializer(CustomList.serializer()), it).toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }
            } ?: return@edit

            if (fromIndex !in current.indices || toIndex !in current.indices) return@edit
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            prefs[KEY_LISTS] = json.encodeToString(ListSerializer(CustomList.serializer()), current)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_LISTS]?.let {
                try {
                    json.decodeFromString(ListSerializer(CustomList.serializer()), it).toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }
            } ?: return@edit

            current.removeAll { it.id == id }
            prefs[KEY_LISTS] = json.encodeToString(ListSerializer(CustomList.serializer()), current)
        }
    }
}

