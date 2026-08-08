package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.util.RecurrenceValue
import com.rendyhd.vicu.util.parser.ParsedRecurrence
import com.rendyhd.vicu.util.parser.recurrenceToVikunja

/** Resolves picker state over NLP state. A manual None is intentionally different from null. */
internal fun resolveTaskEntryRecurrence(
    manualRecurrence: RecurrenceValue?,
    parserEnabled: Boolean,
    parsedRecurrence: ParsedRecurrence?,
): RecurrenceValue {
    manualRecurrence?.let { return it }
    if (!parserEnabled || parsedRecurrence == null) return RecurrenceValue.NONE

    val parsed = recurrenceToVikunja(parsedRecurrence)
    return RecurrenceValue(parsed.repeatAfter, parsed.repeatMode)
}
