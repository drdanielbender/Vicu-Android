package com.rendyhd.vicu.data.remote

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VikunjaImageInterceptorTest {

    @Test
    fun placeholderAttachmentUrlIsRewrittenAndAuthenticated() {
        val interceptor = VikunjaImageInterceptor(
            getFullBaseUrl = { "https://vikunja.example/base/api/v2/" },
            initializeBaseUrl = {},
            getCachedToken = { "secret-token" },
            initializeAuth = { null },
        )
        val chain = RecordingChain(
            Request.Builder()
                .url("http://localhost/tasks/42/attachments/7?preview=true")
                .build()
        )

        interceptor.intercept(chain)

        assertEquals(
            "https://vikunja.example/base/api/v2/tasks/42/attachments/7?preview=true",
            chain.proceededRequest?.url.toString(),
        )
        assertEquals("Bearer secret-token", chain.proceededRequest?.header("Authorization"))
    }

    @Test
    fun lazilyInitializesBaseUrlAndAuthentication() {
        var fullBaseUrl = ""
        val interceptor = VikunjaImageInterceptor(
            getFullBaseUrl = { fullBaseUrl },
            initializeBaseUrl = { fullBaseUrl = "http://192.0.2.1/api/v1/" },
            getCachedToken = { null },
            initializeAuth = { "loaded-token" },
        )
        val chain = RecordingChain(
            Request.Builder()
                .url("http://localhost/tasks/1/attachments/2")
                .build()
        )

        interceptor.intercept(chain)

        assertEquals(
            "http://192.0.2.1/api/v1/tasks/1/attachments/2",
            chain.proceededRequest?.url.toString(),
        )
        assertEquals("Bearer loaded-token", chain.proceededRequest?.header("Authorization"))
    }

    @Test
    fun externalImageUrlIsNotRewrittenOrAuthenticated() {
        val interceptor = VikunjaImageInterceptor(
            getFullBaseUrl = { "https://vikunja.example/api/v2/" },
            initializeBaseUrl = {},
            getCachedToken = { "secret-token" },
            initializeAuth = { "secret-token" },
        )
        val chain = RecordingChain(
            Request.Builder()
                .url("https://images.example/photo.jpg")
                .build()
        )

        interceptor.intercept(chain)

        assertEquals("https://images.example/photo.jpg", chain.proceededRequest?.url.toString())
        assertNull(chain.proceededRequest?.header("Authorization"))
    }

    private class RecordingChain(
        private val initialRequest: Request,
    ) : Interceptor.Chain {
        var proceededRequest: Request? = null

        override fun request(): Request = initialRequest

        override fun proceed(request: Request): Response {
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ByteArray(0).toResponseBody())
                .build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = error("Not used by this test")

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
