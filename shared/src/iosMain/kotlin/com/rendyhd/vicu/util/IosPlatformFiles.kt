package com.rendyhd.vicu.util

class IosPlatformFiles : PlatformFiles {
    override fun getFileNameAndBytes(uriString: String): Pair<String, ByteArray>? {
        // TODO: Implement file reading using iOS APIs (NSURL / NSData)
        return null
    }

    override fun getDisplayName(uriString: String): String? {
        // TODO: Implement display name extraction using iOS APIs
        return null
    }
}
