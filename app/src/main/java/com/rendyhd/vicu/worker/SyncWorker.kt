package com.rendyhd.vicu.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "SyncWorker started delegation to SyncEngine")
        return try {
            val success = syncEngine.performSync()
            if (success) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker execution failed", e)
            Result.retry()
        }
    }
}
