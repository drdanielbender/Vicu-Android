package com.rendyhd.vicu.auth

expect object AuthDebugLog {
    fun init(context: Any?)
    fun log(event: String)
    fun log(event: String, detail: String)
    fun logError(event: String, error: Throwable)
    fun readLog(): String
    fun clear()
    fun tokenState(jwt: Boolean, jwtExpired: Boolean, apiToken: Boolean, refreshToken: Boolean, isV2: Boolean)
    fun authStateChanged(old: String, new: String, reason: String)
    fun jwtExpiry(expiryEpoch: Long)
    fun refreshAttempt(method: String)
    fun refreshResult(method: String, success: Boolean, detail: String = "")
    fun lifecycle(event: String)
    fun interceptor(event: String, path: String)
}
