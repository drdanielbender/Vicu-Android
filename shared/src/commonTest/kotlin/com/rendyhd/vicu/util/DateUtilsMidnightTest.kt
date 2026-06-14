package com.rendyhd.vicu.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class DateUtilsMidnightTest {

    @Test
    fun `one hour before midnight`() {
        val zone = TimeZone.of("Europe/Amsterdam")
        val now = LocalDateTime(2026, 6, 11, 23, 0, 0, 0).toInstant(zone)
        assertEquals(60L * 60 * 1000, DateUtils.millisUntilNextMidnight(now, zone))
    }

    @Test
    fun `dst spring-forward day is one hour shorter`() {
        // Europe/Amsterdam springs forward on 2026-03-29 (02:00 -> 03:00).
        val zone = TimeZone.of("Europe/Amsterdam")
        val now = LocalDateTime(2026, 3, 29, 1, 0, 0, 0).toInstant(zone)
        assertEquals(22L * 60 * 60 * 1000, DateUtils.millisUntilNextMidnight(now, zone))
    }

    @Test
    fun `at exact midnight returns a full day, never zero`() {
        val zone = TimeZone.of("UTC")
        val now = LocalDateTime(2026, 6, 11, 0, 0, 0, 0).toInstant(zone)
        assertEquals(24L * 60 * 60 * 1000, DateUtils.millisUntilNextMidnight(now, zone))
    }
}
