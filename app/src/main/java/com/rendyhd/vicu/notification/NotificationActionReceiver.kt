package com.rendyhd.vicu.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        private const val TAG = "NotifActionReceiver"
        const val ACTION_COMPLETE = "com.rendyhd.vicu.ACTION_COMPLETE"
        const val ACTION_SNOOZE = "com.rendyhd.vicu.ACTION_SNOOZE"
    }

    private val taskDao: TaskDao by inject()
    private val taskMapper: TaskMapper by inject()
    private val api: VikunjaApiService by inject()
    private val alarmScheduler: AlarmScheduler by inject()
    private val pendingActionDao: PendingActionDao by inject()
    private val json: Json by inject()
    private val baseUrlHolder: BaseUrlHolder by inject()
    private val authManager: AuthManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(AlarmReceiver.EXTRA_TASK_ID, 0L)
        if (taskId == 0L) return

        // Dismiss the notification
        NotificationManagerCompat.from(context).cancel(taskId.toInt())

        when (intent.action) {
            ACTION_COMPLETE -> handleComplete(context, taskId)
            ACTION_SNOOZE -> handleSnooze(context, taskId, intent)
        }
    }

    private fun handleComplete(context: Context, taskId: Long) {
        Log.d(TAG, "Completing task $taskId")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Ensure network layer is initialized (cold start after process death)
                baseUrlHolder.ensureInitialized()
                authManager.ensureInitializedAndGetToken()

                val entity = taskDao.getByIdSync(taskId) ?: return@launch
                val task = with(taskMapper) { entity.toDomain() }
                val toggled = task.copy(done = true, doneAt = DateUtils.nowIso())
                val dto = with(taskMapper) { toggled.toDto() }
                val patch = MergePatches.taskDone(done = true)
                val queuedPayload = if (taskId < 0L) {
                    json.encodeToString(Task.serializer(), toggled)
                } else {
                    json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        patch,
                    )
                }

                // Optimistic local update
                val optimisticEntity = with(taskMapper) { dto.toEntity() }
                taskDao.upsert(optimisticEntity)

                // Remote update
                if (taskId < 0L) {
                    val action = PendingActionEntity(
                        entityType = "task",
                        entityId = taskId,
                        actionType = "toggle_done",
                        payload = queuedPayload,
                        createdAt = DateUtils.nowIso(),
                        updatedAt = DateUtils.nowIso(),
                    )
                    pendingActionDao.queueTaskActionMerging(action)
                    SyncScheduler.enqueueWhenOnline(context)
                } else {
                    try {
                        val responseDto = api.updateTask(taskId, patch)
                        val responseEntity = with(taskMapper) { responseDto.toEntity() }
                        taskDao.upsert(responseEntity)
                    } catch (e: Exception) {
                        Log.w(TAG, "Remote complete failed for task $taskId, queuing for sync", e)
                        val action = PendingActionEntity(
                            entityType = "task",
                            entityId = taskId,
                            actionType = "toggle_done",
                            payload = queuedPayload,
                            createdAt = DateUtils.nowIso(),
                            updatedAt = DateUtils.nowIso(),
                        )
                        pendingActionDao.queueTaskActionMerging(action)
                        SyncScheduler.enqueueWhenOnline(context)
                    }
                }

                alarmScheduler.cancelForTask(taskId)
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete task $taskId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleSnooze(context: Context, taskId: Long, intent: Intent) {
        val taskTitle = intent.getStringExtra(AlarmReceiver.EXTRA_TASK_TITLE) ?: "Task Reminder"
        val triggerAt = System.currentTimeMillis() + 15 * 60 * 1000
        Log.d(TAG, "Snoozing task $taskId for 15 minutes")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                alarmScheduler.scheduleSnooze(taskId, taskTitle, triggerAt)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
