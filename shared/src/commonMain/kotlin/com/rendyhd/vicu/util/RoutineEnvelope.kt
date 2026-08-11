@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutineSchedule
import kotlin.io.encoding.Base64
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

object RoutineEnvelope {
    const val CURRENT_VERSION = 1
    private const val MAX_DECODED_BYTES = 512 * 1024
    private val markerRegex = Regex(
        """<!--\s*vicu-routine:v(\d+):([A-Za-z0-9_-]+={0,2})\s*-->""",
    )
    private val anyMarkerRegex = Regex(
        """<!--\s*vicu-routine:[\s\S]*?-->""",
    )

    data class Parsed(
        val isCarrier: Boolean,
        val body: String,
        val rawMarker: String = "",
        val payload: RoutinePayload? = null,
        val error: String? = null,
        val version: Int? = null,
    )

    fun hasMarker(description: String?): Boolean =
        !description.isNullOrEmpty() && anyMarkerRegex.containsMatchIn(description)

    fun extractMarker(description: String?): String =
        if (description.isNullOrEmpty()) "" else anyMarkerRegex.find(description)?.value.orEmpty()

    fun parse(description: String?, json: Json): Parsed {
        if (description.isNullOrEmpty()) return Parsed(false, "")
        val any = anyMarkerRegex.find(description) ?: return Parsed(false, description)
        val body = description.removeRange(any.range).trimEnd()
        val marker = markerRegex.matchEntire(any.value)
            ?: return Parsed(true, body, any.value, error = "Malformed routine metadata")
        val version = marker.groupValues[1].toIntOrNull()
            ?: return Parsed(true, body, any.value, error = "Invalid routine version")
        if (version != CURRENT_VERSION) {
            return Parsed(
                isCarrier = true,
                body = body,
                rawMarker = any.value,
                error = "Routine metadata version $version is not supported",
                version = version,
            )
        }
        return runCatching {
            val encoded = marker.groupValues[2]
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            val bytes = Base64.UrlSafe.decode(padded)
            require(bytes.size <= MAX_DECODED_BYTES) { "Routine metadata is too large" }
            val payload = json.decodeFromString<RoutinePayload>(bytes.decodeToString())
            require(payload.version == CURRENT_VERSION) { "Routine payload version mismatch" }
            require(payload.definition.id.isNotBlank()) { "Routine id is missing" }
            require(payload.definition.name.isNotBlank()) { "Routine name is missing" }
            require(payload.definition.slots.isNotEmpty()) { "Routine has no slots" }
            require(payload.definition.slots.all { it.id.isNotBlank() }) { "Routine slot id is missing" }
            require(payload.definition.slots.map { it.id }.distinct().size == payload.definition.slots.size) {
                "Routine slot ids must be unique"
            }
            LocalDate.parse(payload.definition.activeFrom)
            when (val schedule = payload.definition.schedule) {
                is RoutineSchedule.Calendar -> LocalDate.parse(schedule.anchorDate)
                is RoutineSchedule.AfterCompletion -> {
                    require(schedule.intervalDays > 0) { "Routine interval must be positive" }
                    LocalDate.parse(schedule.firstDueDate)
                }
            }
            Parsed(true, body, any.value, payload, version = version)
        }.getOrElse { error ->
            Parsed(
                isCarrier = true,
                body = body,
                rawMarker = any.value,
                error = error.message ?: "Cannot decode routine metadata",
                version = version,
            )
        }
    }

    fun encode(payload: RoutinePayload, json: Json): String {
        val bytes = json.encodeToString(RoutinePayload.serializer(), payload).encodeToByteArray()
        require(bytes.size <= MAX_DECODED_BYTES) { "Routine metadata is too large" }
        val encoded = Base64.UrlSafe.encode(bytes).trimEnd('=')
        return "<!-- vicu-routine:v$CURRENT_VERSION:$encoded -->"
    }

    fun upsert(descriptionBody: String, payload: RoutinePayload, json: Json): String {
        val cleanBody = anyMarkerRegex.replace(descriptionBody, "").trimEnd()
        val marker = encode(payload, json)
        return if (cleanBody.isEmpty()) marker else "$cleanBody\n$marker"
    }

    fun strip(description: String?): String {
        if (description.isNullOrEmpty()) return ""
        return anyMarkerRegex.replace(description, "").trim()
    }

    /** Field-wise last-write-wins merge used before a carrier is sent to Vikunja. */
    fun mergePayload(local: RoutinePayload, remote: RoutinePayload): RoutinePayload {
        val merged = RoutineScheduleEngine.merge(
            RoutinePayloadMergeInput(local.definition, local.occurrences),
            RoutinePayloadMergeInput(remote.definition, remote.occurrences),
        )
        return local.copy(
            version = maxOf(local.version, remote.version),
            definition = merged.definition,
            occurrences = merged.occurrences,
            prunedBefore = maxOf(local.prunedBefore, remote.prunedBefore),
        )
    }
}
