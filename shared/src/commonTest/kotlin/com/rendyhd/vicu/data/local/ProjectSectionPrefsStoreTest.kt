package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectSectionPrefsStoreTest {
    @Test
    fun `collapsed sections persist independently for each project view`() = runTest {
        val store = ProjectSectionPrefsStore(FakePreferencesDataStore())

        store.setExpanded(rootProjectId = 1L, sectionProjectId = 10L, isExpanded = false)
        store.setExpanded(rootProjectId = 1L, sectionProjectId = 11L, isExpanded = false)
        store.setExpanded(rootProjectId = 2L, sectionProjectId = 10L, isExpanded = false)

        assertEquals(setOf(10L, 11L), store.collapsedSectionIds(1L).first())
        assertEquals(setOf(10L), store.collapsedSectionIds(2L).first())
    }

    @Test
    fun `expanding a section removes only that persisted collapse`() = runTest {
        val store = ProjectSectionPrefsStore(FakePreferencesDataStore())
        store.setExpanded(rootProjectId = 1L, sectionProjectId = 10L, isExpanded = false)
        store.setExpanded(rootProjectId = 1L, sectionProjectId = 11L, isExpanded = false)

        store.setExpanded(rootProjectId = 1L, sectionProjectId = 10L, isExpanded = true)

        assertEquals(setOf(11L), store.collapsedSectionIds(1L).first())
        assertEquals(emptySet(), store.collapsedSectionIds(999L).first())
    }
}

private class FakePreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
