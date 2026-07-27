package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.KtorResponse

object RefreshCookieExtractor {
    private const val COOKIE_NAME = "vikunja_refresh_token"

    fun extractRefreshToken(response: KtorResponse<*>): String? {
        val cookies = response.headers.entries
            .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
            ?.value
            ?: return null
        for (cookie in cookies) {
            if (cookie.startsWith("$COOKIE_NAME=")) {
                val value = cookie.substringAfter("$COOKIE_NAME=").substringBefore(";")
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    fun buildCookieHeader(refreshToken: String): String {
        return "$COOKIE_NAME=$refreshToken"
    }
}
