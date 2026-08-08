package com.rendyhd.vicu.util.parser

import com.rendyhd.vicu.util.RECURRENCE_DAY_SECONDS
import com.rendyhd.vicu.util.RECURRENCE_MODE_FROM_DUE_DATE
import com.rendyhd.vicu.util.RECURRENCE_MODE_MONTHLY
import com.rendyhd.vicu.util.RECURRENCE_WEEK_SECONDS
import com.rendyhd.vicu.util.RECURRENCE_YEAR_SECONDS

data class VikunjaRecurrence(
    val repeatAfter: Long,
    val repeatMode: Int,
)

fun recurrenceToVikunja(r: ParsedRecurrence): VikunjaRecurrence {
    return when (r.unit) {
        RecurrenceUnit.DAY -> VikunjaRecurrence(
            r.interval.toLong() * RECURRENCE_DAY_SECONDS,
            RECURRENCE_MODE_FROM_DUE_DATE,
        )
        RecurrenceUnit.WEEK -> VikunjaRecurrence(
            r.interval.toLong() * RECURRENCE_WEEK_SECONDS,
            RECURRENCE_MODE_FROM_DUE_DATE,
        )
        RecurrenceUnit.MONTH -> {
            if (r.interval == 1) {
                VikunjaRecurrence(0, RECURRENCE_MODE_MONTHLY)
            } else {
                // Multi-month: approximate with days
                VikunjaRecurrence(
                    r.interval.toLong() * 30L * RECURRENCE_DAY_SECONDS,
                    RECURRENCE_MODE_FROM_DUE_DATE,
                )
            }
        }
        RecurrenceUnit.YEAR -> VikunjaRecurrence(
            r.interval.toLong() * RECURRENCE_YEAR_SECONDS,
            RECURRENCE_MODE_FROM_DUE_DATE,
        )
    }
}
