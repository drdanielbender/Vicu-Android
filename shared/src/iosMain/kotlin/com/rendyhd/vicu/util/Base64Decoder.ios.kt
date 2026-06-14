package com.rendyhd.vicu.util

import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.NSDataBase64DecodingIgnoreUnknownCharacters

actual object Base64Decoder {
    actual fun decodeUrlSafe(src: String): ByteArray {
        // Convert URL-safe base64 to standard base64
        var base64 = src
            .replace('-', '+')
            .replace('_', '/')
        
        // Add padding if necessary
        val pad = base64.length % 4
        if (pad > 0) {
            base64 += "====".substring(pad)
        }
        
        val nsData = NSData.create(base64String = base64, options = NSDataBase64DecodingIgnoreUnknownCharacters)
            ?: return ByteArray(0)
            
        val byteArray = ByteArray(nsData.length.toInt())
        if (byteArray.isNotEmpty()) {
            val buffer = nsData.bytes
            // Copy data to Kotlin ByteArray
            for (i in byteArray.indices) {
                byteArray[i] = buffer?.get(i.toLong()) as? Byte ?: 0
            }
        }
        return byteArray
    }
}
