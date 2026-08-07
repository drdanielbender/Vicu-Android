package com.rendyhd.vicu.data.remote.api

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MergePatches {
    val taskReadOnlyFields = setOf(
        "\$schema",
        "id",
        "created",
        "updated",
        "created_by",
        "done_at",
        "identifier",
        "index",
        "labels",
        "attachments",
        "related_tasks",
        "position",
        "kanban_position",
        "max_permission",
    )

    fun task(previous: Task?, current: Task): JsonObject = buildJsonObject {
        putChanged("title", previous?.title, current.title)
        putChanged("description", previous?.description, current.description)
        putChanged("done", previous?.done, current.done)
        putDateChanged("due_date", previous?.dueDate, current.dueDate)
        putChanged("priority", previous?.priority, current.priority)
        putChanged("project_id", previous?.projectId, current.projectId)
        putChanged("repeat_after", previous?.repeatAfter, current.repeatAfter)
        putChanged("repeat_mode", previous?.repeatMode, current.repeatMode)
        putDateChanged("start_date", previous?.startDate, current.startDate)
        putDateChanged("end_date", previous?.endDate, current.endDate)
        putChanged("hex_color", previous?.hexColor?.removePrefix("#"), current.hexColor.removePrefix("#"))
        putChanged("percent_done", previous?.percentDone, current.percentDone)
        putChanged("bucket_id", previous?.bucketId, current.bucketId)
        putChanged("is_favorite", previous?.isFavorite, current.isFavorite)
        if (previous == null || previous.reminders != current.reminders) {
            put(
                "reminders",
                JsonArray(
                    current.reminders.map { reminder ->
                        buildJsonObject {
                            put("reminder", reminder.reminder)
                            put("relative_period", reminder.relativePeriod)
                            put("relative_to", reminder.relativeTo)
                        }
                    },
                ),
            )
        }
    }

    fun taskDone(done: Boolean): JsonObject = buildJsonObject {
        put("done", done)
    }

    fun project(previous: Project?, current: Project): JsonObject = buildJsonObject {
        putChanged("title", previous?.title, current.title)
        putChanged("description", previous?.description, current.description)
        putChanged("hex_color", previous?.hexColor?.removePrefix("#"), current.hexColor.removePrefix("#"))
        putChanged("parent_project_id", previous?.parentProjectId, current.parentProjectId)
        putChanged("position", previous?.position, current.position)
        putChanged("is_archived", previous?.isArchived, current.isArchived)
        putChanged("is_favorite", previous?.isFavorite, current.isFavorite)
        putChanged("identifier", previous?.identifier, current.identifier)
    }

    fun label(previous: Label?, current: Label): JsonObject = buildJsonObject {
        putChanged("title", previous?.title, current.title)
        putChanged("description", previous?.description, current.description)
        putChanged("hex_color", previous?.hexColor?.removePrefix("#"), current.hexColor.removePrefix("#"))
    }

    fun merge(first: JsonObject, second: JsonObject): JsonObject =
        JsonObject(first + second)

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChanged(
        name: String,
        previous: String?,
        current: String,
    ) {
        if (previous == null || previous != current) put(name, current)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChanged(
        name: String,
        previous: Boolean?,
        current: Boolean,
    ) {
        if (previous == null || previous != current) put(name, current)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChanged(
        name: String,
        previous: Int?,
        current: Int,
    ) {
        if (previous == null || previous != current) put(name, current)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChanged(
        name: String,
        previous: Long?,
        current: Long,
    ) {
        if (previous == null || previous != current) put(name, current)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChanged(
        name: String,
        previous: Double?,
        current: Double,
    ) {
        if (previous == null || previous != current) put(name, current)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putDateChanged(
        name: String,
        previous: String?,
        current: String,
    ) {
        if (previous == null || previous != current) {
            val value: JsonElement =
                if (current.isBlank() || DateUtils.isNullDate(current)) JsonNull else JsonPrimitive(current)
            put(name, value)
        }
    }
}
