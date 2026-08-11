package com.rendyhd.vicu.ui.components.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewCadenceInputDialogTest {
    @Test
    fun acceptsCadencesWithinSupportedRange() {
        assertEquals(1, parseReviewCadenceDays("1"))
        assertEquals(23, parseReviewCadenceDays("23"))
        assertEquals(365, parseReviewCadenceDays("365"))
    }

    @Test
    fun rejectsEmptyMalformedAndOutOfRangeCadences() {
        assertNull(parseReviewCadenceDays(""))
        assertNull(parseReviewCadenceDays("0"))
        assertNull(parseReviewCadenceDays("366"))
        assertNull(parseReviewCadenceDays("seven"))
    }
}
