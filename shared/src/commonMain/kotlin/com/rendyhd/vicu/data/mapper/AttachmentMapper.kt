package com.rendyhd.vicu.data.mapper

import com.rendyhd.vicu.data.local.entity.AttachmentEntity
import com.rendyhd.vicu.data.remote.api.AttachmentDto
import com.rendyhd.vicu.domain.model.Attachment

class AttachmentMapper {

    fun AttachmentDto.toEntity(taskId: Long): AttachmentEntity = AttachmentEntity(
        id = id,
        taskId = taskId,
        fileName = file?.name ?: "",
        mimeType = file?.mime ?: "",
        fileSize = file?.size ?: 0,
        createdAt = created,
    )

    fun AttachmentEntity.toDomain(): Attachment = Attachment(
        id = id,
        taskId = taskId,
        fileName = fileName,
        mimeType = mimeType,
        fileSize = fileSize,
        createdAt = createdAt,
    )
}
