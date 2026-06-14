package com.rendyhd.vicu.ui.screens.settings

class IosSettingsHooks : PlatformSettingsHooks {
    override fun updateWidgets() {
        // TODO: Implement for iOS
    }

    override fun scheduleSync(enabled: Boolean) {
        // TODO: Implement for iOS
    }

    override fun scheduleDailySummary(slot: String, enabled: Boolean, hour: Int, minute: Int) {
        // TODO: Implement for iOS
    }

    override fun sendTestNotification(): String? {
        // TODO: Implement for iOS
        return "Test notification scheduled on iOS"
    }

    override fun triggerImmediateSync() {
        // TODO: Implement for iOS
    }
}
