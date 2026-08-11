package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrence
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.domain.model.RoutineSlot
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.minus

object RoutineScheduleEngine {
    fun occurrenceKey(routineId: String, date: String, slotId: String): String =
        "$routineId:$date:$slotId"

    fun isScheduledOn(
        schedule: RoutineSchedule,
        date: LocalDate,
        latestCompletionDate: LocalDate? = null,
    ): Boolean = when (schedule) {
        is RoutineSchedule.Calendar -> {
            val anchor = LocalDate.parse(schedule.anchorDate)
            if (date < anchor) {
                false
            } else {
                val weekdayMatches = schedule.weekdays.isEmpty() || date.dayOfWeek.isoDayNumber in schedule.weekdays
                val interval = schedule.weekInterval.coerceAtLeast(1)
                val anchorWeekStart = anchor.minus(anchor.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
                val dateWeekStart = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
                val weeksSinceAnchor = anchorWeekStart.daysUntil(dateWeekStart) / 7
                weekdayMatches && weeksSinceAnchor % interval == 0
            }
        }
        is RoutineSchedule.AfterCompletion -> {
            val due = latestCompletionDate
                ?.plus(schedule.intervalDays.coerceAtLeast(1), DateTimeUnit.DAY)
                ?: LocalDate.parse(schedule.firstDueDate)
            date == due
        }
    }

    fun occurrencesForDate(
        routine: Routine,
        date: LocalDate,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        today: LocalDate = date,
    ): List<RoutineOccurrence> {
        val definition = routine.definition
        if (definition.archived || date < LocalDate.parse(definition.activeFrom)) return emptyList()
        val latestCompletion = routine.payload.occurrences.values
            .asSequence()
            .filter { it.status == OccurrenceStatus.COMPLETED }
            .mapNotNull { runCatching { LocalDate.parse(it.scheduledDate) }.getOrNull() }
            .maxOrNull()
        val effectiveDate = when (val schedule = definition.schedule) {
            is RoutineSchedule.Calendar -> {
                if (!isScheduledOn(schedule, date, latestCompletion)) return emptyList()
                date
            }
            is RoutineSchedule.AfterCompletion -> {
                val due = latestCompletion
                    ?.plus(schedule.intervalDays.coerceAtLeast(1), DateTimeUnit.DAY)
                    ?: LocalDate.parse(schedule.firstDueDate)
                // Keep a completion-based chore visible once it is due, but only on the
                // current day; future schedule scans still get exactly one occurrence.
                if (date != due && !(date == today && due < today)) return emptyList()
                due
            }
        }

        return definition.slots.map { slot ->
            val key = occurrenceKey(definition.id, effectiveDate.toString(), slot.id)
            val record = routine.payload.occurrences[key]
            val status = record?.status ?: OccurrenceStatus.PENDING
            RoutineOccurrence(
                routine = routine,
                key = key,
                slot = slot,
                scheduledDate = effectiveDate.toString(),
                status = if (
                    status == OccurrenceStatus.PENDING &&
                    definition.kind == RoutineKind.HEALTH &&
                    effectiveDate < today
                ) {
                    OccurrenceStatus.NOT_LOGGED
                } else {
                    status
                },
                loggedAt = record?.loggedAt.orEmpty(),
                note = record?.note.orEmpty(),
                overdue = definition.kind == RoutineKind.CHORE &&
                    status == OccurrenceStatus.PENDING && effectiveDate < today,
            )
        }
    }

    fun recordFor(
        routine: Routine,
        date: LocalDate,
        slot: RoutineSlot,
        status: OccurrenceStatus,
        nowIso: String,
        deviceId: String,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        note: String = "",
    ): RoutineOccurrenceRecord {
        val key = occurrenceKey(routine.definition.id, date.toString(), slot.id)
        return RoutineOccurrenceRecord(
            key = key,
            routineId = routine.definition.id,
            slotId = slot.id,
            scheduledDate = date.toString(),
            scheduledMinutes = slot.reminderMinutes,
            timeZoneId = timeZone.id,
            status = status,
            loggedAt = if (status == OccurrenceStatus.COMPLETED) nowIso else "",
            modifiedAt = nowIso,
            modifiedBy = deviceId,
            note = note,
        )
    }

    fun merge(local: RoutinePayloadMergeInput, remote: RoutinePayloadMergeInput): RoutinePayloadMergeInput {
        val definition = if (isAtLeastAsRecent(
                local.definitionUpdatedAt,
                local.definitionUpdatedBy,
                remote.definitionUpdatedAt,
                remote.definitionUpdatedBy,
            )
        ) local.definition else remote.definition
        val merged = (remote.occurrences.keys + local.occurrences.keys).associateWith { key ->
            val left = local.occurrences[key]
            val right = remote.occurrences[key]
            when {
                left == null -> requireNotNull(right)
                right == null -> left
                isAtLeastAsRecent(left.modifiedAt, left.modifiedBy, right.modifiedAt, right.modifiedBy) -> left
                else -> right
            }
        }
        return RoutinePayloadMergeInput(definition, merged)
    }

    private fun isAtLeastAsRecent(
        leftTimestamp: String,
        leftDevice: String,
        rightTimestamp: String,
        rightDevice: String,
    ): Boolean = when {
        leftTimestamp > rightTimestamp -> true
        leftTimestamp < rightTimestamp -> false
        else -> leftDevice >= rightDevice
    }
}

data class RoutinePayloadMergeInput(
    val definition: com.rendyhd.vicu.domain.model.RoutineDefinition,
    val occurrences: Map<String, RoutineOccurrenceRecord>,
) {
    val definitionUpdatedAt: String get() = definition.updatedAt
    val definitionUpdatedBy: String get() = definition.updatedBy
}

private val kotlinx.datetime.DayOfWeek.isoDayNumber: Int
    get() = ordinal + 1
