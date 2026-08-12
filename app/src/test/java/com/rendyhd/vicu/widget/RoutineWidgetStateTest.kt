package com.rendyhd.vicu.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineWidgetStateTest {
    @Test
    fun statusUpdateChangesOnlyTheSelectedOccurrence() {
        val first = item("routine-1", "morning")
        val second = item("routine-2", "evening")
        val state = RoutineWidgetState(
            date = DATE,
            occurrences = listOf(first, second),
        )

        val updated = state.withStatus(
            routineId = first.routineId,
            date = first.scheduledDate,
            slotId = first.slotId,
            status = OccurrenceStatus.COMPLETED,
        )

        assertEquals(OccurrenceStatus.COMPLETED, updated.occurrences[0].status)
        assertEquals(OccurrenceStatus.PENDING, updated.occurrences[1].status)
        assertEquals(1, updated.completedCount)
    }

    @Test
    fun completedOccurrencesAreHiddenFromTheWidgetList() {
        val completed = item("routine-1", "morning", OccurrenceStatus.COMPLETED)
        val pending = item("routine-2", "evening")
        val state = RoutineWidgetState(DATE, listOf(completed, pending))

        assertEquals(listOf(pending), state.visibleOccurrences)
        assertFalse(state.isAllDone)
    }

    @Test
    fun allDoneRequiresAtLeastOneScheduledOccurrenceAndNoVisibleOccurrences() {
        assertFalse(RoutineWidgetState(date = DATE).isAllDone)

        val state = RoutineWidgetState(
            DATE,
            listOf(
                item("routine-1", "morning", OccurrenceStatus.COMPLETED),
                item("routine-2", "evening", OccurrenceStatus.COMPLETED),
            ),
        )

        assertTrue(state.isAllDone)
        assertTrue(state.visibleOccurrences.isEmpty())
    }

    @Test
    fun widgetStateSerializationRoundTrips() {
        val state = RoutineWidgetState(
            date = DATE,
            occurrences = listOf(item("routine-1", "morning")),
        )
        val prefs = mutablePreferencesOf(
            RoutineWidgetStateDefinition.KEY_STATE to
                RoutineWidgetStateDefinition.encodeState(state),
        )

        assertEquals(state, RoutineWidgetStateDefinition.parseState(prefs))
    }

    private fun item(
        routineId: String,
        slotId: String,
        status: OccurrenceStatus = OccurrenceStatus.PENDING,
    ) = RoutineWidgetItem(
        key = "$routineId:$DATE:$slotId",
        routineId = routineId,
        routineName = "Routine $routineId",
        scheduledDate = DATE,
        slotId = slotId,
        slotLabel = slotId,
        status = status,
    )

    private companion object {
        const val DATE = "2026-08-11"
    }
}
