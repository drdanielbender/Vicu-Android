package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.util.DateUtils
import platform.UserNotifications.*
import platform.Foundation.*
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

class IosRepositoryHooks(
    private val taskDao: TaskDao,
    private val taskMapper: TaskMapper
) : PlatformRepositoryHooks {

    override fun triggerSync() {
        // TODO: Implement iOS background sync trigger
    }

    override fun updateWidgets() {
        // TODO: Implement iOS Widget snapshot update
    }

    override fun playCompletionSound() {
        // TODO: Implement iOS completion sound player
    }

    override suspend fun scheduleAlarm(task: Task) {
        cancelAlarm(task.id)

        if (task.done || task.reminders.isEmpty()) return

        val center = UNUserNotificationCenter.currentNotificationCenter()
        task.reminders.forEachIndexed { index, reminder ->
            val triggerAtMillis = resolveReminderTime(reminder, task.dueDate)
            val now = NSDate().timeIntervalSince1970 * 1000.0
            if (triggerAtMillis != null && triggerAtMillis > now) {
                val delaySeconds = (triggerAtMillis - now) / 1000.0
                
                val content = UNMutableNotificationContent().apply {
                    setTitle(task.title)
                    setBody("Task reminder")
                    setSound(UNNotificationSound.defaultSound())
                    setUserInfo(mapOf("task_id" to task.id))
                }
                
                val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(delaySeconds, repeats = false)
                val identifier = alarmRequestIdentifier(task.id, index)
                val request = UNNotificationRequest.requestWithIdentifier(identifier, content, trigger)
                
                center.addNotificationRequest(request) { error ->
                    if (error != null) {
                        // Log or handle error
                    }
                }
            }
        }
    }

    override suspend fun cancelAlarm(taskId: Long) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val identifiers = (0 until 100).map { alarmRequestIdentifier(taskId, it) }
        center.removePendingNotificationRequestsWithIdentifiers(identifiers)
    }

    override suspend fun rescheduleAlarms() {
        try {
            val entities = taskDao.getAllWithReminders()
            entities.forEach { entity ->
                val task = with(taskMapper) { entity.toDomain() }
                scheduleAlarm(task)
            }
        } catch (e: Exception) {
            // Log or ignore
        }
    }

    private fun alarmRequestIdentifier(taskId: Long, index: Int): String {
        return "vicu_alarm_${taskId}_$index"
    }

    private fun resolveReminderTime(reminder: TaskReminder, dueDate: String): Long? {
        if (reminder.reminder.isNotBlank()) {
            val instant = DateUtils.parseIsoDate(reminder.reminder)
            if (instant != null) return instant.toEpochMilliseconds()
        }

        if (reminder.relativePeriod != 0L || reminder.relativeTo.isNotBlank()) {
            val baseDate = DateUtils.parseIsoDate(dueDate)
            if (baseDate != null) {
                val triggerInstant = baseDate + reminder.relativePeriod.seconds
                return triggerInstant.toEpochMilliseconds()
            }
        }

        return null
    }
}
