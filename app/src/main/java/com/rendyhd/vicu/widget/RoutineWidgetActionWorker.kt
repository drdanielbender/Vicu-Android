package com.rendyhd.vicu.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.CancellationException

class RoutineWidgetActionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val repository: RoutineRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val routineId = inputData.getString(KEY_ROUTINE_ID) ?: return Result.failure()
        val date = inputData.getString(KEY_DATE) ?: return Result.failure()
        val slotId = inputData.getString(KEY_SLOT_ID) ?: return Result.failure()
        val status = inputData.getString(KEY_STATUS)
            ?.let { raw -> runCatching { OccurrenceStatus.valueOf(raw) }.getOrNull() }
            ?: return Result.failure()

        return try {
            val result = repository.setOccurrenceStatus(routineId, date, slotId, status)
            RoutineWidget().updateAllWidgets(applicationContext)
            when (result) {
                is NetworkResult.Success -> Result.success()
                is NetworkResult.Error -> Result.failure()
                NetworkResult.Loading -> Result.retry()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            runCatching { RoutineWidget().updateAllWidgets(applicationContext) }
            Result.retry()
        }
    }

    companion object {
        const val KEY_ROUTINE_ID = "routine_id"
        const val KEY_DATE = "date"
        const val KEY_SLOT_ID = "slot_id"
        const val KEY_STATUS = "status"
    }
}

object RoutineWidgetActionScheduler {
    private const val WORK_NAME_PREFIX = "routine_widget_action"

    fun enqueue(
        context: Context,
        routineId: String,
        date: String,
        slotId: String,
        status: OccurrenceStatus,
    ) {
        val request = OneTimeWorkRequestBuilder<RoutineWidgetActionWorker>()
            .setInputData(
                workDataOf(
                    RoutineWidgetActionWorker.KEY_ROUTINE_ID to routineId,
                    RoutineWidgetActionWorker.KEY_DATE to date,
                    RoutineWidgetActionWorker.KEY_SLOT_ID to slotId,
                    RoutineWidgetActionWorker.KEY_STATUS to status.name,
                ),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_NAME_PREFIX:$routineId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}
