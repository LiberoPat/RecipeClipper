package com.example.recipeclipper.data.model

import java.util.Locale

/**
 * Finds the cooking time a step asks for ("simmer for 20 minutes", "bake 25 to 30 minutes",
 * "1 hour 30 minutes") so cook mode can offer a timer. Pure: string in, seconds out.
 *
 * A range uses its lower bound, so the timer goes off in time to check. A step with no
 * stated duration ("whisk until smooth") returns null and gets no timer: never guess one.
 */
object StepTimers {

    private const val UNIT = """(hours?|hrs?|minutes?|mins?|seconds?|secs?)"""
    private const val QTY = IngredientScaler.QTY

    // groups: 1 quantity, 2 unit. An optional "-" allows "a 20-minute simmer".
    private val DURATION = Regex(
        """(?<![\d.,/⁄])($QTY)(?:\s*(?:[-–—]|to)\s*(?:$QTY))?\s*-?\s*$UNIT\b""",
        RegexOption.IGNORE_CASE
    )

    // "1 hour 30 minutes", "2 minutes and 30 seconds". groups: 1 quantity, 2 unit
    private val FOLLOW_ON = Regex(
        """^\s*(?:and\s+)?($QTY)\s*-?\s*$UNIT\b""",
        RegexOption.IGNORE_CASE
    )

    private const val MAX_SECONDS = 24 * 3600

    fun parse(step: String): Int? {
        val first = DURATION.find(step) ?: return null
        var total = toSeconds(first.groupValues[1], first.groupValues[2]) ?: return null

        val rest = step.substring(first.range.last + 1)
        FOLLOW_ON.find(rest)?.let { follow ->
            val extra = toSeconds(follow.groupValues[1], follow.groupValues[2])
            // Only a smaller unit continues the duration ("1 hour" then "30 minutes").
            if (extra != null && unitSeconds(follow.groupValues[2]) < unitSeconds(first.groupValues[2])) {
                total += extra
            }
        }
        return total.takeIf { it in 1..MAX_SECONDS }
    }

    private fun unitSeconds(unit: String): Int = when (unit.lowercase().first()) {
        'h' -> 3600
        'm' -> 60
        else -> 1
    }

    private fun toSeconds(quantity: String, unit: String): Int? {
        val amount = IngredientScaler.parse(quantity) ?: return null
        return (amount * unitSeconds(unit)).toInt()
    }

    /** "20:00", or "1:05:00" from an hour up. */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        } else {
            String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }

    /** Short wording for a button: "20 min", "1 hr 30 min", "45 sec". */
    fun label(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours hr $minutes min"
            hours > 0 -> "$hours hr"
            minutes > 0 && secs > 0 -> "$minutes min $secs sec"
            minutes > 0 -> "$minutes min"
            else -> "$secs sec"
        }
    }
}
