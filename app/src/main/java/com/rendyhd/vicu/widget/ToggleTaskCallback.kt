package com.rendyhd.vicu.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.worker.SyncScheduler
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
        val taskRepository = get<TaskRepository>()
        val alarmScheduler = get<AlarmScheduler>()

        try {
            val entity = taskDao.getByIdSync(taskId) ?: return
            val task = with(taskMapper) { entity.toDomain() }
            taskRepository.toggleDone(task)

            // Immediately update this widget's Glance state (remove the task)
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

            // Cancel reminders + schedule background sync. The repository owns
            // the recursive completion and offline action queue.
            alarmScheduler.cancelForTask(taskId)
            SyncScheduler.enqueueImmediate(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle task $taskId", e)
        }

        // Refresh all widgets (covers other widget instances showing the same task)
        WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
    }
}
