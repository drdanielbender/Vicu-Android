package com.rendyhd.vicu.ui.screens.setup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VikunjaVersionTest {
    @Test
    fun `requires Vikunja 2_4_0 or newer`() {
        assertFalse(isSupportedVikunjaVersion("v2.3.9"))
        assertFalse(isSupportedVikunjaVersion("0.24.6"))
        assertTrue(isSupportedVikunjaVersion("v2.4.0"))
        assertTrue(isSupportedVikunjaVersion("2.4.1"))
        assertTrue(isSupportedVikunjaVersion("3.0.0"))
    }
}
