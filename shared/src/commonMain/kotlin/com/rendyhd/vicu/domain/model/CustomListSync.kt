package com.rendyhd.vicu.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CustomListWireFilter(
    @SerialName("project_ids") val projectIds: List<Long> = emptyList(),
    @SerialName("project_filter_mode") val projectFilterMode: String = "include",
    @SerialName("add_to_project_id") val addToProjectId: Long = 0L,
    @SerialName("sort_by") val sortBy: String = "due_date",
    @SerialName("order_by") val orderBy: String = "asc",
    @SerialName("due_date_filter") val dueDateFilter: String = "all",
    @SerialName("priority_filter") val priorityFilter: List<Int> = emptyList(),
    @SerialName("label_ids") val labelIds: List<Long> = emptyList(),
    @SerialName("include_done") val includeDone: Boolean = false,
    @SerialName("include_today_all_projects") val includeTodayAllProjects: Boolean = false,
)

@Serializable
data class CustomListWire(
    val id: String,
    val name: String,
    val icon: String = "",
    val filter: CustomListWireFilter,
)

@Serializable
data class CustomListRevision(
    @SerialName("wall_time_ms") val wallTimeMs: Long,
    val counter: Long,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class CustomListSyncRecord(
    val value: CustomListWire? = null,
    val revision: CustomListRevision,
)

@Serializable
data class CustomListSyncOrder(
    val ids: List<String> = emptyList(),
    val revision: CustomListRevision,
)

@Serializable
data class CustomListSyncDocumentV1(
    val version: Int = 1,
    val lists: Map<String, CustomListSyncRecord> = emptyMap(),
    val order: CustomListSyncOrder,
)

@Serializable
data class CustomListSyncLocalState(
    @SerialName("device_id") val deviceId: String,
    val document: CustomListSyncDocumentV1,
    val dirty: Boolean = false,
    @SerialName("carrier_task_id") val carrierTaskId: Long? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

sealed interface CustomListSyncStatus {
    data object Idle : CustomListSyncStatus
    data object Syncing : CustomListSyncStatus
    data object Pending : CustomListSyncStatus
    data class Offline(val message: String) : CustomListSyncStatus
    data class Error(val message: String) : CustomListSyncStatus
    data class UpdateRequired(val message: String = "Update Vicu to sync custom lists") : CustomListSyncStatus
}

fun CustomList.toWire(): CustomListWire = CustomListWire(
    id = id,
    name = name.trim(),
    icon = icon,
    filter = CustomListWireFilter(
        projectIds = filter.projectIds.distinct(),
        projectFilterMode = if (filter.projectFilterMode == "exclude") "exclude" else "include",
        addToProjectId = filter.addToProjectId,
        sortBy = filter.sortBy,
        orderBy = if (filter.orderBy == "desc") "desc" else "asc",
        dueDateFilter = filter.dueDateFilter,
        priorityFilter = filter.priorityFilter.distinct(),
        labelIds = filter.labelIds.distinct(),
        includeDone = filter.includeDone,
        includeTodayAllProjects = filter.includeTodayAllProjects,
    ),
)

fun CustomListWire.toDomain(): CustomList = CustomList(
    id = id,
    name = name.trim(),
    icon = icon,
    filter = CustomListFilter(
        projectIds = filter.projectIds.distinct(),
        projectFilterMode = if (filter.projectFilterMode == "exclude") "exclude" else "include",
        addToProjectId = filter.addToProjectId,
        sortBy = filter.sortBy,
        orderBy = if (filter.orderBy == "desc") "desc" else "asc",
        dueDateFilter = filter.dueDateFilter,
        priorityFilter = filter.priorityFilter.distinct(),
        labelIds = filter.labelIds.distinct(),
        includeDone = filter.includeDone,
        includeTodayAllProjects = filter.includeTodayAllProjects,
    ),
)
