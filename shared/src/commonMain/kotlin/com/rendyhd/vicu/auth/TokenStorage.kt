package com.rendyhd.vicu.auth

import kotlinx.coroutines.flow.Flow

interface TokenStorage {
    suspend fun storeJwt(jwt: String, expiry: Long)
    suspend fun getJwt(): String?
    suspend fun getJwtExpiry(): Long

    suspend fun storeApiToken(token: String, expiry: Long)
    suspend fun getApiToken(): String?
    suspend fun getApiTokenExpiry(): Long
    suspend fun hasApiToken(): Boolean

    suspend fun storeRefreshToken(token: String)
    suspend fun getRefreshToken(): String?

    suspend fun storeServerIsV2(isV2: Boolean)
    suspend fun getServerIsV2(): Boolean

    suspend fun storeAuthMethod(method: String)
    suspend fun getAuthMethod(): String?
    val authMethodFlow: Flow<String?>

    suspend fun storeProviderKey(key: String)
    suspend fun getProviderKey(): String?

    suspend fun storeVikunjaUrl(url: String)
    suspend fun getVikunjaUrl(): String?
    val vikunjaUrlFlow: Flow<String?>

    suspend fun storeInboxProjectId(id: Long)
    suspend fun getInboxProjectId(): Long?

    suspend fun clear()
}
