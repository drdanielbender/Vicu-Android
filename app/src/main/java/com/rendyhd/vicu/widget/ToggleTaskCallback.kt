package com.rendyhd.vicu.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.worker.SyncScheduler
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class ToggleTaskCallback : ActionCallback, KoinComponent {

    companion object {
        private const val TAG = "ToggleTaskCallback"
        val TaskIdKey = ActionParameters.Key<Long>("task_id")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[TaskIdKey] ?: return
        Log.d(TAG, "Toggling task $taskId from widget")

        val taskDao = get<TaskDao>()
        val taskMapper = get<TaskMapper>()
        val alarmScheduler = get<AlarmScheduler>()
        val pendingActionDao = get<PendingActionDao>()
        val json = get<Json>()

        try {
            val entity = taskDao.getByIdSync(taskId) ?: return
            val task = with(taskMapper) { entity.toDomain() }
            val toggled = task.copy(done = true, doneAt = DateUtils.nowIso())
            val patch = MergePatches.taskDone(done = true)
            val queuedPayload = if (taskId < 0L) {
                json.encodeToString(Task.serializer(), toggled)
            } else {
                json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    patch,
                )
            }

            // 1. Optimistic local update (Room)
            val dto = with(taskMapper) { toggled.toDto() }
            val optimisticEntity = with(taskMapper) { dto.toEntity() }
            taskDao.upsert(optimisticEntity)

            // 2. Immediately update this widget's Glance state (remove the task)
            updateAppWidgetState(
                context,
                TaskWidgetStateDefinition,
                glanceId,
            ) { prefs ->
                val state = TaskWidgetStateDefinition.parseState(prefs)
                val updatedState = state.copy(
                    tasks = state.tasks.filter { it.id != taskId },
                    totalCount = (state.totalCount - 1).coerceAtLeast(0),
                )
                prefs.toMutablePreferences().apply {
                    this[TaskWidgetStateDefinition.KEY_STATE] =
                        TaskWidgetStateDefinition.encodeState(updatedState)
                }
            }
            TaskListWidget().update(context, glanceId)

            // 3. Queue pending action for background sync
            val action = PendingActionEntity(
                entityType = "task",
                entityId = taskId,
                actionType = "toggle_done",
                payload = queuedPayload,
                createdAt = DateUtils.nowIso(),
                updatedAt = DateUtils.nowIso(),
            )
            pendingActionDao.queueTaskActionMerging(action)

            // 4. Cancel reminders + schedule background sync
            alarmScheduler.cancelForTask(taskId)
            SyncScheduler.enqueueImmediate(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle task $taskId", e)
        }

        // Refresh all widgets (covers other widget instances showing the same task)
        WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
    }
}
