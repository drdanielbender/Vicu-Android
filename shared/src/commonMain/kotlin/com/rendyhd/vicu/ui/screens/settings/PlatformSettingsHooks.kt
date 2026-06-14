package com.rendyhd.vicu.ui.screens.settings

interface PlatformSettingsHooks {
    fun updateWidgets()
    fun scheduleSync(enabled: Boolean)
    fun scheduleDailySummary(slot: String, enabled: Boolean, hour: Int, minute: Int)
    fun sendTestNotification(): String?
    fun triggerImmediateSync()
}
