package com.rendyhd.vicu.ui.components.selection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.descendantsDepthFirst
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.util.unfinishedDescendants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Per-screen multi-select state + bulk actions. Scoped to the screen's NavBackStackEntry via
 * koinViewModel(), so selection is contextual to one list and clears when you leave.
 *
 * Bulk ops send the COMPLETE Task object (Go zero-value problem) or use the dedicated
 * move/label endpoints; the list ViewModels observe Room flows and update automatically.
 */
class SelectionViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
) : ViewModel() {

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()
    private val _selectedDescendantCount = MutableStateFlow(0)
    val selectedDescendantCount: StateFlow<Int> = _selectedDescendantCount.asStateFlow()
    private val _pendingCompletionDescendantCount = MutableStateFlow<Int?>(null)
    val pendingCompletionDescendantCount: StateFlow<Int?> = _pendingCompletionDescendantCount.asStateFlow()
    private var pendingCompletionTasks: List<Task> = emptyList()

    val projects: StateFlow<List<Project>> = projectRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val labels: StateFlow<List<Label>> = labelRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun toggle(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
        refreshSelectedDescendantCount()
    }

    fun clear() {
        _selectedIds.value = emptySet()
        _selectedDescendantCount.value = 0
        _pendingCompletionDescendantCount.value = null
        pendingCompletionTasks = emptyList()
    }

    fun bulkComplete() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val tasks = rootSelection(taskRepository.getByIds(ids).filter { !it.done }, ids)
            val descendantCount = tasks
                .flatMap { it.unfinishedDescendants() }
                .distinctBy { it.id }
                .size
            if (descendantCount > 0) {
                pendingCompletionTasks = tasks
                _pendingCompletionDescendantCount.value = descendantCount
            } else {
                completeTasks(tasks)
            }
        }
    }

    fun confirmBulkComplete() {
        val tasks = pendingCompletionTasks
        if (tasks.isEmpty()) return
        viewModelScope.launch { completeTasks(tasks) }
    }

    fun dismissBulkComplete() {
        pendingCompletionTasks = emptyList()
        _pendingCompletionDescendantCount.value = null
    }

    private suspend fun completeTasks(tasks: List<Task>) {
        tasks.forEach { taskRepository.toggleDone(it) }
        clear()
    }

    private fun rootSelection(
        tasks: List<Task>,
        selectedIds: Set<Long>,
    ): List<Task> = tasks.filter { task ->
        task.relatedTasks[RelationKind.PARENTTASK]
            .orEmpty()
            .none { it.id in selectedIds }
    }

    private fun refreshSelectedDescendantCount() {
        val ids = _selectedIds.value
        viewModelScope.launch {
            _selectedDescendantCount.value = taskRepository.getByIds(ids)
                .flatMap { it.descendantsDepthFirst() }
                .distinctBy { it.id }
                .size
        }
    }

    fun bulkMove(projectId: Long) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { taskRepository.moveToProject(it, projectId) }
            clear()
        }
    }

    fun bulkToday() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            taskRepository.getByIds(ids).forEach { task ->
                taskRepository.update(task.copy(dueDate = DateUtils.todayEndIso()))
            }
            clear()
        }
    }

    fun bulkSchedule(dueDate: String) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            taskRepository.getByIds(ids).forEach { task ->
                taskRepository.update(task.copy(dueDate = dueDate))
            }
            clear()
        }
    }

    fun bulkSetPriority(priority: Int) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            taskRepository.getByIds(ids).forEach { task ->
                taskRepository.update(task.copy(priority = priority))
            }
            clear()
        }
    }

    fun bulkRemove() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (taskRepository.getByIds(ids).any { it.descendantsDepthFirst().isNotEmpty() }) {
                refreshSelectedDescendantCount()
                return@launch
            }
            ids.forEach { taskRepository.delete(it) }
            clear()
        }
    }

    fun bulkApplyLabel(labelId: Long) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { labelRepository.addToTask(it, labelId) }
            clear()
        }
    }
}
