package com.rendyhd.vicu.util

const val RECURRENCE_MODE_FROM_DUE_DATE = 0
const val RECURRENCE_MODE_MONTHLY = 1
const val RECURRENCE_MODE_FROM_COMPLETION = 2

const val RECURRENCE_DAY_SECONDS = 86_400L
const val RECURRENCE_WEEK_SECONDS = 7L * RECURRENCE_DAY_SECONDS
const val RECURRENCE_YEAR_SECONDS = 365L * RECURRENCE_DAY_SECONDS

data class RecurrenceValue(
    val repeatAfter: Long,
    val repeatMode: Int,
) {
    val isRecurring: Boolean
        get() = repeatAfter > 0 || repeatMode == RECURRENCE_MODE_MONTHLY

    val fromCompletion: Boolean
        get() = repeatMode == RECURRENCE_MODE_FROM_COMPLETION

    companion object {
        val NONE = RecurrenceValue(
            repeatAfter = 0,
            repeatMode = RECURRENCE_MODE_FROM_DUE_DATE,
        )
    }
}

enum class RecurrencePreset {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    CUSTOM,
}

enum class RecurrenceCustomUnit(val seconds: Long) {
    DAYS(RECURRENCE_DAY_SECONDS),
    WEEKS(RECURRENCE_WEEK_SECONDS),
}

fun isRecurring(repeatAfter: Long, repeatMode: Int): Boolean =
    RecurrenceValue(repeatAfter, repeatMode).isRecurring

fun detectRecurrencePreset(value: RecurrenceValue): RecurrencePreset = when {
    !value.isRecurring -> RecurrencePreset.NONE
    value.repeatMode == RECURRENCE_MODE_MONTHLY && value.repeatAfter == 0L ->
        RecurrencePreset.MONTHLY
    value.repeatMode !in setOf(RECURRENCE_MODE_FROM_DUE_DATE, RECURRENCE_MODE_FROM_COMPLETION) ->
        RecurrencePreset.CUSTOM
    value.repeatAfter == RECURRENCE_DAY_SECONDS -> RecurrencePreset.DAILY
    value.repeatAfter == RECURRENCE_WEEK_SECONDS -> RecurrencePreset.WEEKLY
    value.repeatAfter == RECURRENCE_YEAR_SECONDS -> RecurrencePreset.YEARLY
    else -> RecurrencePreset.CUSTOM
}

fun recurrenceForPreset(
    preset: RecurrencePreset,
    fromCompletion: Boolean = false,
): RecurrenceValue = when (preset) {
    RecurrencePreset.NONE -> RecurrenceValue.NONE
    RecurrencePreset.DAILY -> fixedRecurrence(RECURRENCE_DAY_SECONDS, fromCompletion)
    RecurrencePreset.WEEKLY -> fixedRecurrence(RECURRENCE_WEEK_SECONDS, fromCompletion)
    RecurrencePreset.MONTHLY -> RecurrenceValue(0, RECURRENCE_MODE_MONTHLY)
    RecurrencePreset.YEARLY -> fixedRecurrence(RECURRENCE_YEAR_SECONDS, fromCompletion)
    RecurrencePreset.CUSTOM -> error("A custom recurrence requires an interval and unit")
}

fun customRecurrence(
    interval: Int,
    unit: RecurrenceCustomUnit,
    fromCompletion: Boolean = false,
): RecurrenceValue {
    require(interval in 1..365) { "Recurrence interval must be between 1 and 365" }
    return fixedRecurrence(interval.toLong() * unit.seconds, fromCompletion)
}

fun formatRecurrence(value: RecurrenceValue): String {
    if (!value.isRecurring) return ""
    if (value.repeatMode == RECURRENCE_MODE_MONTHLY) return "Every month"

    val seconds = value.repeatAfter
    val interval = when {
        seconds % RECURRENCE_YEAR_SECONDS == 0L -> {
            val years = seconds / RECURRENCE_YEAR_SECONDS
            if (years == 1L) "Every year" else "Every $years years"
        }
        seconds % RECURRENCE_WEEK_SECONDS == 0L -> {
            val weeks = seconds / RECURRENCE_WEEK_SECONDS
            if (weeks == 1L) "Every week" else "Every $weeks weeks"
        }
        seconds % RECURRENCE_DAY_SECONDS == 0L -> {
            val days = seconds / RECURRENCE_DAY_SECONDS
            if (days == 1L) "Every day" else "Every $days days"
        }
        seconds >= 3_600L && seconds % 3_600L == 0L -> {
            val hours = seconds / 3_600L
            if (hours == 1L) "Every hour" else "Every $hours hours"
        }
        else -> "Every $seconds seconds"
    }

    return if (value.fromCompletion) "$interval from completion" else interval
}

private fun fixedRecurrence(seconds: Long, fromCompletion: Boolean): RecurrenceValue =
    RecurrenceValue(
        repeatAfter = seconds,
        repeatMode = if (fromCompletion) {
            RECURRENCE_MODE_FROM_COMPLETION
        } else {
            RECURRENCE_MODE_FROM_DUE_DATE
        },
    )
