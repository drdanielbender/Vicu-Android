package com.rendyhd.vicu.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TotpTest {
    @Test
    fun `sanitizer keeps at most six digits`() {
        assertEquals("123456", sanitizeTotpPasscode("12a34 5678"))
    }

    @Test
    fun `passcode is complete only for six digits`() {
        assertTrue(isTotpPasscodeComplete("012345"))
        assertFalse(isTotpPasscodeComplete("12345"))
        assertFalse(isTotpPasscodeComplete("12345a"))
    }

    @Test
    fun `both Vikunja TOTP challenge codes are recognized`() {
        assertTrue(isTotpProblemCode(ERROR_INVALID_TOTP))
        assertTrue(isTotpProblemCode(ERROR_USED_TOTP))
        assertFalse(isTotpProblemCode(1011))
    }
}
