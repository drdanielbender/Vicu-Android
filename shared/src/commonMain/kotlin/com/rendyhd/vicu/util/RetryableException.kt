package com.rendyhd.vicu.util

import io.ktor.client.plugins.ResponseException

fun isRetriableNetworkError(e: Exception): Boolean {
    if (e is ResponseException) {
        val code = e.response.status.value
        return code in 500..599 || code == 429
    }
    return isPlatformRetriableError(e)
}

expect fun isPlatformRetriableError(e: Exception): Boolean
