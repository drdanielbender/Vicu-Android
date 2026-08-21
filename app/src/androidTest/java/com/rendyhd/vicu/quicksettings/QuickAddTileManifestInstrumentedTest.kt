package com.rendyhd.vicu.quicksettings

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickAddTileManifestInstrumentedTest {

    @Test
    fun installedServiceUsesStandardListeningMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, QuickAddTileService::class.java)
        val serviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        }

        assertTrue(serviceInfo.enabled)
        assertTrue(serviceInfo.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", serviceInfo.permission)
        assertFalse(serviceInfo.metaData?.containsKey(ACTIVE_TILE_METADATA) == true)
        assertFalse(serviceInfo.metaData?.containsKey(TOGGLEABLE_TILE_METADATA) == true)
        assertFalse(context.packageManager.isComponentExplicitlyDisabled(component))
    }

    private fun PackageManager.isComponentExplicitlyDisabled(component: ComponentName): Boolean =
        getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private companion object {
        const val ACTIVE_TILE_METADATA = "android.service.quicksettings.ACTIVE_TILE"
        const val TOGGLEABLE_TILE_METADATA = "android.service.quicksettings.TOGGLEABLE_TILE"
    }
}
