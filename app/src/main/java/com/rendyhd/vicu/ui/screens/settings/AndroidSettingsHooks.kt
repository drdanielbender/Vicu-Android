package com.rendyhd.vicu.ui.screens.settings

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R
import com.rendyhd.vicu.notification.DailySummaryScheduler
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.SyncScheduler

class AndroidSettingsHooks(
    private val context: Context,
    private val dailySummaryScheduler: DailySummaryScheduler,
) : PlatformSettingsHooks {

    override fun updateWidgets() {
        WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
    }

    override fun scheduleSync(enabled: Boolean) {
        if (enabled) {
            SyncScheduler.enqueueWhenOnline(context)
        } else {
            SyncScheduler.cancel(context)
        }
    }

    override fun scheduleDailySummary(slot: String, enabled: Boolean, hour: Int, minute: Int) {
        dailySummaryScheduler.scheduleIfEnabled(slot, enabled, hour, minute)
    }

    override fun sendTestNotification(): String? {
        val tapIntent = Intent(context, MainActivity::class.java)
        val tapPending = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            context,
            NotificationChannelManager.CHANNEL_TASK_REMINDERS,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Test Notification")
            .setContentText("Task reminders are working!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(888_888, notification)
            "Test notification sent"
        } catch (e: SecurityException) {
            throw Exception("Notification permission not granted")
        }
    }

    override fun triggerImmediateSync() {
        SyncScheduler.enqueueImmediate(context)
    }
}
