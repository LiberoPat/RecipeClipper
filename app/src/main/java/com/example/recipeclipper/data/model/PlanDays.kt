package com.example.recipeclipper.data.model

import java.util.TimeZone

/**
 * The meal plan's calendar (#49), as plain numbers. A day is an **epoch day**: whole days since
 * 1970-01-01 on the user's local calendar, so "Tuesday the 23rd" is one integer however the
 * clock and time zone move, and a week is seven consecutive integers. Stored as such in
 * `meal_plan_entries.day`.
 *
 * Days of the week use `java.util.Calendar`'s numbering, which is also iOS
 * `Calendar.firstWeekday`'s: 1 = Sunday … 7 = Saturday. Pure (no java.time, which needs API 26),
 * and identical to iOS's `PlanDays`.
 */
object PlanDays {

    const val MILLIS_PER_DAY = 86_400_000L

    /** How many days the plan sheets offer: this week and next. */
    const val SHEET_DAYS = 14

    /** The local calendar day containing [millis], for a zone [offsetMillis] ahead of UTC. */
    fun epochDay(millis: Long, offsetMillis: Int): Long =
        Math.floorDiv(millis + offsetMillis, MILLIS_PER_DAY)

    /** Today in [zone] at [millis]: what the cull rule and "This week" are measured from. */
    fun today(millis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        epochDay(millis, zone.getOffset(millis))

    /** 1 = Sunday … 7 = Saturday. Epoch day 0 was a Thursday. */
    fun dayOfWeek(day: Long): Int = (Math.floorMod(day + 4, 7L) + 1).toInt()

    /** The first day of the week holding [day], for a week that starts on [firstDayOfWeek]. */
    fun weekStart(day: Long, firstDayOfWeek: Int): Long =
        day - Math.floorMod((dayOfWeek(day) - firstDayOfWeek).toLong(), 7L)

    /** The seven days of the week starting on [start]. */
    fun weekDays(start: Long): List<Long> = (0 until 7).map { start + it }

    /** Midnight UTC of [day], for formatting it with a UTC calendar (never the local zone,
     *  which could land on the day before). */
    fun utcMillis(day: Long): Long = day * MILLIS_PER_DAY
}
