package com.example.recipeclipper.fake

import com.example.recipeclipper.data.PlanCalendar

/** A pinned today and first day of the week. Defaults: Wednesday 2026-09-23, weeks from Monday. */
class FakePlanCalendar(
    var today: Long = WEDNESDAY,
    var firstDayOfWeek: Int = MONDAY_FIRST
) : PlanCalendar {
    override fun today(): Long = today
    override fun firstDayOfWeek(): Int = firstDayOfWeek

    companion object {
        /** 2026-09-23, a Wednesday. */
        const val WEDNESDAY = 20_719L
        const val MONDAY_FIRST = 2
        const val SUNDAY_FIRST = 1
    }
}
