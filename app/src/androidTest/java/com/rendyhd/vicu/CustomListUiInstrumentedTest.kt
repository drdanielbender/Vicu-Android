package com.rendyhd.vicu

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.components.shared.CustomListDialog
import com.rendyhd.vicu.ui.navigation.DrawerContent
import com.rendyhd.vicu.ui.navigation.DrawerUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomListUiInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun destinationSelectionIsSavedOnCustomList() {
        var saved: CustomList? = null
        composeRule.setContent {
            MaterialTheme {
                CustomListDialog(
                    customList = customList("Focused"),
                    projects = listOf(Project(id = 1, title = "Inbox"), Project(id = 2, title = "Project B")),
                    labels = emptyList(),
                    inboxProjectId = 1,
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Inbox (default)").performClick()
        composeRule.onNodeWithText("Project B", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Save").performClick()

        composeRule.runOnIdle { assertEquals(2L, saved?.filter?.addToProjectId) }
    }

    @Test
    fun openDrawerReflectsCustomListEditWithoutRecreation() {
        val state = mutableStateOf(DrawerUiState(customLists = listOf(customList("Before"))))
        composeRule.setContent {
            MaterialTheme {
                DrawerContent(
                    state = state.value,
                    currentRoute = null,
                    onNavigate = {},
                    onToggleProjects = {},
                    onToggleLists = {},
                    onToggleTags = {},
                )
            }
        }

        composeRule.onNodeWithText("Before").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.copy(customLists = listOf(customList("After")))
        }
        composeRule.onNodeWithText("After").assertTextEquals("After").assertIsDisplayed()
    }

    private fun customList(name: String) = CustomList(
        id = "list-a",
        name = name,
        icon = "filter_list",
        filter = CustomListFilter(),
    )
}
