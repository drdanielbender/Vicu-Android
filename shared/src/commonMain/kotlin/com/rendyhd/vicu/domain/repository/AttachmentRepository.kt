package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow

interface AttachmentRepository {
    fun getByTaskId(taskId: Long): Flow<List<Attachment>>

    suspend fun upload(taskId: Long, fileName: String, content: ByteArray): NetworkResult<Attachment>
    suspend fun download(taskId: Long, attachmentId: Long): NetworkResult<ByteArray>
    suspend fun delete(taskId: Long, attachmentId: Long): NetworkResult<Unit>
    suspend fun refreshForTask(taskId: Long): NetworkResult<Unit>
}
