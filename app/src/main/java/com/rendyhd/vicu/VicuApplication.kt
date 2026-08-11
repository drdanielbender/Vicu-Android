package com.rendyhd.vicu

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.RoutineMaintenanceScheduler
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.androidx.workmanager.koin.workManagerFactory
import com.rendyhd.vicu.di.androidAppModules
import com.rendyhd.vicu.di.sharedModules

class VicuApplication : Application(), SingletonImageLoader.Factory, KoinComponent {

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
        RoutineMaintenanceScheduler.schedule(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
