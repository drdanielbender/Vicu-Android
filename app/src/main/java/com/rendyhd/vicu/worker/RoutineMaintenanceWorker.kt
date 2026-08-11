package com.rendyhd.vicu.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.notification.RoutineAlarmScheduler
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.widget.RoutineWidget
import java.util.concurrent.TimeUnit

class RoutineMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: RoutineRepository,
    private val alarmScheduler: RoutineAlarmScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        when (repository.finalizeAndPrune()) {
            is NetworkResult.Error -> Result.retry()
            else -> {
                alarmScheduler.rescheduleAll()
                RoutineWidget().updateAllWidgets(applicationContext)
                Result.success()
            }
        }
    } catch (_: Exception) {
        Result.retry()
    }
}

object RoutineMaintenanceScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RoutineMaintenanceWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "routine_daily_maintenance",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
