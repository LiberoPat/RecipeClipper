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

    private const val MAX_PART = 10_000_000L

    /** What [format] writes for a language with no tables: English's symbols, "1h 30m", "1h", "20m". */
    private val ENGLISH_SHAPE = Regex("^(?:(\\d+)h(?: (\\d+)m)?|(\\d+)m)$")

    /**
     * How many minutes [raw] says, read the way [format] reads it (#378: "Under 30 min"), or null
     * when it isn't a time the app can read: blank, a zero total ("PT0S", which [format] hides),
     * a range ("20 to 25 minutes"), a word ("Overnight") or a figure too large for an Int. Never
     * a guess. Reads ISO, a phrase in the recipe's words and what [format] writes back ("1h 30m",
     * "1h 30min"), so a stored time reads as it shows. With [words] null (a language with no
     * tables) only ISO and English's "1h 30m" are read. Iso seconds round as [format] rounds them.
     */
    fun minutes(raw: String, words: LanguageWords? = LanguageWords.ENGLISH): Int? {
        val text = raw.trim()
        if (text.isBlank()) return null
        // A part that is absent counts nothing; one past ten million is no real time (and keeps the
        // sums below from overflowing, which iOS's Int would trap on).
        fun part(digits: String): Long? = if (digits.isEmpty()) 0 else digits.toLongOrNull()?.takeIf { it <= MAX_PART }
        val iso = ISO_DURATION.find(text)?.takeIf { m -> m.groupValues.drop(1).any { it.isNotBlank() } }
        val total: Long? = when {
            iso != null -> {
                val g = iso.groupValues
                val seconds = g[4].toDoubleOrNull() ?: 0.0
                val days = part(g[1])
                val hours = part(g[2])
                val minutes = part(g[3])
                if (days == null || hours == null || minutes == null) null
                else days * 24 * 60 + hours * 60 + minutes + (seconds / 60.0).roundToInt()
            }
            words == null -> ENGLISH_SHAPE.matchEntire(text)?.groupValues?.let { g ->
                val hours = part(g[1])
                val minutes = part(g[2].ifEmpty { g[3] })
                if (hours == null || minutes == null) null else hours * 60 + minutes
            }
            else -> patterns(words).phrase.matchEntire(text)?.groupValues?.let { g ->
                val hours = part(g[1])
                val minutes = part(g[2].ifEmpty { g[3] })
                if (hours == null || minutes == null) null else hours * 60 + minutes
            }
        }
        return total?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
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
