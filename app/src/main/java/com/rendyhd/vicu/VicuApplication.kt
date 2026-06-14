package com.rendyhd.vicu

import android.app.Application
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.androidx.workmanager.factory.KoinDelegatingWorkerFactory
import com.rendyhd.vicu.di.androidAppModules
import com.rendyhd.vicu.di.sharedModules

class VicuApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory, KoinComponent {

    private val notificationChannelManager: NotificationChannelManager by inject()
    private val imageLoader: ImageLoader by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@VicuApplication)
            workManagerFactory()
            modules(sharedModules + androidAppModules)
        }
        notificationChannelManager.createChannels()
        WidgetUpdateScheduler.schedulePeriodicRefresh(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(KoinDelegatingWorkerFactory())
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
