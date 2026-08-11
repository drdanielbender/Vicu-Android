package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.HealthSubtype
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineDefinition
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutinePeriod
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.domain.model.RoutineSlot
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoutineEnvelopeTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun envelopeRoundTripsWithoutTouchingVisibleDescription() {
        val payload = payload(updatedAt = "2026-08-11T08:00:00Z", updatedBy = "phone")
        val description = RoutineEnvelope.upsert("Visible notes", payload, json)

        val parsed = RoutineEnvelope.parse(description, json)

        assertTrue(parsed.isCarrier)
        assertEquals("Visible notes", parsed.body)
        assertEquals(payload, parsed.payload)
        assertEquals("Visible notes", RoutineEnvelope.strip(description))
    }

    @Test
    fun mergeKeepsNewestDefinitionAndNewestValuePerOccurrence() {
        val key = "routine-1:2026-08-11:morning"
        val local = payload("2026-08-11T10:00:00Z", "phone").copy(
            occurrences = mapOf(key to record(key, OccurrenceStatus.COMPLETED, "2026-08-11T09:00:00Z", "phone")),
        )
        val remote = payload("2026-08-11T08:00:00Z", "tablet").copy(
            occurrences = mapOf(key to record(key, OccurrenceStatus.SKIPPED, "2026-08-11T08:30:00Z", "tablet")),
        )

        val merged = RoutineEnvelope.mergePayload(local, remote)

        assertEquals("phone", merged.definition.updatedBy)
        assertEquals(OccurrenceStatus.COMPLETED, assertNotNull(merged.occurrences[key]).status)
    }

    private fun payload(updatedAt: String, updatedBy: String) = RoutinePayload(
        definition = RoutineDefinition(
            id = "routine-1",
            name = "Creatine",
            kind = RoutineKind.HEALTH,
            healthSubtype = HealthSubtype.SUPPLEMENT,
            amount = "5",
            unit = "g",
            schedule = RoutineSchedule.Calendar(anchorDate = "2026-08-01"),
            slots = listOf(RoutineSlot("morning", "Morning", RoutinePeriod.MORNING)),
            activeFrom = "2026-08-01",
            createdAt = "2026-08-01T08:00:00Z",
            updatedAt = updatedAt,
            updatedBy = updatedBy,
        ),
    )

    private fun record(key: String, status: OccurrenceStatus, modifiedAt: String, modifiedBy: String) =
        RoutineOccurrenceRecord(
            key = key,
            routineId = "routine-1",
            slotId = "morning",
            scheduledDate = "2026-08-11",
            scheduledMinutes = 480,
            timeZoneId = "Europe/Amsterdam",
            status = status,
            modifiedAt = modifiedAt,
            modifiedBy = modifiedBy,
        )
}
