package com.rendyhd.vicu.util

import android.util.Base64

actual object Base64Decoder {
    actual fun decodeUrlSafe(src: String): ByteArray {
        return Base64.decode(src, Base64.URL_SAFE or Base64.NO_PADDING)
    }
}
