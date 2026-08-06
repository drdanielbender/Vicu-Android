package com.rendyhd.vicu.ui.screens.settings

interface PlatformSettingsHooks {
    val supportsQuickAddTile: Boolean
    fun updateWidgets()
    fun scheduleSync(enabled: Boolean)
    fun scheduleDailySummary(slot: String, enabled: Boolean, hour: Int, minute: Int)
    fun sendTestNotification(): String?
    fun requestQuickAddTile(onResult: (String) -> Unit)
    fun triggerImmediateSync()
}
