package com.rendyhd.vicu.util

import android.net.Uri
import com.rendyhd.vicu.data.local.PlatformContext

class AndroidPlatformFiles(private val platformContext: PlatformContext) : PlatformFiles {
    override fun getFileNameAndBytes(uriString: String): Pair<String, ByteArray>? {
        return try {
            FileUtils.getFileNameAndBytes(platformContext.context, Uri.parse(uriString))
        } catch (_: Exception) {
            null
        }
    }

    override fun getDisplayName(uriString: String): String? {
        return try {
            FileUtils.getDisplayName(platformContext.context, Uri.parse(uriString))
        } catch (_: Exception) {
            null
        }
    }
}
