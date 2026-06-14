package com.rendyhd.vicu.data.repository

import android.content.Context
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.util.CompletionSoundPlayer
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.SyncScheduler

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidRepositoryHooks @Inject constructor(
    private val context: Context,
    private val alarmScheduler: AlarmScheduler,
    private val completionSoundPlayer: CompletionSoundPlayer,
) : PlatformRepositoryHooks {

    override fun triggerSync() {
        SyncScheduler.enqueueWhenOnline(context)
    }

    override fun updateWidgets() {
        WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
    }

    override fun playCompletionSound() {
        completionSoundPlayer.play()
    }

    override suspend fun scheduleAlarm(task: Task) {
        alarmScheduler.scheduleForTask(task)
    }

    override suspend fun cancelAlarm(taskId: Long) {
        alarmScheduler.cancelForTask(taskId)
    }

    override suspend fun rescheduleAlarms() {
        alarmScheduler.rescheduleAll()
    }
}
