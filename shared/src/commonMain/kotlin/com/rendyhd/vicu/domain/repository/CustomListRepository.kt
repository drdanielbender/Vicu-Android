package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CustomListRepository {
    val lists: Flow<List<CustomList>>
    val syncStatus: StateFlow<CustomListSyncStatus>

    suspend fun upsert(customList: CustomList)
    suspend fun delete(id: String)
    suspend fun reorder(fromIndex: Int, toIndex: Int)
    suspend fun clearLocal()
    suspend fun sync(): CustomListSyncStatus
}
