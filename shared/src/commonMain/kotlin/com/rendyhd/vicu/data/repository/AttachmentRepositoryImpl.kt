package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.dao.AttachmentDao
import com.rendyhd.vicu.data.mapper.AttachmentMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AttachmentRepositoryImpl(
    private val attachmentDao: AttachmentDao,
    private val api: VikunjaApiService,
    private val attachmentMapper: AttachmentMapper,
) : AttachmentRepository {

    override fun getByTaskId(taskId: Long): Flow<List<Attachment>> =
        attachmentDao.getByTaskId(taskId).map { entities ->
            entities.map { with(attachmentMapper) { it.toDomain() } }
        }

    override suspend fun upload(
        taskId: Long,
        fileName: String,
        content: ByteArray
    ): NetworkResult<Attachment> {
        return try {
            val beforeIds = api.getAttachments(taskId).map { it.id }.toSet()
            api.uploadAttachment(taskId, fileName, content)
            val afterDtos = api.getAttachments(taskId)
            val newDto = afterDtos.filter { it.id !in beforeIds }.maxByOrNull { it.id }
                ?: return NetworkResult.Error("Upload succeeded but new attachment not found")

            val afterEntities = afterDtos.map { with(attachmentMapper) { it.toEntity(taskId) } }
            attachmentDao.upsertAll(afterEntities)

            val newEntity = with(attachmentMapper) { newDto.toEntity(taskId) }
            NetworkResult.Success(with(attachmentMapper) { newEntity.toDomain() })
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to upload attachment")
        }
    }

    override suspend fun download(taskId: Long, attachmentId: Long): NetworkResult<ByteArray> {
        return try {
            val bytes = api.downloadAttachment(taskId, attachmentId)
            NetworkResult.Success(bytes)
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to download attachment")
        }
    }

    override suspend fun delete(taskId: Long, attachmentId: Long): NetworkResult<Unit> {
        return try {
            attachmentDao.deleteById(attachmentId)
            api.deleteAttachment(taskId, attachmentId)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to delete attachment")
        }
    }

    override suspend fun refreshForTask(taskId: Long): NetworkResult<Unit> {
        return try {
            val dtos = api.getAttachments(taskId)
            val entities = dtos.map { with(attachmentMapper) { it.toEntity(taskId) } }
            attachmentDao.upsertAll(entities)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Failed to refresh attachments")
        }
    }
}
