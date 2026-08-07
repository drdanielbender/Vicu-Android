package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    /** Active projects only. Normal navigation and pickers must use this flow. */
    fun getAll(): Flow<List<Project>>
    /** Complete server snapshot, including archived projects. Intended for Settings. */
    fun getAllIncludingArchived(): Flow<List<Project>>
    fun getById(id: Long): Flow<Project?>
    fun getChildren(parentId: Long): Flow<List<Project>>

    suspend fun create(project: Project): NetworkResult<Project>
    suspend fun update(project: Project): NetworkResult<Project>
    suspend fun delete(projectId: Long): NetworkResult<Unit>
    suspend fun refreshAll(): NetworkResult<Unit>
}
