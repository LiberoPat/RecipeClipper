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

    private const val QTY = IngredientScaler.QTY

    /** One language's duration words: shared/tables/<language>/timers.json. */
    private class Patterns(words: LanguageWords) {
        private val units = SharedTables.objects(words.table("timers").getJSONArray("units"))

        /** Each unit's words as one case-insensitive whole-string regex, with its length in seconds. */
        val unitSeconds: List<Pair<Regex, Int>> = units.map {
            Regex(SharedTables.alternation(SharedTables.strings(it.getJSONArray("patterns"))), RegexOption.IGNORE_CASE) to
                it.getInt("seconds")
        }

        /** How a button writes each unit, by its length in seconds. */
        val labels: Map<Int, String> = units.associate { it.getInt("seconds") to it.getString("label") }

        private val unit = units.flatMap { SharedTables.strings(it.getJSONArray("patterns")) }
            .ifEmpty { listOf("(?!)") }
            .joinToString("|", "(", ")")
        private val followOnWords = SharedTables.alternation(words.strings("timers", "followOn"))
        private val range = words.rangeWords

        // groups: 1 quantity, 2 unit. An optional "-" allows "a 20-minute simmer".
        val duration = Regex(
            """(?<![\d.,/])($QTY)(?:\s*(?:[-–—]|$range)\s*(?:$QTY))?\s*-?\s*$unit\b""",
            RegexOption.IGNORE_CASE
        )

        // "1 hour 30 minutes", "2 minutes and 30 seconds". groups: 1 quantity, 2 unit
        val followOn = Regex(
            """^\s*(?:$followOnWords\s+)?($QTY)\s*-?\s*$unit\b""",
            RegexOption.IGNORE_CASE
        )

        fun secondsOf(unit: String): Int = unitSeconds.first { (words, _) -> words.matches(unit) }.second
    }

    private fun patterns(words: LanguageWords): Patterns = words.compiled(Patterns::class) { Patterns(it) }

    private const val MAX_SECONDS = 24 * 3600

    /** [words] null: a language the app has no words for, so no timer. */
    fun parse(step: String, words: LanguageWords? = LanguageWords.ENGLISH): Int? {
        val p = patterns(words ?: return null)
        val first = p.duration.find(step) ?: return null
        var total = toSeconds(p, first.groupValues[1], first.groupValues[2]) ?: return null

        val rest = step.substring(first.range.last + 1)
        p.followOn.find(rest)?.let { follow ->
            val extra = toSeconds(p, follow.groupValues[1], follow.groupValues[2])
            // Only a smaller unit continues the duration ("1 hour" then "30 minutes").
            if (extra != null && p.secondsOf(follow.groupValues[2]) < p.secondsOf(first.groupValues[2])) {
                total += extra
            }
        }
        return total.takeIf { it in 1..MAX_SECONDS }
    }

    private fun toSeconds(p: Patterns, quantity: String, unit: String): Int? {
        val amount = IngredientScaler.parse(quantity) ?: return null
        return (amount * p.secondsOf(unit)).toInt()
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

    /** Short wording for a button, in the recipe's words: "20 min", "1 hr 30 min", "45 sec". */
    fun label(seconds: Int, words: LanguageWords = LanguageWords.ENGLISH): String {
        val labels = patterns(words).labels
        val hr = labels.getValue(3600)
        val min = labels.getValue(60)
        val sec = labels.getValue(1)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours $hr $minutes $min"
            hours > 0 -> "$hours $hr"
            minutes > 0 && secs > 0 -> "$minutes $min $secs $sec"
            minutes > 0 -> "$minutes $min"
            else -> "$secs $sec"
        }
    }
}
