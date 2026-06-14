package com.rendyhd.vicu.auth

import android.content.Context
import com.rendyhd.vicu.worker.TokenRefreshScheduler
import com.rendyhd.vicu.widget.WidgetUpdateScheduler

class AndroidAuthHooks(private val context: Context) : PlatformAuthHooks {
    override fun cancelRefreshScheduler() {
        TokenRefreshScheduler.cancel(context)
    }

    override fun scheduleRefresh() {
        TokenRefreshScheduler.schedule(context)
    }

    override fun updateWidgets() {
        WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
    }
}
