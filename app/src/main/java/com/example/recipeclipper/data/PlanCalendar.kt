package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.PlanDays
import java.util.Calendar
import javax.inject.Inject

/**
 * Today and the first day of the week, on the user's calendar (#49). A seam so the Week and
 * plan-sheet ViewModels never read the clock, zone or locale themselves, and a test can pin
 * both. [SystemPlanCalendar] is the real one.
 */
interface PlanCalendar {
    /** Today, as an epoch day (see [PlanDays]). */
    fun today(): Long

    /** 1 = Sunday … 7 = Saturday: the locale's own, never a fixed Monday (owner's call). */
    fun firstDayOfWeek(): Int
}

class SystemPlanCalendar @Inject constructor(private val clock: Clock) : PlanCalendar {
    override fun today(): Long = PlanDays.today(clock.now())

    // java.util.Calendar rather than WeekFields: the same answer, without java.time (API 26).
    override fun firstDayOfWeek(): Int = Calendar.getInstance().firstDayOfWeek
}
