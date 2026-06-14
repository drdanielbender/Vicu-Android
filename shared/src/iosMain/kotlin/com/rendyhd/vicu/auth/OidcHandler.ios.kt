package com.rendyhd.vicu.auth

import platform.Foundation.NSUUID
import platform.Foundation.NSString
import platform.Foundation.stringByAddingPercentEncodingWithAllowedCharacters
import platform.Foundation.NSCharacterSet
import platform.Foundation.URLQueryAllowedCharacterSet

actual fun encodeUrl(value: String): String {
    // Cast value to NSString using standard Kotlin/Native platform methods
    val nsString = value as NSString
    return nsString.stringByAddingPercentEncodingWithAllowedCharacters(NSCharacterSet.URLQueryAllowedCharacterSet) ?: value
}

actual fun generateStateValue(): String {
    return NSUUID.UUID().UUIDString
}
