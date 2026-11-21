package com.example.recipeclipper.data.model

/**
 * When a planned meal is still worth offering on Home's Today card (#402), by the meal type's
 * built-in key: breakfast until 11:00, lunch until 15:00, dinner and snack until the end of the
 * day. The cook's own meal types (no key) have no window. A meal whose window has passed drops
 * off the card entirely, cooked or not. Times are local, as millis since local midnight. Pure,
 * and identical to iOS's `MealWindows`.
 */
object MealWindows {

    const val MILLIS_PER_HOUR = 3_600_000L

    /** Breakfast leads until 11:00. */
    const val BREAKFAST_ENDS = 11 * MILLIS_PER_HOUR

    /** Lunch leads until 15:00. */
    const val LUNCH_ENDS = 15 * MILLIS_PER_HOUR

    /** When the window of the meal type with [builtInKey] ends; null for none before the day's end. */
    fun endsAt(builtInKey: String?): Long? = when (builtInKey) {
        "breakfast" -> BREAKFAST_ENDS
        "lunch" -> LUNCH_ENDS
        else -> null
    }

    /** Whether a meal of type [builtInKey] is still offered at [millisOfDay] (a window ends exclusively). */
    fun isOpen(builtInKey: String?, millisOfDay: Long): Boolean = endsAt(builtInKey)?.let { millisOfDay < it } ?: true

    /** How many windows have ended by [millisOfDay]: the card only changes when this does. */
    fun ended(millisOfDay: Long): Int = listOf(BREAKFAST_ENDS, LUNCH_ENDS).count { it <= millisOfDay }

    /** Millis from [millisOfDay] until the next window ends today; null when none is left. */
    fun untilNextEnd(millisOfDay: Long): Long? =
        listOf(BREAKFAST_ENDS, LUNCH_ENDS).firstOrNull { it > millisOfDay }?.let { it - millisOfDay }
}
