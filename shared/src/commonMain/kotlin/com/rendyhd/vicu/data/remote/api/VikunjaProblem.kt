package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class VikunjaProblemDto(
    val title: String = "",
    val status: Int = 0,
    val detail: String = "",
    val code: Long = 0,
    val errors: List<VikunjaErrorDetailDto>? = null,
)

@Serializable
data class VikunjaErrorDetailDto(
    val location: String = "",
    val message: String = "",
    val value: JsonElement? = null,
)

class VikunjaApiException(
    val httpStatus: Int,
    val problem: VikunjaProblemDto? = null,
) : Exception(
    problem?.detail?.takeIf { it.isNotBlank() }
        ?: problem?.title?.takeIf { it.isNotBlank() }
        ?: "Vikunja request failed (HTTP $httpStatus)",
)
