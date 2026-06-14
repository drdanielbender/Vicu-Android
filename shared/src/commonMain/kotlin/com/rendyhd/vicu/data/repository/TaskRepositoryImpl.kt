package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.LogbookPrefsStore
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.TaskPositionDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.util.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import com.rendyhd.vicu.data.local.ScheduleAction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.datetime.Clock
import com.rendyhd.vicu.util.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val api: VikunjaApiService,
    private val pendingActionDao: PendingActionDao,
    private val taskMapper: TaskMapper,
    private val platformHooks: PlatformRepositoryHooks,
    private val json: Json,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val logbookPrefsStore: LogbookPrefsStore,
) : TaskRepository {

    companion object {
        private const val TAG = "TaskRepoImpl"
    }

    private val tempIdCounter = AtomicLong(-(Clock.System.now().epochSeconds))

    private suspend fun anchorNewTaskAtEnd(projectId: Long, newTaskId: Long) {
        if (projectId <= 0L) return
        try {
            val views = api.getProjectViews(projectId)
            val listView = views.firstOrNull { it.viewKind == "list" } ?: return
            val existing = api.getViewTasks(
                projectId,
                listView.id,
                mapOf("sort_by" to "position", "order_by" to "desc", "per_page" to "1"),
            )
            val maxPos = existing.firstOrNull()?.position ?: 0.0
            api.updateTaskPosition(
                newTaskId,
                TaskPositionDto(position = maxPos + 65_536.0, projectViewId = listView.id),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "anchorNewTaskAtEnd failed (non-fatal) for project=$projectId task=$newTaskId: ${e.message}")
        }
    }

    override suspend fun updatePosition(taskId: Long, projectId: Long, newPosition: Double) {
        taskDao.updatePosition(taskId, newPosition)
        try {
            val views = api.getProjectViews(projectId)
            val listView = views.firstOrNull { it.viewKind == "list" } ?: return
            api.updateTaskPosition(
                taskId,
                TaskPositionDto(position = newPosition, projectViewId = listView.id),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "updatePosition failed (non-fatal) for task=$taskId: ${e.message}")
        }
    }

    private suspend fun queueTaskAction(entityId: Long, actionType: String, payload: String) {
        val action = PendingActionEntity(
            entityType = "task",
            entityId = entityId,
            actionType = actionType,
            payload = payload,
            createdAt = DateUtils.nowIso(),
            updatedAt = DateUtils.nowIso(),
        )
        if (actionType == "create") {
            pendingActionDao.insert(action)
        } else {
            pendingActionDao.queueTaskActionMerging(action)
        }
        platformHooks.triggerSync()
    }

    override fun getInboxTasks(inboxProjectId: Long): Flow<List<Task>> =
        behaviorPrefsStore.getPrefs()
            .map { it.inboxExcludeDated }
            .distinctUntilChanged()
            .flatMapLatest { excludeDated ->
                taskDao.getInboxTasks(inboxProjectId, includeDated = !excludeDated).distinctUntilChanged().map { entities ->
                    entities.map { with(taskMapper) { it.toDomain() } }
                }
            }

    override fun getTodayTasks(): Flow<List<Task>> =
        DateUtils.endOfTodayFlow()
            .distinctUntilChanged()
            .flatMapLatest { endOfToday ->
                taskDao.getTodayTasks(endOfToday).distinctUntilChanged().map { entities ->
                    entities.map { with(taskMapper) { it.toDomain() } }
                }
            }

    override fun getUpcomingTasks(): Flow<List<Task>> =
        DateUtils.endOfTodayFlow()
            .distinctUntilChanged()
            .flatMapLatest { endOfToday ->
                taskDao.getUpcomingTasks(endOfToday).distinctUntilChanged().map { entities ->
                    entities.map { with(taskMapper) { it.toDomain() } }
                }
            }

    override fun getAnytimeTasks(inboxProjectId: Long): Flow<List<Task>> =
        taskDao.getAnytimeTasks(inboxProjectId).distinctUntilChanged().map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getLogbookTasks(): Flow<List<Task>> =
        logbookPrefsStore.getPrefs()
            .map { if (it.enabled) DateUtils.isoDaysAgo(it.retentionDays) else "" }
            .distinctUntilChanged()
            .flatMapLatest { cutoff ->
                taskDao.getLogbookTasks(cutoff).distinctUntilChanged().map { entities ->
                    entities.map { with(taskMapper) { it.toDomain() } }
                }
            }

    override fun getByProjectId(projectId: Long): Flow<List<Task>> =
        taskDao.getByProjectId(projectId).distinctUntilChanged().map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getById(id: Long): Flow<Task?> =
        taskDao.getById(id).map { entity ->
            entity?.let { with(taskMapper) { it.toDomain() } }
        }

    override fun searchByTitle(query: String): Flow<List<Task>> =
        taskDao.searchByTitle(query).map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun searchByTitleIncludingDone(query: String): Flow<List<Task>> =
        taskDao.searchByTitleIncludingDone(query).map { list ->
            list.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getAllOpenTasks(): Flow<List<Task>> =
        taskDao.getAllOpenTasks().distinctUntilChanged().map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getAllTasks(): Flow<List<Task>> =
        taskDao.getAllTasksFlow().distinctUntilChanged().map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override suspend fun create(task: Task): NetworkResult<Task> {
        return try {
            val createDto = with(taskMapper) { task.toCreateDto() }
            val responseDto = api.createTask(task.projectId, createDto)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            taskDao.upsert(responseEntity)
            val created = with(taskMapper) { responseEntity.toDomain() }
            platformHooks.scheduleAlarm(created)
            anchorNewTaskAtEnd(task.projectId, created.id)
            platformHooks.updateWidgets()
            NetworkResult.Success(created)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                val tempId = tempIdCounter.decrementAndGet()
                val localTask = task.copy(
                    id = tempId,
                    created = DateUtils.nowIso(),
                    updated = DateUtils.nowIso(),
                )
                val dto = with(taskMapper) { localTask.toDto() }
                val entity = with(taskMapper) { dto.toEntity() }
                taskDao.upsert(entity)
                queueTaskAction(tempId, "create", json.encodeToString(Task.serializer(), localTask))
                platformHooks.updateWidgets()
                NetworkResult.Success(localTask)
            } else {
                NetworkResult.Error(e.message ?: "Failed to create task")
            }
        }
    }

    override suspend fun update(task: Task): NetworkResult<Task> {
        val previous = taskDao.getByIdSync(task.id)
        return try {
            val dto = with(taskMapper) { task.toDto() }
            val optimisticEntity = with(taskMapper) { dto.toEntity() }
            taskDao.upsert(optimisticEntity)

            val responseDto = api.updateTask(task.id, dto)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            taskDao.upsert(responseEntity)

            val updated = with(taskMapper) { responseEntity.toDomain() }
            platformHooks.scheduleAlarm(updated)
            platformHooks.updateWidgets()
            NetworkResult.Success(updated)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueTaskAction(task.id, "update", json.encodeToString(Task.serializer(), task))
                platformHooks.updateWidgets()
                NetworkResult.Success(task)
            } else {
                previous?.let { taskDao.upsert(it) }
                NetworkResult.Error(e.message ?: "Failed to update task")
            }
        }
    }

    override suspend fun getByIds(ids: Set<Long>): List<Task> =
        taskDao.getByIds(ids.toList()).map { with(taskMapper) { it.toDomain() } }

    override suspend fun applyScheduleAction(task: Task): NetworkResult<Task> {
        val action = behaviorPrefsStore.getPrefs().first().scheduleAction
        val updated = when (action) {
            ScheduleAction.DUE_TODAY -> task.copy(dueDate = DateUtils.todayEndIso())
            ScheduleAction.PRIORITY_URGENT -> task.copy(priority = 4)
        }
        return update(updated)
    }

    override suspend fun moveToProject(taskId: Long, newProjectId: Long): NetworkResult<Unit> {
        val entity = taskDao.getByIdSync(taskId)
            ?: return NetworkResult.Error("Task $taskId not in local cache; cannot move")
        val task = with(taskMapper) { entity.toDomain() }
        if (task.projectId == newProjectId) return NetworkResult.Success(Unit)
        return when (val r = update(task.copy(projectId = newProjectId))) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Success(Unit)
        }
    }

    override suspend fun delete(taskId: Long): NetworkResult<Unit> {
        return try {
            platformHooks.cancelAlarm(taskId)
            taskDao.deleteById(taskId)
            api.deleteTask(taskId)
            platformHooks.updateWidgets()
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueTaskAction(taskId, "delete", "")
                platformHooks.updateWidgets()
                NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error(e.message ?: "Failed to delete task")
            }
        }
    }

    override suspend fun createSubtask(parentTaskId: Long, subtask: Task): NetworkResult<Task> {
        return try {
            val createDto = with(taskMapper) { subtask.toCreateDto() }
            val createdDto = api.createTask(subtask.projectId, createDto)
            val createdEntity = with(taskMapper) { createdDto.toEntity() }
            taskDao.upsert(createdEntity)

            api.createRelation(
                parentTaskId,
                com.rendyhd.vicu.data.remote.api.CreateRelationDto(
                    otherTaskId = createdDto.id,
                    relationKind = "subtask",
                ),
            )

            taskDao.getByIdSync(parentTaskId)?.let { parent ->
                taskDao.upsert(with(taskMapper) { parent.withRelatedTaskAdded("subtask", createdDto) })
            }

            NetworkResult.Success(with(taskMapper) { createdEntity.toDomain() })
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to create subtask")
        }
    }

    override suspend fun toggleSubtaskDone(parentTaskId: Long, subtask: Task): NetworkResult<Task> {
        val cached = taskDao.getByIdSync(subtask.id)
        val current = cached?.let { with(taskMapper) { it.toDomain() } } ?: subtask
        val toggled = current.copy(
            done = !current.done,
            doneAt = if (!current.done) DateUtils.nowIso() else "",
        )
        if (toggled.done) {
            platformHooks.playCompletionSound()
        }

        taskDao.getByIdSync(parentTaskId)?.let { parent ->
            taskDao.upsert(with(taskMapper) { parent.withRelatedTaskDone(subtask.id, toggled.done) })
        }
        cached?.let {
            taskDao.upsert(it.copy(done = toggled.done, doneAt = DateUtils.normalizeToUtc(toggled.doneAt)))
        }

        return try {
            val responseDto = api.updateTask(subtask.id, with(taskMapper) { toggled.toDto() })
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            taskDao.upsert(responseEntity)
            taskDao.getByIdSync(parentTaskId)?.let { parent ->
                taskDao.upsert(with(taskMapper) { parent.withRelatedTaskDone(subtask.id, responseDto.done) })
            }
            val result = with(taskMapper) { responseEntity.toDomain() }
            if (toggled.done) platformHooks.cancelAlarm(subtask.id) else platformHooks.scheduleAlarm(result)
            platformHooks.updateWidgets()
            NetworkResult.Success(result)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueTaskAction(subtask.id, "toggle_done", json.encodeToString(Task.serializer(), toggled))
                if (toggled.done) platformHooks.cancelAlarm(subtask.id)
                platformHooks.updateWidgets()
                NetworkResult.Success(toggled)
            } else {
                cached?.let { taskDao.upsert(it) }
                taskDao.getByIdSync(parentTaskId)?.let { parent ->
                    taskDao.upsert(with(taskMapper) { parent.withRelatedTaskDone(subtask.id, current.done) })
                }
                NetworkResult.Error(e.message ?: "Failed to update subtask")
            }
        }
    }

    override suspend fun createRelation(
        taskId: Long,
        otherTaskId: Long,
        relationKind: String,
    ): NetworkResult<Unit> {
        return try {
            api.createRelation(
                taskId,
                com.rendyhd.vicu.data.remote.api.CreateRelationDto(
                    otherTaskId = otherTaskId,
                    relationKind = relationKind,
                ),
            )
            val otherDto = try {
                api.getTask(otherTaskId).also { taskDao.upsert(with(taskMapper) { it.toEntity() }) }
            } catch (e: Exception) {
                null
            }
            val baseEntity = taskDao.getByIdSync(taskId)
            if (otherDto != null && baseEntity != null) {
                taskDao.upsert(with(taskMapper) { baseEntity.withRelatedTaskAdded(relationKind, otherDto) })
            } else {
                val dto = api.getTask(taskId)
                taskDao.upsert(with(taskMapper) { dto.toEntity() })
            }
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to create relation")
        }
    }

    override suspend fun deleteRelation(
        taskId: Long,
        relationKind: String,
        otherTaskId: Long,
    ): NetworkResult<Unit> {
        return try {
            api.deleteRelation(taskId, relationKind, otherTaskId)
            taskDao.getByIdSync(taskId)?.let { base ->
                taskDao.upsert(with(taskMapper) { base.withRelatedTaskRemoved(relationKind, otherTaskId) })
            }
            try {
                val otherDto = api.getTask(otherTaskId)
                taskDao.upsert(with(taskMapper) { otherDto.toEntity() })
            } catch (e: Exception) {
            }
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to delete relation")
        }
    }

    override suspend fun toggleDone(task: Task): NetworkResult<Task> {
        val toggled = task.copy(
            done = !task.done,
            doneAt = if (!task.done) DateUtils.nowIso() else "",
        )
        if (toggled.done) {
            platformHooks.playCompletionSound()
        }
        return try {
            val dto = with(taskMapper) { toggled.toDto() }
            val responseDto = api.updateTask(task.id, dto)
            val responseEntity = with(taskMapper) { responseDto.toEntity() }
            val result = with(taskMapper) { responseEntity.toDomain() }
            if (toggled.done) {
                platformHooks.cancelAlarm(task.id)
            } else {
                platformHooks.scheduleAlarm(result)
            }
            platformHooks.updateWidgets()
            NetworkResult.Success(result)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                val dto = with(taskMapper) { toggled.toDto() }
                val entity = with(taskMapper) { dto.toEntity() }
                taskDao.upsert(entity)
                queueTaskAction(task.id, "toggle_done", json.encodeToString(Task.serializer(), toggled))
                if (toggled.done) {
                    platformHooks.cancelAlarm(task.id)
                }
                platformHooks.updateWidgets()
                NetworkResult.Success(toggled)
            } else {
                NetworkResult.Error(e.message ?: "Failed to toggle task")
            }
        }
    }

    override suspend fun deleteLocalByIds(ids: Set<Long>) {
        if (ids.isNotEmpty()) taskDao.deleteByIds(ids.toList())
    }

    override suspend fun refreshAll(filters: Map<String, String>): NetworkResult<Unit> {
        Logger.d(TAG, "refreshAll() called with filters=$filters")
        return try {
            val allTasks = mutableListOf<com.rendyhd.vicu.data.remote.api.TaskDto>()
            var page = 1
            val maxPages = 100
            while (page <= maxPages) {
                val params = buildMap {
                    putAll(filters)
                    put("page", page.toString())
                    put("per_page", Constants.DEFAULT_PAGE_SIZE.toString())
                }
                Logger.d(TAG, "refreshAll() fetching page=$page params=$params")
                val batch = api.getAllTasks(params)
                Logger.d(TAG, "refreshAll() page=$page returned ${batch.size} tasks")
                allTasks.addAll(batch)
                if (batch.size < Constants.DEFAULT_PAGE_SIZE) break
                page++
            }
            val entities = allTasks.map { with(taskMapper) { it.toEntity() } }
            val pendingTaskIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
            val existingById = taskDao.getAllSync().associateBy { it.id }
            val safeEntities = entities.filter { it.id !in pendingTaskIds }
            val changed = safeEntities.filter { existingById[it.id] != it }
            taskDao.upsertAll(changed)
            var alarmsTouched = changed.any { e ->
                val old = existingById[e.id]
                old == null || old.remindersJson != e.remindersJson ||
                    old.dueDate != e.dueDate || old.done != e.done
            }
            if (filters.isEmpty()) {
                val serverTaskIds = allTasks.map { it.id }.toSet() + pendingTaskIds
                val deletedIds = existingById.keys - serverTaskIds
                if (deletedIds.isNotEmpty()) {
                    taskDao.deleteNotIn(serverTaskIds)
                    alarmsTouched = true
                }
            }
            if (alarmsTouched) platformHooks.rescheduleAlarms()
            platformHooks.updateWidgets()
            Logger.d(TAG, "refreshAll() SUCCESS: upserted ${changed.size} changed tasks (skipped ${entities.size - safeEntities.size} with pending actions)")
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "refreshAll() FAILED: ${e.message}", e)
            NetworkResult.Error(e.message ?: "Failed to refresh tasks")
        }
    }
}
