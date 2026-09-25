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

    // --- Months (#52)

    @Test
    fun `civil dates round-trip, leap days and before 1970 included`() {
        assertEquals(0L, PlanDays.epochDay(1970, 1, 1))
        assertEquals(wednesday, PlanDays.epochDay(2026, 9, 23))
        assertEquals(Triple(2026, 9, 23), PlanDays.civil(wednesday))
        assertEquals(Triple(1969, 12, 31), PlanDays.civil(-1))
        assertEquals(Triple(2024, 2, 29), PlanDays.civil(PlanDays.epochDay(2024, 2, 29)))
        assertEquals(PlanDays.epochDay(2024, 3, 1), PlanDays.epochDay(2024, 2, 29) + 1)
        assertEquals(PlanDays.epochDay(1900, 3, 1), PlanDays.epochDay(1900, 2, 28) + 1) // not a leap year
        assertEquals(PlanDays.epochDay(2000, 3, 1), PlanDays.epochDay(2000, 2, 28) + 2) // a leap year
        for (day in -800L..800L step 7) assertEquals(day, PlanDays.civil(day).let { (y, m, d) -> PlanDays.epochDay(y, m, d) })
    }

    @Test
    fun `months start on the 1st and step across years`() {
        assertEquals(PlanDays.epochDay(2026, 9, 1), PlanDays.monthStart(wednesday))
        assertEquals(PlanDays.epochDay(2026, 10, 1), PlanDays.addMonths(wednesday, 1))
        assertEquals(PlanDays.epochDay(2027, 1, 1), PlanDays.addMonths(wednesday, 4))
        assertEquals(PlanDays.epochDay(2025, 12, 1), PlanDays.addMonths(wednesday, -9))
        assertEquals(PlanDays.epochDay(2026, 2, 1), PlanDays.addMonths(PlanDays.epochDay(2026, 1, 31), 1))
    }

    @Test
    fun `the month grid is whole weeks from the locale's first day`() {
        // September 2026: the 1st is a Tuesday, the 30th a Wednesday.
        val monday = PlanDays.monthGrid(wednesday, firstDayOfWeek = 2)
        assertEquals(PlanDays.epochDay(2026, 8, 31), monday.first())
        assertEquals(PlanDays.epochDay(2026, 10, 4), monday.last())
        assertEquals(35, monday.size)
        val sunday = PlanDays.monthGrid(wednesday, firstDayOfWeek = 1)
        assertEquals(PlanDays.epochDay(2026, 8, 30), sunday.first())
        assertEquals(PlanDays.epochDay(2026, 10, 3), sunday.last())
        // February 2026 starts on a Sunday and has 28 days: exactly four Sunday-first rows.
        assertEquals(28, PlanDays.monthGrid(PlanDays.epochDay(2026, 2, 10), firstDayOfWeek = 1).size)
        // August 2026 starts on a Saturday: six Monday-first rows.
        assertEquals(42, PlanDays.monthGrid(PlanDays.epochDay(2026, 8, 10), firstDayOfWeek = 2).size)
    }
}
