package com.rendyhd.vicu.ui

import androidx.compose.ui.window.ComposeUIViewController
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.ThemeMode
import com.rendyhd.vicu.data.local.ThemePrefsStore
import com.rendyhd.vicu.ui.VicuApp
import com.rendyhd.vicu.ui.theme.VicuTheme
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    val koinComponent = object : KoinComponent {}
    val authManager: AuthManager by koinComponent.inject()
    val themePrefsStore: ThemePrefsStore by koinComponent.inject()

    VicuTheme(themeMode = ThemeMode.System, dynamicColor = false) {
        VicuApp(
            authManager = authManager
        )
    }
}
