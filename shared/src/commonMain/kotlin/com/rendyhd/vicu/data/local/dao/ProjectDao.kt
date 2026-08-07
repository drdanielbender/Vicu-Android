package com.rendyhd.vicu.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects WHERE isArchived = 0 ORDER BY position ASC")
    fun getAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY position ASC")
    fun getAllIncludingArchived(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getById(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE parentProjectId = :parentId AND isArchived = 0 ORDER BY position ASC")
    fun getChildren(parentId: Long): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE isArchived = 0 ORDER BY position ASC")
    suspend fun getAllSync(): List<ProjectEntity>

    @Query("SELECT * FROM projects ORDER BY position ASC")
    suspend fun getAllIncludingArchivedSync(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getByIdSync(id: Long): ProjectEntity?

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Upsert
    suspend fun upsertAll(projects: List<ProjectEntity>)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()

    @Query("DELETE FROM projects WHERE id NOT IN (:serverIds)")
    suspend fun deleteNotIn(serverIds: List<Long>)

    @Transaction
    suspend fun replaceAll(projects: List<ProjectEntity>) {
        if (projects.isEmpty()) {
            deleteAll()
        } else {
            upsertAll(projects)
            deleteNotIn(projects.map { it.id })
        }
    }
}
