package com.example.recipeclipper.data.model

import kotlin.math.round

/**
 * Converts temperatures in instruction text between °F and °C ("Preheat to 350°F",
 * "bake at 180 degrees C", "165F"). Pure: string in, string out.
 *
 * [TemperatureUnit.CELSIUS] and [TemperatureUnit.FAHRENHEIT] convert to that scale,
 * [TemperatureUnit.AS_WRITTEN] leaves the text alone. Independent of [UnitSystem] — see
 * [TemperatureUnit]'s doc. Ovens are rounded to the numbers recipes actually use (350°F is
 * 180°C, not 177°C).
 *
 * A number is only a temperature if it is 2-3 digits and followed by F or C. Without a
 * degree sign, "degrees" or a full word ("350F", "350 F") it must also be a plausible
 * cooking temperature, so "2 C flour" (cups) and "20 C of sugar" are left alone.
 * A pair like "350°F (180°C)" collapses to whichever half already matches the target.
 */
object TemperatureConverter {

    private enum class Scale(val letter: Char) { F('F'), C('C') }

    private class Temp(
        val low: Int,
        val separator: String,
        val high: Int?,
        val scale: Scale
    )

    // The scale, degree and range words are shared with iOS:
    // shared/tables/<language>/temperature.json and ranges.json.
    private class Patterns(words: LanguageWords) {
        private val fahrenheitWords = words.strings("temperature", "fahrenheit")
        private val scaleWords = (fahrenheitWords + words.strings("temperature", "celsius")).ifEmpty { listOf("(?!)") }
        val fahrenheitWord = Regex(SharedTables.alternation(fahrenheitWords), RegexOption.IGNORE_CASE)
        private val degrees = SharedTables.alternation(words.strings("temperature", "degrees"))

        // groups: 1 low, 2 range separator, 3 high, 4 connector, 5 unit
        private val temp =
            """(\d{2,3})(?:(\s*(?:[-–—]|${words.rangeWords})\s*)(\d{2,3}))?(\s*[°º˚]\s*|\s+$degrees\s+|\s?)""" +
                    """((?i:${scaleWords.joinToString("|")})|[FC])(?![A-Za-z])"""

        val tempAnywhere = Regex("""(?<![\d.,/])$temp""")
        val tempAtStart = Regex("""^$temp""")
    }

    /** A bare "180 C" or "350 F" is a temperature only in these ranges; the scaler reads them too (#135). */
    internal val PLAUSIBLE_CELSIUS = 40..320
    private val PLAUSIBLE_FAHRENHEIT = 100..600

    private val PAIR_JOINER = Regex("""^\s*([(/])\s*""")
    private val CLOSING_PAREN = Regex("""^\s*\)""")

    /** [words] null: a language the app has no words for, so the text stays as written. */
    fun convert(text: String, unit: TemperatureUnit, words: LanguageWords? = LanguageWords.ENGLISH): String {
        if (words == null) return text
        val p = words.compiled(Patterns::class) { Patterns(it) }
        val target = when (unit) {
            TemperatureUnit.AS_WRITTEN -> return text
            TemperatureUnit.CELSIUS -> Scale.C
            TemperatureUnit.FAHRENHEIT -> Scale.F
        }

        val out = StringBuilder()
        var cursor = 0
        while (true) {
            val match = p.tempAnywhere.find(text, cursor) ?: break
            val temp = parse(p, match)
            val end = match.range.last + 1
            if (temp == null) {
                out.append(text, cursor, end)
                cursor = end
                continue
            }

            // "350°F (180°C)" or "180°C/350°F": keep the half that already matches.
            val pair = findPair(p, text, end, temp)
            if (pair != null) {
                out.append(text, cursor, match.range.first)
                out.append(if (temp.scale == target) match.value else pair.value)
                cursor = pair.end
                continue
            }

            out.append(text, cursor, match.range.first)
            out.append(if (temp.scale == target) match.value else render(temp, target))
            cursor = end
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /**
     * Every temperature [text] states, each as its keys ("350F", "180-200C"); "350°F (180°C)"
     * is one temperature with two keys. What [ShortStepCheck] compares, so a short step can't
     * change a scale or drop an oven setting. Empty for [words] null.
     */
    fun temperatures(text: String, words: LanguageWords?): List<List<String>> {
        if (words == null) return emptyList()
        val p = words.compiled(Patterns::class) { Patterns(it) }
        val found = mutableListOf<List<String>>()
        var cursor = 0
        while (true) {
            val match = p.tempAnywhere.find(text, cursor) ?: break
            val temp = parse(p, match)
            cursor = match.range.last + 1
            if (temp == null) continue
            val pair = findPair(p, text, cursor, temp)
            found += listOfNotNull(key(temp), pair?.let { key(it.temp) })
            if (pair != null) cursor = pair.end
        }
        return found
    }

    private fun key(t: Temp) = "${t.low}${t.high?.let { "-$it" } ?: ""}${t.scale.letter}"

    private class Pair(val value: String, val end: Int, val temp: Temp)

    /** The other-scale temperature written straight after [first], if there is one. */
    private fun findPair(p: Patterns, text: String, firstEnd: Int, first: Temp): Pair? {
        val rest = text.substring(firstEnd)
        val joiner = PAIR_JOINER.find(rest) ?: return null
        val afterJoiner = rest.substring(joiner.value.length)
        val match = p.tempAtStart.find(afterJoiner) ?: return null
        val second = parse(p, match) ?: return null
        if (second.scale == first.scale) return null

        var end = firstEnd + joiner.value.length + match.value.length
        if (joiner.groupValues[1] == "(") {
            val close = CLOSING_PAREN.find(text.substring(end)) ?: return null
            end += close.value.length
        }
        return Pair(match.value, end, second)
    }

    private fun parse(p: Patterns, match: MatchResult): Temp? {
        val low = match.groupValues[1].toInt()
        val high = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
        val unit = match.groupValues[5]
        val scale = if (unit == "F" || p.fahrenheitWord.matches(unit)) Scale.F else Scale.C

        val connector = match.groupValues[4]
        val explicit = unit.length > 1 || connector.any { it in "°º˚" } || connector.isNotBlank()
        if (!explicit) {
            val range = if (scale == Scale.F) PLAUSIBLE_FAHRENHEIT else PLAUSIBLE_CELSIUS
            if (low !in range || (high != null && high !in range)) return null
        }
        return Temp(low, match.groupValues[2], high, scale)
    }

    private fun render(temp: Temp, target: Scale): String {
        fun convert(v: Int) = if (target == Scale.C) toCelsius(v) else toFahrenheit(v)
        val high = temp.high?.let { temp.separator + convert(it) } ?: ""
        return "${convert(temp.low)}$high°${target.letter}"
    }

    // Oven range to the nearest 10, food-safety and dough temperatures to the nearest degree.
    private fun toCelsius(f: Int): Int {
        val c = (f - 32) * 5 / 9.0
        return if (c >= 120) (round(c / 10) * 10).toInt() else round(c).toInt()
    }

    // Oven range to the nearest 25 (350, 375, 400, 425...), lower temperatures to the nearest 5.
    private fun toFahrenheit(c: Int): Int {
        val f = c * 9 / 5.0 + 32
        return if (f >= 200) (round(f / 25) * 25).toInt() else (round(f / 5) * 5).toInt()
    }
}
