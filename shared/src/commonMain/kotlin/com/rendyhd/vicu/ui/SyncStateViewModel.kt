package com.rendyhd.vicu.ui

import androidx.lifecycle.ViewModel
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.util.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class SyncStateViewModel(
    networkMonitor: NetworkMonitor,
    pendingActionDao: PendingActionDao,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val pendingCount: Flow<Int> = pendingActionDao.getPendingCount()
}
