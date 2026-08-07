package com.rendyhd.vicu.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<Task> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
)

class SearchViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var collectJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }

        searchJob?.cancel()

        if (query.isBlank()) {
            collectJob?.cancel()
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            delay(300) // debounce
            _uiState.update { it.copy(isSearching = true) }

            // Trigger API search to refresh local cache
            taskRepository.refreshAll(mapOf("q" to query))

            // Observe local results
            collectJob?.cancel()
            collectJob = viewModelScope.launch {
                combine(
                    taskRepository.searchByTitle(query),
                    projectRepository.getAll(),
                ) { tasks, projects ->
                    val activeIds = projects.mapTo(mutableSetOf()) { it.id }
                    tasks.filter { it.projectId in activeIds }
                }.collect { visibleTasks ->
                    _uiState.update { it.copy(results = visibleTasks, isSearching = false) }
                }
            }
        }
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            if (!task.done) {
                _uiState.update { it.copy(completedTaskIds = it.completedTaskIds + task.id) }
            }
            when (val result = taskRepository.toggleDone(task)) {
                is NetworkResult.Error -> {
                    // Hard failure: revert the optimistic strikethrough along with surfacing
                    // the error, otherwise the row stays visually completed.
                    _uiState.update {
                        it.copy(
                            error = result.message,
                            completedTaskIds = it.completedTaskIds - task.id,
                        )
                    }
                }
                else -> {}
            }
        }
    }

    fun undoComplete(task: Task) {
        viewModelScope.launch {
            _uiState.update { it.copy(completedTaskIds = it.completedTaskIds - task.id) }
            // The row still renders done=false (Room is never flipped on complete — the
            // strikethrough is driven by completedTaskIds), and toggleDone flips whatever
            // it's handed. Pass done=true so it reverts to done=false remotely; passing the
            // raw task would re-send done=true and the completion would survive a refresh.
            taskRepository.toggleDone(task.copy(done = true))
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
