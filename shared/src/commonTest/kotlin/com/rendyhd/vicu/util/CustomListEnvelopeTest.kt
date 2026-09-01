package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.CustomListRevision
import com.rendyhd.vicu.domain.model.CustomListSyncDocumentV1
import com.rendyhd.vicu.domain.model.CustomListSyncOrder
import com.rendyhd.vicu.domain.model.CustomListSyncRecord
import com.rendyhd.vicu.domain.model.CustomListWire
import com.rendyhd.vicu.domain.model.CustomListWireFilter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomListEnvelopeTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun markerRoundTripsUnicodeAndSnakeCaseWireFields() {
        val document = CustomListEnvelope.fromLists(
            listOf(wire("one", "Überblick 🗂", destination = 42L)),
            deviceId = "desktop-a",
            now = 100L,
        )

        val marker = CustomListEnvelope.encode(document, json)
        val parsed = CustomListEnvelope.parse(marker, json)

        assertEquals("Überblick 🗂", parsed.document?.lists?.get("one")?.value?.name)
        assertTrue(json.encodeToString(document).contains("\"add_to_project_id\":42"))
        assertTrue(CustomListEnvelope.isAnyMetadataTask(marker))
        assertFalse(CustomListEnvelope.isAnyMetadataTask("ordinary notes"))
    }

    @Test
    fun futureVersionIsDetectedWithoutDecodingOrWritingIt() {
        val parsed = CustomListEnvelope.parse("<!-- vicu-custom-lists:v2:e30 -->", json)

        assertTrue(parsed.isCarrier)
        assertEquals(2, parsed.version)
        assertNull(parsed.document)
    }

    @Test
    fun firstMergePreservesUuidDistinctDuplicateNames() {
        val left = CustomListEnvelope.fromLists(listOf(wire("a", "Same")), "desktop", 10L)
        val right = CustomListEnvelope.fromLists(listOf(wire("b", "Same")), "android", 20L)

        val merged = CustomListEnvelope.merge(left, right)

        assertEquals(setOf("a", "b"), CustomListEnvelope.activeLists(merged).map { it.id }.toSet())
    }

    @Test
    fun newerTombstonePreventsOfflineResurrection() {
        val live = document("a", wire("a", "Live"), revision(100, 0, "offline"))
        val deleted = document("a", null, revision(101, 0, "phone"))

        val merged = CustomListEnvelope.merge(live, deleted)

        assertTrue(merged.lists.containsKey("a"))
        assertNull(merged.lists.getValue("a").value)
        assertTrue(CustomListEnvelope.activeLists(merged).isEmpty())
    }

    @Test
    fun orderIsIndependentAndMissingActiveIdsAreAppendedDeterministically() {
        val a = revision(10, 0, "a")
        val b = revision(20, 0, "b")
        val document = CustomListSyncDocumentV1(
            lists = mapOf(
                "second" to CustomListSyncRecord(wire("second", "Second"), b),
                "first" to CustomListSyncRecord(wire("first", "First"), a),
            ),
            order = CustomListSyncOrder(listOf("second", "deleted", "second"), revision(30, 0, "order")),
        )

        assertEquals(listOf("second", "first"), CustomListEnvelope.normalize(document).order.ids)
    }

    @Test
    fun revisionTieBreakUsesCounterThenDeviceId() {
        val base = revision(100, 1, "a")
        assertTrue(CustomListEnvelope.compareRevision(revision(100, 2, "a"), base) > 0)
        assertTrue(CustomListEnvelope.compareRevision(revision(100, 1, "z"), base) > 0)
        assertEquals(revision(100, 2, "device"), CustomListEnvelope.nextRevision(
            document("a", wire("a", "A"), revision(100, 1, "other")),
            "device",
            99L,
        ))
    }

    private fun wire(id: String, name: String, destination: Long = 0L) = CustomListWire(
        id = id,
        name = name,
        icon = "list",
        filter = CustomListWireFilter(addToProjectId = destination),
    )

    private fun revision(wall: Long, counter: Long, device: String) =
        CustomListRevision(wall, counter, device)

    private fun document(id: String, value: CustomListWire?, revision: CustomListRevision) =
        CustomListSyncDocumentV1(
            lists = mapOf(id to CustomListSyncRecord(value, revision)),
            order = CustomListSyncOrder(listOf(id), revision),
        )
}
