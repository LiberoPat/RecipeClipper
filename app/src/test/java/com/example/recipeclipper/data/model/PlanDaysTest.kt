package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class PlanDaysTest {

    // 2026-09-23 is a Wednesday, epoch day 20719.
    private val wednesday = 20_719L

    @Test
    fun `epoch day 0 was a Thursday`() {
        assertEquals(5, PlanDays.dayOfWeek(0))
        assertEquals(4, PlanDays.dayOfWeek(wednesday))
        assertEquals(4, PlanDays.dayOfWeek(-1)) // 1969-12-31, a Wednesday
    }

    @Test
    fun `the week starts on the locale's first day`() {
        assertEquals(wednesday - 2, PlanDays.weekStart(wednesday, firstDayOfWeek = 2)) // Monday
        assertEquals(wednesday - 3, PlanDays.weekStart(wednesday, firstDayOfWeek = 1)) // Sunday
        assertEquals(wednesday - 4, PlanDays.weekStart(wednesday, firstDayOfWeek = 7)) // Saturday
        // A week-start day is its own week's start.
        assertEquals(wednesday - 2, PlanDays.weekStart(wednesday - 2, firstDayOfWeek = 2))
        // Sunday in a Monday-first week belongs to the week before it.
        assertEquals(wednesday - 2, PlanDays.weekStart(wednesday + 4, firstDayOfWeek = 2))
    }

    @Test
    fun `a week is seven consecutive days`() {
        assertEquals((10L..16L).toList(), PlanDays.weekDays(10))
    }

    @Test
    fun `today follows the zone, not UTC`() {
        // 2026-09-23 23:30 UTC is already the 24th in Tokyo and still the 23rd in New York.
        val lateOnThe23rd = wednesday * PlanDays.MILLIS_PER_DAY + 23 * 3_600_000L + 30 * 60_000L
        assertEquals(wednesday, PlanDays.today(lateOnThe23rd, TimeZone.getTimeZone("UTC")))
        assertEquals(wednesday + 1, PlanDays.today(lateOnThe23rd, TimeZone.getTimeZone("Asia/Tokyo")))
        assertEquals(wednesday, PlanDays.today(lateOnThe23rd, TimeZone.getTimeZone("America/New_York")))
    }

    @Test
    fun `a moment before 1970 floors to the day before`() {
        assertEquals(-1L, PlanDays.epochDay(-1, 0))
    }
}
