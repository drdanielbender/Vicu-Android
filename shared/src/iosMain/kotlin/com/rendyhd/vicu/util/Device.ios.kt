package com.rendyhd.vicu.util

import platform.UIKit.UIDevice

actual fun getDeviceTokenTitle(): String {
    val model = UIDevice.currentDevice.model
    val name = UIDevice.currentDevice.name
    val suffix = if (name.isNotBlank() && name != model) name else model
    return "Vicu — $suffix"
}
