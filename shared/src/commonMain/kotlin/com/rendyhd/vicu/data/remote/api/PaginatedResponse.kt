package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PaginatedResponse<T>(
    @SerialName("items") private val rawItems: List<T>? = null,
    val total: Long = 0,
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
) {
    val items: List<T>
        get() = rawItems.orEmpty()
}
