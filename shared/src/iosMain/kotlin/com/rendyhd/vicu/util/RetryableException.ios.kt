package com.rendyhd.vicu.util

actual fun isPlatformRetriableError(e: Exception): Boolean {
    val className = e::class.simpleName ?: ""
    val causeClassName = e.cause?.let { it::class.simpleName } ?: ""
    if (className.contains("SSL") || causeClassName.contains("SSL")) return false
    if (className.contains("IOException") || causeClassName.contains("IOException")) return true
    if (className.contains("Timeout") || causeClassName.contains("Timeout")) return true
    if (className.contains("Connect") || causeClassName.contains("Connect")) return true
    return false
}
