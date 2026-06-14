package com.rendyhd.vicu.util

expect object Base64Decoder {
    fun decodeUrlSafe(src: String): ByteArray
}
