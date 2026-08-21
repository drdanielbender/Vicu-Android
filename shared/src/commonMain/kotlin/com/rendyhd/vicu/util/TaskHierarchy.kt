package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task

/**
 * Hide a subtask from the top level when one of its parents is present in the
 * same result or is completed. Active parents omitted by a search/date filter
 * still allow a contextual child-only match; completing a parent never promotes
 * unfinished children into unrelated root tasks.
 */
fun List<Task>.withoutNestedSubtasks(): List<Task> {
    val visibleIds = mapTo(HashSet(size)) { it.id }
    return filter { task ->
        task.relatedTasks[RelationKind.PARENTTASK]
            .orEmpty()
            .none { parent -> parent.id in visibleIds || parent.done }
    }
}

/** All descendants in depth-first order, with each child before its own descendants. */
fun Task.descendantsDepthFirst(): List<Task> {
    val result = mutableListOf<Task>()
    val visited = mutableSetOf(id)

    fun visit(parent: Task) {
        parent.relatedTasks[RelationKind.SUBTASK].orEmpty().forEach { child ->
            if (!visited.add(child.id)) return@forEach
            result += child
            visit(child)
        }
    }

    visit(this)
    return result
}

fun Task.unfinishedDescendants(): List<Task> = descendantsDepthFirst().filterNot { it.done }

fun Task.subtaskProgress(): Pair<Int, Int> {
    val descendants = descendantsDepthFirst()
    return descendants.count { it.done } to descendants.size
}
