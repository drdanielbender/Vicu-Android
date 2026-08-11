package com.rendyhd.vicu.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.widget.RoutineWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RoutineNotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    companion object {
        const val ACTION_COMPLETE = "com.rendyhd.vicu.routine.COMPLETE"
        const val ACTION_SKIP = "com.rendyhd.vicu.routine.SKIP"
    }

    private val repository: RoutineRepository by inject()
    private val scheduler: RoutineAlarmScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val routineId = intent.getStringExtra(RoutineAlarmReceiver.EXTRA_ROUTINE_ID) ?: return
        val date = intent.getStringExtra(RoutineAlarmReceiver.EXTRA_DATE) ?: return
        val slotId = intent.getStringExtra(RoutineAlarmReceiver.EXTRA_SLOT_ID) ?: return
        val key = intent.getStringExtra(RoutineAlarmReceiver.EXTRA_OCCURRENCE_KEY).orEmpty()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val status = if (intent.action == ACTION_SKIP) OccurrenceStatus.SKIPPED else OccurrenceStatus.COMPLETED
                repository.setOccurrenceStatus(routineId, date, slotId, status)
                scheduler.rescheduleAll()
                RoutineWidget().updateAllWidgets(context)
                NotificationManagerCompat.from(context).cancel("routine:$key".hashCode())
            } finally {
                pending.finish()
            }
        }
    }
}
