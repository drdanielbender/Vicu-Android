package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.TaskReminder
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object ReminderFormat {
    private val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    private val relativeLabels = mapOf(
        0L to "At due time",
        -300L to "5 minutes before",
        -900L to "15 minutes before",
        -3600L to "1 hour before",
        -86400L to "1 day before",
    )

    /** Absolute reminder time, in the user's local timezone. */
    fun formatAbsolute(dateStr: String): String {
        val instant = DateUtils.parseIsoDate(dateStr) ?: return dateStr
        val zoned = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${zoned.date.toJavaLocalDate().format(dateFmt)} ${zoned.time.toJavaLocalTime().format(timeFmt)}"
    }

    /** Human label for a reminder: an absolute time, or a relative-to-due description. */
    fun format(reminder: TaskReminder): String {
        if (reminder.reminder.isNotBlank() && !DateUtils.isNullDate(reminder.reminder)) {
            return formatAbsolute(reminder.reminder)
        }
        return relativeLabels[reminder.relativePeriod]
            ?: if (reminder.relativePeriod != 0L) "${reminder.relativePeriod / 60} min relative" else "At due time"
    }

    /** Summary for a row: the single reminder's label, or "N reminders" when there are several. */
    fun summary(reminders: List<TaskReminder>): String = when (reminders.size) {
        0 -> ""
        1 -> format(reminders.first())
        else -> "${reminders.size} reminders"
    }
}
