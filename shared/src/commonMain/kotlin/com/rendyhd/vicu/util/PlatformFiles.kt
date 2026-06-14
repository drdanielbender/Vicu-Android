package com.rendyhd.vicu.util

interface PlatformFiles {
    fun getFileNameAndBytes(uriString: String): Pair<String, ByteArray>?
    fun getDisplayName(uriString: String): String?
}
