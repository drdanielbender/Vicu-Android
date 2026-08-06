package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task

/**
 * Hide a subtask from the top level only when one of its parents is present in
 * the same result. This preserves useful child-only search and filtered views.
 */
fun List<Task>.withoutNestedSubtasks(): List<Task> {
    val visibleIds = mapTo(HashSet(size)) { it.id }
    return filter { task ->
        task.relatedTasks[RelationKind.PARENTTASK]
            .orEmpty()
            .none { parent -> parent.id in visibleIds }
    }
}
