package com.rendyhd.vicu.auth

actual object AuthDebugLog {
    actual fun init(context: Any?) {}
    actual fun log(event: String) {}
    actual fun log(event: String, detail: String) {}
    actual fun logError(event: String, error: Throwable) {}
    actual fun readLog(): String = "(not implemented on iOS)"
    actual fun clear() {}
    actual fun tokenState(jwt: Boolean, jwtExpired: Boolean, apiToken: Boolean, refreshToken: Boolean, isV2: Boolean) {}
    actual fun authStateChanged(old: String, new: String, reason: String) {}
    actual fun jwtExpiry(expiryEpoch: Long) {}
    actual fun refreshAttempt(method: String) {}
    actual fun refreshResult(method: String, success: Boolean, detail: String) {}
    actual fun lifecycle(event: String) {}
    actual fun interceptor(event: String, path: String) {}
}
