package com.rendyhd.vicu.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncRemapTest {

    @Test
    fun remaps_add_label_payload_that_references_the_temp_id() {
        assertEquals("42:7", remapLabelTaskPayload("-123:7", tempId = -123, realId = 42))
    }

    @Test
    fun returns_null_when_payload_does_not_reference_the_temp_id() {
        assertNull(remapLabelTaskPayload("99:7", tempId = -123, realId = 42))
    }

    @Test
    fun returns_null_for_a_malformed_payload() {
        assertNull(remapLabelTaskPayload("not-a-pair", tempId = -123, realId = 42))
    }
}
