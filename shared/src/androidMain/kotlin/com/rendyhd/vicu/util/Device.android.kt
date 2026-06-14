package com.rendyhd.vicu.util

import android.os.Build

actual fun getDeviceTokenTitle(): String {
    val device = Build.MODEL.ifBlank { Build.DEVICE }.ifBlank { "Android" }
    val manuf = Build.MANUFACTURER
        .takeIf { it.isNotBlank() && !device.startsWith(it, ignoreCase = true) }
    val suffix = if (manuf != null) "$manuf $device" else device
    return "Vicu — $suffix"
}
