package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.api.VikunjaProblemDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthTotpHandlerTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun service(errorCode: Long, detail: String): VikunjaApiService {
        val engine = MockEngine {
            respond(
                content = """{"title":"Precondition Failed","status":412,"detail":"$detail","code":$errorCode}""",
                status = HttpStatusCode.PreconditionFailed,
                headers = Headers.build {
                    append(HttpHeaders.ContentType, "application/problem+json")
                },
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return VikunjaApiService(client, json)
    }

    @Test
    fun `password login requests TOTP after Vikunja challenge`() = runTest {
        val handler = PasswordLoginHandler {
            service(ERROR_INVALID_TOTP, "Invalid totp passcode.")
        }

        assertIs<PasswordLoginResult.NeedsTOTP>(handler.login("user", "password"))
    }

    @Test
    fun `password login preserves invalid TOTP error for retry`() = runTest {
        val handler = PasswordLoginHandler {
            service(ERROR_USED_TOTP, "This totp passcode has already been used.")
        }

        val result = assertIs<PasswordLoginResult.Error>(
            handler.login("user", "password", "123456"),
        )

        assertEquals("This totp passcode has already been used.", result.message)
    }

    @Test
    fun `OIDC token exchange requests TOTP after Vikunja challenge`() {
        val result = oidcTotpChallenge(
            VikunjaProblemDto(
                status = 412,
                code = ERROR_INVALID_TOTP,
                detail = "Invalid totp passcode.",
            ),
        )

        assertIs<OidcResult.NeedsTOTP>(result)
    }
}
