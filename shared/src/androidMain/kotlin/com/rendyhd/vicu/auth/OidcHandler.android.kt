package com.rendyhd.vicu.auth

import android.net.Uri
import java.util.UUID

actual fun encodeUrl(value: String): String {
    return Uri.encode(value)
}

actual fun generateStateValue(): String {
    return UUID.randomUUID().toString()
}
