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

    // --- Months (#52's month view), on the proleptic Gregorian calendar, as plain integers
    // (Howard Hinnant's days_from_civil and civil_from_days).

    /** The epoch day of [year]-[month]-[dayOfMonth]; [month] is 1…12. */
    fun epochDay(year: Int, month: Int, dayOfMonth: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yearOfEra = y - era * 400
        val m = month.toLong()
        val dayOfYear = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + dayOfMonth - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    /** The year, month (1…12) and day of the month of [day]. */
    fun civil(day: Long): Triple<Int, Int, Int> {
        val z = day + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val dayOfMonth = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, dayOfMonth)
    }

    /** The first day of the month holding [day]. */
    fun monthStart(day: Long): Long = day - civil(day).third + 1

    /** The first day of the month [months] after (or before, if negative) the one holding [day]. */
    fun addMonths(day: Long, months: Int): Long {
        val (year, month, _) = civil(day)
        val index = year * 12L + (month - 1) + months
        return epochDay(Math.floorDiv(index, 12L).toInt(), Math.floorMod(index, 12L).toInt() + 1, 1)
    }

    /**
     * The month grid holding [day]: whole weeks from the locale's [firstDayOfWeek], from the week
     * of the month's first day to the week of its last, so four to six rows of seven. The days
     * before and after the month fill the first and last rows.
     */
    fun monthGrid(day: Long, firstDayOfWeek: Int): List<Long> {
        val first = weekStart(monthStart(day), firstDayOfWeek)
        val last = weekStart(addMonths(day, 1) - 1, firstDayOfWeek) + 6
        return (first..last).toList()
    }

    /** Midnight UTC of [day], for formatting it with a UTC calendar (never the local zone,
     *  which could land on the day before). */
    fun utcMillis(day: Long): Long = day * MILLIS_PER_DAY
}
