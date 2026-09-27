package com.example.recipeclipper.fake

import com.example.recipeclipper.data.PlanCalendar

/** A pinned today and first day of the week. Defaults: Wednesday 2026-09-23, weeks from Monday. */
class FakePlanCalendar(
    var today: Long = WEDNESDAY,
    var firstDayOfWeek: Int = MONDAY_FIRST,
    /** 2026-09-23 14:25:00 UTC by default. */
    var now: Long = WEDNESDAY * 86_400_000L + (14 * 3600 + 25 * 60) * 1000L
) : PlanCalendar {
    override fun today(): Long = today
    override fun firstDayOfWeek(): Int = firstDayOfWeek
    override fun now(): Long = now

    companion object {
        /** 2026-09-23, a Wednesday. */
        const val WEDNESDAY = 20_719L
        const val MONDAY_FIRST = 2
        const val SUNDAY_FIRST = 1
    }
}
