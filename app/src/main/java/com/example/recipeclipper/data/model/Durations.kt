package com.example.recipeclipper.data.model

import kotlin.math.roundToInt

/**
 * A recipe's prep, cook and total times as the app shows them (the times rule in CLAUDE.md's
 * parsing rules). Pure, so the parsers and the tour's sample (#179) share it; iOS's `Durations`
 * is the same, pinned by the differential corpus's `Dur` rows.
 */
object Durations {

    private val ISO_DURATION = Regex(
        "^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$",
        RegexOption.IGNORE_CASE
    )

    /**
     * A duration phrase in the recipe's words, as Condé Nast sites (Bon Appétit, Epicurious) publish
     * instead of ISO: "20 minutes", "1 hour", "1 hour 30 minutes", "1 hr, 5 mins",
     * "1 hour and 30 minutes". Whole-string only (used with matchEntire): whole numbers, an
     * hours part and/or a minutes part, nothing else. A range ("1-2 hours", "20 to 25
     * minutes"), a fraction or a word ("Overnight") does not match and is shown as written.
     */
    private class Patterns(words: LanguageWords) {
        private val hours = SharedTables.alternation(words.strings("durations", "hours"))
        private val minutes = SharedTables.alternation(words.strings("durations", "minutes"))
        private val joiners = SharedTables.alternation(words.strings("durations", "joiners"))

        val phrase = Regex(
            "\\s*(?:(\\d+)\\s*$hours" +
                "(?:\\s*,?\\s*(?:\\b$joiners\\s+)?(\\d+)\\s*$minutes)?" +
                "|(\\d+)\\s*$minutes)\\s*",
            RegexOption.IGNORE_CASE
        )
        val hourSymbol: String = words.table("durations").getString("hourSymbol")
        val minuteSymbol: String = words.table("durations").getString("minuteSymbol")
    }

    private fun patterns(words: LanguageWords): Patterns = words.compiled(Patterns::class) { Patterns(it) }

    /**
     * Turns an ISO-8601 duration like "PT1H30M", or a plain phrase in the recipe's words like
     * "1 hour 30 minutes", into "1h 30m". Either one totalling zero ("PT0S", "P0D",
     * "0 minutes") is null, so the label is hidden rather than showing "PT0S". Anything else
     * is returned as written (trimmed): never guess at "Overnight" or "20 to 25 minutes".
     * With [words] null (a language the app has no words for) only ISO is read, and written
     * back with English's symbols.
     */
    fun format(raw: String, words: LanguageWords? = LanguageWords.ENGLISH): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val d = patterns(words ?: LanguageWords.ENGLISH)

        ISO_DURATION.find(text)?.takeIf { m -> m.groupValues.drop(1).any { it.isNotBlank() } }?.let { match ->
            val days = match.groupValues[1].toIntOrNull() ?: 0
            val hours = match.groupValues[2].toIntOrNull() ?: 0
            val minutes = match.groupValues[3].toIntOrNull() ?: 0
            val seconds = match.groupValues[4].toDoubleOrNull() ?: 0.0
            return renderMinutes(days * 24 * 60 + hours * 60 + minutes + (seconds / 60.0).roundToInt(), text, d)
        }

        if (words == null) return text
        d.phrase.matchEntire(text)?.let { match ->
            val g = match.groupValues
            // A figure too large for an Int is not a real time: show it as written.
            val hours = if (g[1].isEmpty()) 0 else g[1].toIntOrNull() ?: return text
            val minutesText = g[2].ifEmpty { g[3] }
            val minutes = if (minutesText.isEmpty()) 0 else minutesText.toIntOrNull() ?: return text
            return renderMinutes(hours * 60 + minutes, text, d)
        }

        return text
    }

    /** "1h 30m", "1h" or "20m"; null for a zero total; [asWritten] for a total that
     *  overflowed to a negative number. */
    private fun renderMinutes(totalMinutes: Int, asWritten: String, d: Patterns): String? {
        if (totalMinutes == 0) return null
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 && m > 0 -> "$h${d.hourSymbol} $m${d.minuteSymbol}"
            h > 0 -> "$h${d.hourSymbol}"
            m > 0 -> "$m${d.minuteSymbol}"
            else -> asWritten
        }
    }
}
