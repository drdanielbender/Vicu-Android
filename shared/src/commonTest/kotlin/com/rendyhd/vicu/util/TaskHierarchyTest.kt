package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskHierarchyTest {
    @Test
    fun `child is nested when its parent is in the same result`() {
        val parentReference = Task(id = 1, title = "Parent")
        val child = Task(
            id = 2,
            title = "Child",
            relatedTasks = mapOf(RelationKind.PARENTTASK to listOf(parentReference)),
        )
        val parent = parentReference.copy(
            relatedTasks = mapOf(RelationKind.SUBTASK to listOf(child)),
        )

        assertEquals(listOf(parent), listOf(parent, child).withoutNestedSubtasks())
    }

    @Test
    fun `child remains visible when a filter omits its parent`() {
        val child = Task(
            id = 2,
            title = "Matching child",
            relatedTasks = mapOf(
                RelationKind.PARENTTASK to listOf(Task(id = 1, title = "Parent")),
            ),
        )

        assertEquals(listOf(child), listOf(child).withoutNestedSubtasks())
    }
}
