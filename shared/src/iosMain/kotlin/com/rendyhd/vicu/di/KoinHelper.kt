package com.rendyhd.vicu.di

import org.koin.core.context.startKoin
import org.koin.dsl.module
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.auth.IosKeychainTokenStorage
import com.rendyhd.vicu.auth.PlatformAuthHooks
import com.rendyhd.vicu.auth.IosAuthHooks
import com.rendyhd.vicu.data.local.PlatformContext
import com.rendyhd.vicu.ui.screens.settings.PlatformSettingsHooks
import com.rendyhd.vicu.ui.screens.settings.IosSettingsHooks
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.IosPlatformFiles
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.IosNetworkMonitor
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.data.repository.IosRepositoryHooks

val iosModule = module {
    single<PlatformContext> { PlatformContext() }
    single<HttpClientEngine> { Darwin.create() }
    single<TokenStorage> { IosKeychainTokenStorage() }
    single<PlatformAuthHooks> { IosAuthHooks() }
    single<PlatformSettingsHooks> { IosSettingsHooks() }
    single<PlatformFiles> { IosPlatformFiles() }
    single<NetworkMonitor> { IosNetworkMonitor() }
    single<PlatformRepositoryHooks> { IosRepositoryHooks(get(), get()) }
}

fun initKoin() {
    startKoin {
        modules(sharedModules + iosModule)
    }
}
