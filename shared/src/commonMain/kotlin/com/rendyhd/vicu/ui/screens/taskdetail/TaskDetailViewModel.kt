package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.PlatformFiles
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DescriptionHtml
import com.rendyhd.vicu.util.ImageTokens
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.TaskParser
import com.rendyhd.vicu.util.parser.TokenType
import com.rendyhd.vicu.util.parser.extractBangToday
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskDetailUiState(
    val task: Task? = null,
    val originalTask: Task? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val allProjects: List<Project> = emptyList(),
    val allLabels: List<Label> = emptyList(),
    val subtasks: List<Task> = emptyList(),
    val relations: Map<String, List<Task>> = emptyMap(),
    val attachments: List<Attachment> = emptyList(),
    val showDeleteConfirmation: Boolean = false,
    val isDeleted: Boolean = false,
    val inboxProjectId: Long = 0L,
    val isUploadingImage: Boolean = false,
    val parseResult: ParseResult? = null,
    val parserConfig: ParserConfig = ParserConfig(),
    val suppressedTypes: Set<TokenType> = emptySet(),
    val manuallyEditedTypes: Set<TokenType> = emptySet(),
)

class TaskDetailViewModel(
    private val taskRepository: TaskRepository,
    private val labelRepository: LabelRepository,
    private val attachmentRepository: AttachmentRepository,
    private val projectRepository: ProjectRepository,
    private val authManager: AuthManager,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val nlpPrefsStore: NlpPrefsStore,
    private val platformFiles: PlatformFiles,
) : ViewModel() {

    companion object {
        private const val TAG = "TaskDetailVM"
    }

    private val _uiState = MutableStateFlow(TaskDetailUiState())
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    private var taskIdLoaded = 0L

    /** Collectors started by loadTask; cancelled when a different task is loaded so a
     *  previously opened task's Room emissions can't overwrite the current task's state. */
    private val loadJobs = mutableListOf<Job>()

    /** Preserved link HTML stripped from description for display, re-appended on save. */
    private var preservedLinkHtml = ""

    /** Raw token text retained when a parse-preview chip is dismissed. */
    private var suppressedRawTexts: Map<TokenType, List<String>> = emptyMap()

    private val _relationSearchQuery = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val relationSearchResults: StateFlow<List<Task>> = _relationSearchQuery
        .debounce(250)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else taskRepository.searchByTitleIncludingDone(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            nlpPrefsStore.config.collect { config ->
                _uiState.update { state ->
                    val newConfig = config.copy(suppressTypes = state.suppressedTypes)
                    state.copy(
                        parserConfig = newConfig,
                        parseResult = state.parseResult?.let {
                            state.task?.title?.takeIf(String::isNotBlank)?.let { title ->
                                TaskParser.parse(title, newConfig)
                            }
                        },
                    )
                }
            }
        }
    }

    fun loadTask(taskId: Long) {
        if (taskId == taskIdLoaded) return
        taskIdLoaded = taskId

        loadJobs.forEach { it.cancel() }
        loadJobs.clear()

        // Reset state for the new task so stale data from the previous task doesn't persist
        preservedLinkHtml = ""
        suppressedRawTexts = emptyMap()
        _uiState.update {
            it.copy(
                task = null,
                originalTask = null,
                isLoading = true,
                isDeleted = false,
                error = null,
                parseResult = null,
                suppressedTypes = emptySet(),
                manuallyEditedTypes = emptySet(),
                parserConfig = it.parserConfig.copy(suppressTypes = emptySet()),
            )
        }

        loadJobs += viewModelScope.launch {
            taskRepository.getById(taskId).collect { task ->
                if (task != null) {
                    val subtasks = task.relatedTasks["subtask"] ?: emptyList()
                    val relations = task.relatedTasks
                        .filterKeys { it in com.rendyhd.vicu.util.RelationKind.DISPLAYABLE }
                        .filterValues { it.isNotEmpty() }
                    val isFirstLoad = _uiState.value.originalTask == null
                    if (isFirstLoad) {
                        val split = DescriptionHtml.splitForEditor(task.description)
                        preservedLinkHtml = split.linkHtml
                        val displayDesc = ImageTokens.buildValue(split.htmlBody, split.imageRefs)
                        val displayTask = task.copy(description = displayDesc)
                        _uiState.update {
                            it.copy(
                                task = displayTask,
                                originalTask = displayTask,
                                subtasks = subtasks,
                                relations = relations,
                                isLoading = false,
                            )
                        }
                    } else {
                        // Preserve the user's in-progress title/description edits, but adopt
                        // server-confirmed labels from later Room emissions so an added/removed
                        // label is reflected here (issue #6 — the checkbox previously went stale).
                        _uiState.update { st ->
                            st.copy(
                                task = st.task?.copy(labels = task.labels),
                                subtasks = subtasks,
                                relations = relations,
                                isLoading = false,
                            )
                        }
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }

        loadJobs += viewModelScope.launch {
            projectRepository.getAll().collect { projects ->
                _uiState.update { it.copy(allProjects = projects) }
            }
        }

        loadJobs += viewModelScope.launch {
            val inboxId = authManager.getInboxProjectId() ?: 0L
            _uiState.update { it.copy(inboxProjectId = inboxId) }
        }

        loadJobs += viewModelScope.launch {
            labelRepository.getAll().collect { labels ->
                _uiState.update { it.copy(allLabels = labels) }
            }
        }

        loadJobs += viewModelScope.launch {
            attachmentRepository.getByTaskId(taskId).collect { attachments ->
                _uiState.update { it.copy(attachments = attachments) }
            }
        }

        loadJobs += viewModelScope.launch {
            attachmentRepository.refreshForTask(taskId)
        }
    }

    fun updateTitle(title: String) {
        _uiState.update { state ->
            val activeSuppressed = state.suppressedTypes.filter { type ->
                val texts = suppressedRawTexts[type] ?: return@filter false
                texts.any { title.contains(it) }
            }.toSet()
            if (activeSuppressed != state.suppressedTypes) {
                suppressedRawTexts = suppressedRawTexts.filterKeys { it in activeSuppressed }
            }

            val config = state.parserConfig.copy(suppressTypes = activeSuppressed)
            state.copy(
                task = state.task?.copy(title = title),
                parseResult = title.takeIf(String::isNotBlank)?.let { TaskParser.parse(it, config) },
                suppressedTypes = activeSuppressed,
                parserConfig = config,
            )
        }
    }

    fun suppressType(type: TokenType) {
        _uiState.update { state ->
            val result = state.parseResult ?: return@update state
            suppressedRawTexts = suppressedRawTexts + (
                type to result.tokens.filter { it.type == type }.map { it.raw }
            )
            val newSuppressed = state.suppressedTypes + type
            val config = state.parserConfig.copy(suppressTypes = newSuppressed)
            state.copy(
                parseResult = state.task?.title?.takeIf(String::isNotBlank)?.let {
                    TaskParser.parse(it, config)
                },
                suppressedTypes = newSuppressed,
                parserConfig = config,
            )
        }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(task = it.task?.copy(description = description)) }
    }

    fun setDueDate(dueDate: String) {
        _uiState.update {
            it.copy(
                task = it.task?.copy(dueDate = dueDate),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.DATE,
            )
        }
    }

    fun clearDueDate() {
        _uiState.update {
            it.copy(
                task = it.task?.copy(dueDate = Constants.NULL_DATE_STRING),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.DATE,
            )
        }
    }

    fun cyclePriority() {
        _uiState.update {
            val current = it.task?.priority ?: 0
            it.copy(
                task = it.task?.copy(priority = (current + 1) % 5),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.PRIORITY,
            )
        }
    }

    fun setPriority(value: Int) {
        _uiState.update {
            it.copy(
                task = it.task?.copy(priority = value.coerceIn(0, 4)),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.PRIORITY,
            )
        }
    }

    /** Clears a recurrence set elsewhere (e.g. desktop); persisted on dismiss via saveIfChanged. */
    fun clearRecurrence() {
        _uiState.update {
            it.copy(
                task = it.task?.copy(repeatAfter = 0, repeatMode = 0),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.RECURRENCE,
            )
        }
    }

    fun setProject(projectId: Long) {
        _uiState.update {
            it.copy(
                task = it.task?.copy(projectId = projectId),
                manuallyEditedTypes = it.manuallyEditedTypes + TokenType.PROJECT,
            )
        }
    }

    fun addLabel(labelId: Long) {
        val current = _uiState.value.task ?: return
        if (current.labels.any { it.id == labelId }) return
        // Optimistically reflect the label so the picker checkbox + chips update immediately,
        // without waiting for the server round-trip and Room re-emission (issue #6).
        val label = _uiState.value.allLabels.find { it.id == labelId }
        if (label != null) {
            _uiState.update { st ->
                val t = st.task ?: return@update st
                st.copy(task = t.copy(labels = t.labels + label))
            }
        }
        viewModelScope.launch {
            when (val result = labelRepository.addToTask(current.id, labelId)) {
                is NetworkResult.Error -> _uiState.update { st ->
                    // Roll back the optimistic add and surface the failure.
                    val t = st.task ?: return@update st.copy(error = result.message)
                    st.copy(
                        task = t.copy(labels = t.labels.filterNot { it.id == labelId }),
                        error = result.message,
                    )
                }
                else -> {}
            }
        }
    }

    fun removeLabel(labelId: Long) {
        val current = _uiState.value.task ?: return
        val removed = current.labels.find { it.id == labelId } ?: return
        // Optimistic remove.
        _uiState.update { st ->
            val t = st.task ?: return@update st
            st.copy(task = t.copy(labels = t.labels.filterNot { it.id == labelId }))
        }
        viewModelScope.launch {
            when (val result = labelRepository.removeFromTask(current.id, labelId)) {
                is NetworkResult.Error -> _uiState.update { st ->
                    // Roll back the optimistic remove and surface the failure.
                    val t = st.task ?: return@update st.copy(error = result.message)
                    val restored = if (t.labels.none { it.id == labelId }) t.labels + removed else t.labels
                    st.copy(task = t.copy(labels = restored), error = result.message)
                }
                else -> {}
            }
        }
    }

    fun createAndAddLabel(name: String, hexColor: String) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            val label = Label(id = 0, title = name, hexColor = hexColor)
            when (val result = labelRepository.create(label)) {
                is NetworkResult.Success -> {
                    labelRepository.addToTask(task.id, result.data.id)
                }
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun addReminder(reminder: TaskReminder) {
        _uiState.update {
            it.copy(task = it.task?.copy(reminders = it.task.reminders + reminder))
        }
    }

    fun removeReminder(index: Int) {
        _uiState.update {
            val updated = it.task?.reminders?.toMutableList()?.apply { removeAt(index) } ?: emptyList()
            it.copy(task = it.task?.copy(reminders = updated))
        }
    }

    fun editReminder(index: Int, reminder: TaskReminder) {
        _uiState.update {
            val updated = it.task?.reminders?.toMutableList()?.apply { set(index, reminder) } ?: emptyList()
            it.copy(task = it.task?.copy(reminders = updated))
        }
    }

    fun createSubtask(title: String) {
        val task = _uiState.value.task ?: return
        if (title.isBlank()) return

        viewModelScope.launch {
            val subtask = Task(
                id = 0,
                title = title,
                projectId = task.projectId,
            )
            when (val result = taskRepository.createSubtask(task.id, subtask)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun addRelation(otherTaskId: Long, relationKind: String) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            when (val result = taskRepository.createRelation(task.id, otherTaskId, relationKind)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun removeRelation(relationKind: String, otherTaskId: Long) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            when (val result = taskRepository.deleteRelation(task.id, relationKind, otherTaskId)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun setRelationSearchQuery(q: String) {
        _relationSearchQuery.value = q
        if (q.isNotBlank()) {
            viewModelScope.launch { taskRepository.refreshAll(mapOf("q" to q)) }
        }
    }

    fun toggleSubtaskDone(subtask: Task) {
        val parentId = _uiState.value.task?.id ?: return
        val target = !subtask.done
        // Optimistically flip the checkbox so it responds instantly; the repository also flips
        // the parent's cached relatedTasks, so the Room re-emission reconciles to the same state.
        _uiState.update { st ->
            st.copy(
                subtasks = st.subtasks.map { if (it.id == subtask.id) it.copy(done = target) else it },
                relations = st.relations.mapValues { (_, list) ->
                    list.map { if (it.id == subtask.id) it.copy(done = target) else it }
                },
            )
        }
        viewModelScope.launch {
            when (val result = taskRepository.toggleSubtaskDone(parentId, subtask)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun uploadAttachment(uriString: String) {
        val task = _uiState.value.task ?: return
        val fileInfo = platformFiles.getFileNameAndBytes(uriString) ?: return
        viewModelScope.launch {
            when (val result = attachmentRepository.upload(task.id, fileInfo.first, fileInfo.second)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun addImageAttachment(uriString: String) {
        val taskIdSnapshot = _uiState.value.task?.id ?: return
        val fileInfo = platformFiles.getFileNameAndBytes(uriString) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingImage = true) }
            when (val result = attachmentRepository.upload(taskIdSnapshot, fileInfo.first, fileInfo.second)) {
                is NetworkResult.Success -> {
                    // Read the LATEST description inside .update so keystrokes typed
                    // during the upload aren't dropped by a pre-launch snapshot.
                    _uiState.update { current ->
                        val latestDesc = current.task?.description ?: ""
                        val newDesc = ImageTokens.appendImageToken(latestDesc, result.data.id)
                        current.copy(
                            task = current.task?.copy(description = newDesc),
                            isUploadingImage = false,
                        )
                    }
                    // Persist immediately so a token added mid-session survives a
                    // process kill before the sheet's onDispose save fires.
                    saveIfChanged()
                }
                is NetworkResult.Error -> _uiState.update {
                    it.copy(isUploadingImage = false, error = result.message)
                }
                else -> _uiState.update { it.copy(isUploadingImage = false) }
            }
        }
    }

    fun deleteAttachment(attachmentId: Long) {
        val task = _uiState.value.task ?: return
        _uiState.update { it.copy(attachments = it.attachments.filter { a -> a.id != attachmentId }) }
        viewModelScope.launch {
            when (val result = attachmentRepository.delete(task.id, attachmentId)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    suspend fun downloadAttachment(attachmentId: Long): ByteArray? {
        val task = _uiState.value.task ?: return null
        return when (val result = attachmentRepository.download(task.id, attachmentId)) {
            is NetworkResult.Success -> result.data
            else -> null
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    /**
     * Entry point for the trash button. Respects the "Confirm before deleting"
     * behavior pref — when off, deletes immediately; when on, raises the dialog.
     */
    fun requestDeleteTask() {
        viewModelScope.launch {
            val prefs = behaviorPrefsStore.getPrefs().first()
            if (prefs.confirmBeforeDelete) {
                _uiState.update { it.copy(showDeleteConfirmation = true) }
            } else {
                deleteTask()
            }
        }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun deleteTask() {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteConfirmation = false) }
            when (val result = taskRepository.delete(task.id)) {
                is NetworkResult.Success -> _uiState.update { it.copy(isDeleted = true) }
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun saveIfChanged() {
        val state = _uiState.value
        val taskWithRawTitle = state.task ?: return
        val original = state.originalTask ?: return

        val shortcutResult = when {
            state.parserConfig.enabled && state.parseResult != null -> applyTaskEditShortcuts(
                task = taskWithRawTitle,
                parseResult = state.parseResult,
                projects = state.allProjects,
                manuallyEditedTypes = state.manuallyEditedTypes,
            )
            !state.parserConfig.enabled &&
                state.parserConfig.bangToday &&
                taskWithRawTitle.title != original.title &&
                TokenType.DATE !in state.manuallyEditedTypes -> {
                val bang = extractBangToday(taskWithRawTitle.title)
                if (bang.dueDate != null) {
                    TaskEditShortcutResult(
                        task = taskWithRawTitle.copy(
                            title = bang.title,
                            dueDate = DateUtils.todayStartIso(),
                        ),
                        labelNames = emptyList(),
                    )
                } else {
                    TaskEditShortcutResult(taskWithRawTitle, emptyList())
                }
            }
            else -> TaskEditShortcutResult(taskWithRawTitle, emptyList())
        }
        val task = shortcutResult.task
        val parsedLabelNames = shortcutResult.labelNames.filterNot { labelName ->
            task.labels.any { it.title.equals(labelName, ignoreCase = true) }
        }

        if (task == original && parsedLabelNames.isEmpty()) return

        // When the task's project changed, move its subtasks too: each subtask is a full
        // task with its own project_id and Vikunja does not cascade the move (issue #6).
        val movedSubtasks = if (task.projectId != original.projectId) state.subtasks else emptyList()
        val newProjectId = task.projectId

        Logger.d(TAG, "saveIfChanged: task has changes, saving...")
        // Re-append preserved link HTML before saving. task.description already
        // contains the rich-text HTML body + [[image:N]] tokens; link metadata
        // is stored separately and stitched back here.
        val (bodyHtml, imageRefs) = ImageTokens.parseValue(task.description)
        val fullDescription = DescriptionHtml.merge(bodyHtml, imageRefs, preservedLinkHtml)
        val taskToSave = task.copy(description = fullDescription)

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            // Send COMPLETE task object (Go zero-value problem)
            when (val result = taskRepository.update(taskToSave)) {
                is NetworkResult.Success -> {
                    // Cascade the project move to direct subtasks.
                    movedSubtasks.forEach { sub ->
                        taskRepository.moveToProject(sub.id, newProjectId)
                    }
                    val split = DescriptionHtml.splitForEditor(result.data.description)
                    preservedLinkHtml = split.linkHtml
                    suppressedRawTexts = emptyMap()
                    val displayDesc = ImageTokens.buildValue(split.htmlBody, split.imageRefs)
                    val displayed = result.data.copy(description = displayDesc)
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            task = displayed,
                            originalTask = displayed,
                            parseResult = null,
                            suppressedTypes = emptySet(),
                            manuallyEditedTypes = emptySet(),
                            parserConfig = it.parserConfig.copy(suppressTypes = emptySet()),
                        )
                    }

                    for (labelName in parsedLabelNames) {
                        val labelId = state.allLabels
                            .firstOrNull { it.title.equals(labelName, ignoreCase = true) }
                            ?.id
                            ?: when (
                                val createResult = labelRepository.create(
                                    Label(id = 0L, title = labelName, hexColor = ""),
                                )
                            ) {
                                is NetworkResult.Success -> createResult.data.id
                                is NetworkResult.Error -> {
                                    Logger.w(TAG, "Auto-create label '$labelName' failed: ${createResult.message}")
                                    null
                                }
                                is NetworkResult.Loading -> null
                            }

                        if (labelId != null) {
                            when (val addResult = labelRepository.addToTask(displayed.id, labelId)) {
                                is NetworkResult.Error -> _uiState.update {
                                    it.copy(error = addResult.message)
                                }
                                else -> {}
                            }
                        }
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(isSaving = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
