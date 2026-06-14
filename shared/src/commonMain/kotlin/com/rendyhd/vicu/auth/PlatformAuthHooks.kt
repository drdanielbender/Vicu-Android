package com.rendyhd.vicu.auth

interface PlatformAuthHooks {
    fun cancelRefreshScheduler()
    fun scheduleRefresh()
    fun updateWidgets()
}
