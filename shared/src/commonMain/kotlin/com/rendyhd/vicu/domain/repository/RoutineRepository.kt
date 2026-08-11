package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow

data class RoutineParseIssue(
    val taskId: Long,
    val title: String,
    val message: String,
)

interface RoutineRepository {
    fun observeActive(): Flow<List<Routine>>
    fun observeArchived(): Flow<List<Routine>>
    fun observeRoutine(routineId: String): Flow<Routine?>
    fun observeDay(date: String): Flow<RoutineDay>
    fun observeHistory(routineId: String): Flow<List<RoutineOccurrenceRecord>>
    fun observeIssues(): Flow<List<RoutineParseIssue>>

    suspend fun create(draft: RoutineDraft): NetworkResult<Routine>
    suspend fun update(routineId: String, draft: RoutineDraft): NetworkResult<Routine>
    suspend fun setOccurrenceStatus(
        routineId: String,
        date: String,
        slotId: String,
        status: OccurrenceStatus,
        note: String = "",
    ): NetworkResult<Routine>
    suspend fun archive(routineId: String, archived: Boolean): NetworkResult<Routine>
    suspend fun deletePermanently(routineId: String): NetworkResult<Unit>
    suspend fun finalizeAndPrune(): NetworkResult<Unit>
    suspend fun exportCsv(): String
}
