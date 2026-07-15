package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.SyntaxMode
import com.rendyhd.vicu.util.parser.TaskParser
import com.rendyhd.vicu.util.parser.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TaskEditShortcutsTest {
    private val config = ParserConfig(syntaxMode = SyntaxMode.TODOIST)
    private val projects = listOf(
        Project(id = 10L, title = "Inbox"),
        Project(id = 20L, title = "Work"),
    )

    @Test
    fun `applies parsed shortcuts to an existing task`() {
        val original = Task(
            id = 1L,
            title = "Original",
            dueDate = "2026-01-01T00:00:00Z",
            priority = 1,
            projectId = 10L,
        )
        val parsed = TaskParser.parse(
            "Updated tomorrow #work @focus p1 every 2 weeks",
            config,
        )

        val result = applyTaskEditShortcuts(original, parsed, projects)

        assertEquals("Updated", result.task.title)
        assertNotEquals(original.dueDate, result.task.dueDate)
        assertEquals(4, result.task.priority)
        assertEquals(20L, result.task.projectId)
        assertEquals(1_209_600L, result.task.repeatAfter)
        assertEquals(0, result.task.repeatMode)
        assertEquals(listOf("focus"), result.labelNames)
    }

    @Test
    fun `manual picker changes win over title shortcuts`() {
        val edited = Task(
            id = 1L,
            title = "Updated tomorrow #work @focus p1 every week",
            dueDate = "2026-02-02T00:00:00Z",
            priority = 2,
            projectId = 10L,
            repeatAfter = 86_400L,
        )
        val parsed = TaskParser.parse(edited.title, config)

        val result = applyTaskEditShortcuts(
            task = edited,
            parseResult = parsed,
            projects = projects,
            manuallyEditedTypes = TokenType.entries.toSet(),
        )

        assertEquals("Updated", result.task.title)
        assertEquals(edited.dueDate, result.task.dueDate)
        assertEquals(edited.priority, result.task.priority)
        assertEquals(edited.projectId, result.task.projectId)
        assertEquals(edited.repeatAfter, result.task.repeatAfter)
        assertEquals(emptyList<String>(), result.labelNames)
    }

    @Test
    fun `keeps current project when shortcut has no matching project`() {
        val task = Task(id = 1L, title = "Task #missing", projectId = 10L)
        val parsed = TaskParser.parse(task.title, config)

        val result = applyTaskEditShortcuts(task, parsed, projects)

        assertEquals("Task", result.task.title)
        assertEquals(10L, result.task.projectId)
    }
}
