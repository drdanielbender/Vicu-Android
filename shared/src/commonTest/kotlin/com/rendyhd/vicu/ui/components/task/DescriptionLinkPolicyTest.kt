package com.rendyhd.vicu.ui.components.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DescriptionLinkPolicyTest {
    @Test
    fun allowsOnlySharedContractSchemes() {
        assertTrue(isSafeDescriptionHref("https://example.com/path"))
        assertTrue(isSafeDescriptionHref("http://localhost:3456"))
        assertTrue(isSafeDescriptionHref("mailto:user@example.com"))
        assertFalse(isSafeDescriptionHref("javascript:alert(1)"))
        assertFalse(isSafeDescriptionHref("data:text/html,x"))
        assertFalse(isSafeDescriptionHref("https://exa mple.com"))
    }

    @Test
    fun normalizesBareHostsToHttps() {
        assertEquals("https://example.com/path", normalizeDescriptionHref("example.com/path"))
        assertNull(normalizeDescriptionHref("relative/path"))
        assertNull(normalizeDescriptionHref("javascript:alert(1)"))
    }
}
