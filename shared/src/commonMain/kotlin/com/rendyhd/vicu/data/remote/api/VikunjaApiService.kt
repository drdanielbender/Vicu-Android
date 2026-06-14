package com.rendyhd.vicu.data.remote.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

data class KtorResponse<T>(
    val code: Int,
    val isSuccessful: Boolean,
    val body: T?,
    val headers: Map<String, List<String>>
) {
    fun code(): Int = code
    fun body(): T? = body
}

class VikunjaApiService(private val client: HttpClient) {

    // Tasks
    suspend fun getAllTasks(filters: Map<String, String> = emptyMap()): List<TaskDto> {
        return client.get("tasks") {
            filters.forEach { (k, v) -> parameter(k, v) }
        }.body()
    }

    suspend fun getTask(id: Long): TaskDto {
        return client.get("tasks/$id").body()
    }

    suspend fun createTask(projectId: Long, task: CreateTaskDto): TaskDto {
        return client.put("projects/$projectId/tasks") {
            contentType(ContentType.Application.Json)
            setBody(task)
        }.body()
    }

    suspend fun updateTask(id: Long, task: TaskDto): TaskDto {
        return client.post("tasks/$id") {
            contentType(ContentType.Application.Json)
            setBody(task)
        }.body()
    }

    suspend fun deleteTask(id: Long) {
        client.delete("tasks/$id")
    }

    // Projects
    suspend fun getAllProjects(): List<ProjectDto> {
        return client.get("projects").body()
    }

    suspend fun getProject(id: Long): ProjectDto {
        return client.get("projects/$id").body()
    }

    suspend fun createProject(project: CreateProjectDto): ProjectDto {
        return client.put("projects") {
            contentType(ContentType.Application.Json)
            setBody(project)
        }.body()
    }

    suspend fun updateProject(id: Long, project: UpdateProjectDto): ProjectDto {
        return client.post("projects/$id") {
            contentType(ContentType.Application.Json)
            setBody(project)
        }.body()
    }

    suspend fun deleteProject(id: Long) {
        client.delete("projects/$id")
    }

    // Labels
    suspend fun getAllLabels(): List<LabelDto> {
        return client.get("labels").body()
    }

    suspend fun getLabel(id: Long): LabelDto {
        return client.get("labels/$id").body()
    }

    suspend fun createLabel(label: LabelDto): LabelDto {
        return client.put("labels") {
            contentType(ContentType.Application.Json)
            setBody(label)
        }.body()
    }

    suspend fun updateLabel(id: Long, label: LabelDto): LabelDto {
        return client.post("labels/$id") {
            contentType(ContentType.Application.Json)
            setBody(label)
        }.body()
    }

    suspend fun deleteLabel(id: Long) {
        client.delete("labels/$id")
    }

    // Task Labels
    suspend fun addLabelToTask(taskId: Long, body: LabelTaskDto) {
        client.put("tasks/$taskId/labels") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun removeLabelFromTask(taskId: Long, labelId: Long) {
        client.delete("tasks/$taskId/labels/$labelId")
    }

    // Attachments
    suspend fun getAttachments(taskId: Long): List<AttachmentDto> {
        return client.get("tasks/$taskId/attachments").body()
    }

    suspend fun uploadAttachment(taskId: Long, fileName: String, content: ByteArray): AttachmentDto {
        return client.put("tasks/$taskId/attachments") {
            setBody(MultiPartFormDataContent(
                formData {
                    append("files", content, Headers.build {
                        append(HttpHeaders.ContentType, "application/octet-stream")
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    })
                }
            ))
        }.body()
    }

    suspend fun downloadAttachment(taskId: Long, attId: Long): ByteArray {
        return client.get("tasks/$taskId/attachments/$attId").body()
    }

    suspend fun deleteAttachment(taskId: Long, attId: Long) {
        client.delete("tasks/$taskId/attachments/$attId")
    }

    // Relations
    suspend fun createRelation(taskId: Long, body: CreateRelationDto) {
        client.put("tasks/$taskId/relations") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun deleteRelation(taskId: Long, relationKind: String, otherTaskId: Long) {
        client.delete("tasks/$taskId/relations/$relationKind/$otherTaskId")
    }

    // Views
    suspend fun getProjectViews(projectId: Long): List<ProjectViewDto> {
        return client.get("projects/$projectId/views").body()
    }

    suspend fun getViewTasks(projectId: Long, viewId: Long, filters: Map<String, String> = emptyMap()): List<TaskDto> {
        return client.get("projects/$projectId/views/$viewId/tasks") {
            filters.forEach { (k, v) -> parameter(k, v) }
        }.body()
    }

    // Position
    suspend fun updateTaskPosition(taskId: Long, body: TaskPositionDto) {
        client.post("tasks/$taskId/position") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    // Auth
    suspend fun login(body: LoginRequestDto): KtorResponse<TokenResponseDto> {
        val response = client.post("login") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return response.toKtorResponse()
    }

    suspend fun getServerInfo(): ServerInfoDto {
        return client.get("info").body()
    }

    suspend fun getCurrentUser(): UserDto {
        return client.get("user").body()
    }

    suspend fun getOidcProviders(): List<OidcProviderDto> {
        return client.get("auth/openid/callback").body()
    }

    suspend fun exchangeOidcToken(providerKey: String, body: OidcCallbackDto): KtorResponse<TokenResponseDto> {
        val response = client.post("auth/openid/$providerKey/callback") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return response.toKtorResponse()
    }

    suspend fun createApiToken(body: ApiTokenRequestDto): ApiTokenResponseDto {
        return client.put("tokens") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    suspend fun listApiTokens(page: Int = 1, perPage: Int = 100): List<ApiTokenDto> {
        return client.get("tokens") {
            parameter("page", page)
            parameter("per_page", perPage)
        }.body()
    }

    suspend fun deleteApiToken(id: Long) {
        client.delete("tokens/$id")
    }

    suspend fun getApiTokenRoutes(): Map<String, Map<String, RouteDetailDto>> {
        return client.get("routes").body()
    }

    suspend fun renewTokenLegacy(): TokenResponseDto {
        return client.post("user/token").body()
    }

    suspend fun refreshToken(cookie: String): KtorResponse<TokenResponseDto> {
        val response = client.post("user/token/refresh") {
            header(HttpHeaders.Cookie, cookie)
        }
        return response.toKtorResponse()
    }

    suspend fun serverLogout() {
        client.post("user/logout")
    }

    private suspend inline fun <reified T> HttpResponse.toKtorResponse(): KtorResponse<T> {
        val success = status.value in 200..299
        val bodyObj = if (success || status.value == 412) {
            try {
                body<T>()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val headerMap = headers.entries().associate { it.key to it.value }
        return KtorResponse(
            code = status.value,
            isSuccessful = success,
            body = bodyObj,
            headers = headerMap
        )
    }
}
