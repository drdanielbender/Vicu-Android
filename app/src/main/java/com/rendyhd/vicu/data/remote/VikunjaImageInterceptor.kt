package com.rendyhd.vicu.data.remote

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Rewrites Coil's placeholder attachment URLs to the configured Vikunja server
 * and authenticates them. Other network images are deliberately left untouched
 * so credentials can never be sent to an unrelated host.
 */
internal class VikunjaImageInterceptor(
    private val getFullBaseUrl: () -> String,
    private val initializeBaseUrl: () -> Unit,
    private val getCachedToken: () -> String?,
    private val initializeAuth: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (originalRequest.url.host != PLACEHOLDER_HOST) {
            return chain.proceed(originalRequest)
        }

        var fullBaseUrl = getFullBaseUrl()
        if (fullBaseUrl.isBlank()) {
            initializeBaseUrl()
            fullBaseUrl = getFullBaseUrl()
        }

        val rewrittenUrl = rewriteUrl(originalRequest.url, fullBaseUrl)
            ?: return chain.proceed(originalRequest)

        val token = getCachedToken().takeUnless { it.isNullOrBlank() }
            ?: initializeAuth().takeUnless { it.isNullOrBlank() }

        val request = originalRequest.newBuilder()
            .url(rewrittenUrl)
            .apply {
                if (token != null) {
                    header(AUTHORIZATION_HEADER, "Bearer $token")
                }
            }
            .build()

        return chain.proceed(request)
    }

    private fun rewriteUrl(originalUrl: HttpUrl, fullBaseUrl: String): HttpUrl? {
        val baseUrl = fullBaseUrl.toHttpUrlOrNull() ?: return null
        val basePath = baseUrl.encodedPath.trimEnd('/')
        val requestPath = originalUrl.encodedPath.trimStart('/')
        val combinedPath = when {
            basePath.isEmpty() && requestPath.isEmpty() -> "/"
            basePath.isEmpty() -> "/$requestPath"
            requestPath.isEmpty() -> basePath
            else -> "$basePath/$requestPath"
        }

        return originalUrl.newBuilder()
            .scheme(baseUrl.scheme)
            .host(baseUrl.host)
            .port(baseUrl.port)
            .encodedPath(combinedPath)
            .build()
    }

    private companion object {
        const val PLACEHOLDER_HOST = "localhost"
        const val AUTHORIZATION_HEADER = "Authorization"
    }
}
