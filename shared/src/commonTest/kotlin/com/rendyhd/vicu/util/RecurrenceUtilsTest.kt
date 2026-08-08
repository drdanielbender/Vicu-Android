package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecurrenceUtilsTest {

    @Test
    fun `none and calendar monthly have distinct recurring state`() {
        assertFalse(RecurrenceValue.NONE.isRecurring)
        assertTrue(RecurrenceValue(0, RECURRENCE_MODE_MONTHLY).isRecurring)
        assertEquals(
            RecurrencePreset.MONTHLY,
            detectRecurrencePreset(RecurrenceValue(0, RECURRENCE_MODE_MONTHLY)),
        )
    }

    @Test
    fun `preset values map to Vikunja fields`() {
        assertEquals(
            RecurrenceValue(RECURRENCE_DAY_SECONDS, RECURRENCE_MODE_FROM_DUE_DATE),
            recurrenceForPreset(RecurrencePreset.DAILY),
        )
        assertEquals(
            RecurrenceValue(RECURRENCE_WEEK_SECONDS, RECURRENCE_MODE_FROM_COMPLETION),
            recurrenceForPreset(RecurrencePreset.WEEKLY, fromCompletion = true),
        )
        assertEquals(
            RecurrenceValue(0, RECURRENCE_MODE_MONTHLY),
            recurrenceForPreset(RecurrencePreset.MONTHLY, fromCompletion = true),
        )
        assertEquals(
            RecurrenceValue(RECURRENCE_YEAR_SECONDS, RECURRENCE_MODE_FROM_DUE_DATE),
            recurrenceForPreset(RecurrencePreset.YEARLY),
        )
    }

    @Test
    fun `completion mode still detects fixed presets`() {
        assertEquals(
            RecurrencePreset.DAILY,
            detectRecurrencePreset(
                RecurrenceValue(RECURRENCE_DAY_SECONDS, RECURRENCE_MODE_FROM_COMPLETION),
            ),
        )
    }

    @Test
    fun `unsupported server interval remains custom`() {
        assertEquals(
            RecurrencePreset.CUSTOM,
            detectRecurrencePreset(RecurrenceValue(90_061L, RECURRENCE_MODE_FROM_DUE_DATE)),
        )
    }

    @Test
    fun `custom recurrence validates and converts day and week intervals`() {
        assertEquals(
            RecurrenceValue(3 * RECURRENCE_DAY_SECONDS, RECURRENCE_MODE_FROM_DUE_DATE),
            customRecurrence(3, RecurrenceCustomUnit.DAYS),
        )
        assertEquals(
            RecurrenceValue(2 * RECURRENCE_WEEK_SECONDS, RECURRENCE_MODE_FROM_COMPLETION),
            customRecurrence(2, RecurrenceCustomUnit.WEEKS, fromCompletion = true),
        )
        assertFailsWith<IllegalArgumentException> {
            customRecurrence(0, RecurrenceCustomUnit.DAYS)
        }
        assertFailsWith<IllegalArgumentException> {
            customRecurrence(366, RecurrenceCustomUnit.WEEKS)
        }
    }

    @Test
    fun `formatting never guesses fixed day intervals are months`() {
        assertEquals("Every 30 weeks", formatRecurrence(RecurrenceValue(30 * RECURRENCE_WEEK_SECONDS, 0)))
        assertEquals("Every 60 days", formatRecurrence(RecurrenceValue(60 * RECURRENCE_DAY_SECONDS, 0)))
        assertEquals("Every month", formatRecurrence(RecurrenceValue(0, RECURRENCE_MODE_MONTHLY)))
    }

    @Test
    fun `completion mode is explicit in the label`() {
        assertEquals(
            "Every 2 weeks from completion",
            formatRecurrence(
                RecurrenceValue(2 * RECURRENCE_WEEK_SECONDS, RECURRENCE_MODE_FROM_COMPLETION),
            ),
        )
    }
}
