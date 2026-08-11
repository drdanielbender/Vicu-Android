package com.rendyhd.vicu.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "routine_occurrence_archive",
    primaryKeys = ["routineId", "occurrenceKey"],
)
data class RoutineOccurrenceArchiveEntity(
    val routineId: String,
    val occurrenceKey: String,
    val slotId: String,
    val scheduledDate: String,
    val scheduledMinutes: Int,
    val timeZoneId: String,
    val status: String,
    val loggedAt: String,
    val modifiedAt: String,
    val modifiedBy: String,
    val note: String,
)
