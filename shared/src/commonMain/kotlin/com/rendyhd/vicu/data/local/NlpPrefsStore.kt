package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.SyntaxMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NlpPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_NLP_ENABLED = booleanPreferencesKey("nlp_enabled")
        private val KEY_SYNTAX_MODE = stringPreferencesKey("nlp_syntax_mode")
        private val KEY_BANG_TODAY = booleanPreferencesKey("bang_today")
    }

    val config: Flow<ParserConfig> = dataStore.data.map { prefs ->
        ParserConfig(
            enabled = prefs[KEY_NLP_ENABLED] ?: true,
            syntaxMode = when (prefs[KEY_SYNTAX_MODE]) {
                "vikunja" -> SyntaxMode.VIKUNJA
                else -> SyntaxMode.TODOIST
            },
            bangToday = prefs[KEY_BANG_TODAY] ?: true,
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NLP_ENABLED] = enabled }
    }

    suspend fun setSyntaxMode(mode: SyntaxMode) {
        dataStore.edit {
            it[KEY_SYNTAX_MODE] = when (mode) {
                SyntaxMode.TODOIST -> "todoist"
                SyntaxMode.VIKUNJA -> "vikunja"
            }
        }
    }

    suspend fun setBangToday(enabled: Boolean) {
        dataStore.edit { it[KEY_BANG_TODAY] = enabled }
    }
}

