package com.rendyhd.vicu.util

import java.io.IOException
import javax.net.ssl.SSLException

actual fun isPlatformRetriableError(e: Exception): Boolean {
    // Replicate original retrofit check if it's still thrown anywhere
    if (e.javaClass.name.contains("HttpException")) {
        try {
            val codeMethod = e.javaClass.getMethod("code")
            val code = codeMethod.invoke(e) as Int
            return code in 500..599 || code == 429
        } catch (_: Exception) {}
    }
    if (e is SSLException || e.cause is SSLException) return false
    if (e is IOException) return true
    if (e.cause is IOException) return true
    return false
}
