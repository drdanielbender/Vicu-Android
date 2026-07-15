package com.rendyhd.vicu.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefreshBackoffTest {

    @Test
    fun `first server error schedules 5s backoff`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 0)
        assertEquals(5_000L, delay)
    }

    @Test
    fun `network error follows the same exponential curve as server error`() {
        assertEquals(5_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.NetworkError, 0))
        assertEquals(10_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.NetworkError, 1))
        assertEquals(20_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.NetworkError, 2))
    }

    @Test
    fun `consecutive failures double up to 120s cap`() {
        assertEquals(5_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 0))
        assertEquals(10_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 1))
        assertEquals(20_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 2))
        assertEquals(40_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 3))
        assertEquals(80_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 4))
        assertEquals(120_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 5))
        assertEquals(120_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 6))
        assertEquals(120_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.ServerError, 99))
    }

    @Test
    fun `rate limited with Retry-After 90 honors the header`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.RateLimited(retryAfterSecs = 90), 0)
        assertEquals(90_000L, delay)
    }

    @Test
    fun `rate limited with Retry-After 30 is floored to 60s`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.RateLimited(retryAfterSecs = 30), 0)
        assertEquals(60_000L, delay)
    }

    @Test
    fun `rate limited ignores prior consecutive failure count`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.RateLimited(retryAfterSecs = 90), 4)
        assertEquals(90_000L, delay)
    }

    @Test
    fun `Unauthorized is treated as terminal`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.Unauthorized, 0)
        assertTrue(delay >= 1_000_000_000L, "Expected a very large delay, got $delay")
    }

    @Test
    fun `NoRefreshToken is treated as terminal`() {
        val delay = RefreshBackoffPolicy.nextDelayMs(RefreshFailure.NoRefreshToken, 0)
        assertTrue(delay >= 1_000_000_000L, "Expected a very large delay, got $delay")
    }

    @Test
    fun `EmptyTokenReturned uses transient backoff curve`() {
        assertEquals(5_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.EmptyTokenReturned, 0))
        assertEquals(10_000L, RefreshBackoffPolicy.nextDelayMs(RefreshFailure.EmptyTokenReturned, 1))
    }
}
