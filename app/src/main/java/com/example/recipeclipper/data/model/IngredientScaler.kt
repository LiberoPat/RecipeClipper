package com.example.recipeclipper.data.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * Scales the leading quantity of an ingredient line ("1 1/2 cups flour",
 * "½ tsp salt", "1-2 tbsp oil"). Pure: string in, string out.
 *
 * Only the leading quantity is touched. A line that doesn't start with a number
 * ("salt to taste"), or whose number is a measurement rather than an amount
 * ("1-inch piece ginger", "2% milk"), is returned unchanged. Wrong scaling is worse
 * than no scaling, so anything ambiguous is left alone.
 */
object IngredientScaler {

    private const val UNICODE_FRACTIONS = "¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"

    private val UNICODE_VALUES = mapOf(
        '¼' to 1 / 4.0, '½' to 1 / 2.0, '¾' to 3 / 4.0, '⅐' to 1 / 7.0, '⅑' to 1 / 9.0,
        '⅒' to 1 / 10.0, '⅓' to 1 / 3.0, '⅔' to 2 / 3.0, '⅕' to 1 / 5.0, '⅖' to 2 / 5.0,
        '⅗' to 3 / 5.0, '⅘' to 4 / 5.0, '⅙' to 1 / 6.0, '⅚' to 5 / 6.0, '⅛' to 1 / 8.0,
        '⅜' to 3 / 8.0, '⅝' to 5 / 8.0, '⅞' to 7 / 8.0
    )

    // "1 1/2", "1½", "1/2", "1.5", "1,5", "½", "2" — tried in that order. A comma followed by
    // one or two digits is a decimal comma; one followed by three ("1,500") may be a thousands
    // separator, so it is never read as part of a quantity, and AMBIGUOUS_COMMA leaves the line.
    internal const val QTY =
        """(?:\d+\s+\d+/\d+|\d+\s*[$UNICODE_FRACTIONS]|\d+/\d+|\d+(?:\.\d+|,\d{1,2}(?!\d))?|[$UNICODE_FRACTIONS])"""

    /** "1,5": the line writes decimals with a comma, so its output does too. */
    internal val DECIMAL_COMMA = Regex("""\d,\d{1,2}(?!\d)""")

    /** "1,500" is 1.5 or 1500 depending on who wrote it: a line holding one is left as written. */
    internal val AMBIGUOUS_COMMA = Regex("""\d,\d{3}""")

    private val DECIMAL_POINT = Regex("""(?<=\d)\.(?=\d)""")

    /** "2.25" as "2,25" when [comma], for a line that writes its decimals that way. */
    internal fun withSeparator(text: String, comma: Boolean): String =
        if (comma) DECIMAL_POINT.replace(text, ",") else text

    /** The patterns that read one language's words (shared/tables/<language>/amounts.json). */
    internal class Patterns(val words: LanguageWords) {
        private val units = UnitPatterns.of(words)

        // groups: 1 leading space, 2 quantity, 3 range separator, 4 range upper bound
        val leading = Regex("""^(\s*)($QTY)(?:(\s*[-–—]\s*|\s+${words.rangeWords}\s+)($QTY))?""")

        // What follows the number when it is a size or a percentage, not an amount.
        val notAnAmount = Regex(
            """^\s*-?\s*""" + (listOf("%") + words.strings("amounts", "sizes") + listOf("""cm\b""", """mm\b"""))
                .joinToString("|", "(?:", ")"),
            RegexOption.IGNORE_CASE
        )

        // A quantity followed by a unit. groups: 1 quantity, 2 space, 3 unit
        val qtyUnit = Regex("""($QTY)(\s*)${units.captured}""", RegexOption.IGNORE_CASE)

        // "1 cup (120 g) flour": a second measure of the same amount, right after a unit word.
        // A parenthesis straight after the number ("1 (14 oz) can") or after a container word
        // ("1 can (14 oz)") is a package size, not an alternate measure, and is never scaled.
        val altParen = Regex("""^(\s*${units.plain}\s*\()([^)]*)(\))""", RegexOption.IGNORE_CASE)

        // "1 cup/120 grams flour". groups: 1 prefix, 2 quantity, 3 space, 4 unit
        val altSlash = Regex(
            """^(\s*${units.plain}\s*/\s*)($QTY)(\s*)${units.captured}""",
            RegexOption.IGNORE_CASE
        )

        // The word joining the two parts of a compound amount: "1 cup plus 2 tbsp", "1 cup + 2 tbsp".
        val continuation = (words.strings("amounts", "continuation") + """\+\s*""").joinToString("|", "(?:", ")")

        // "1 cup plus 2 tbsp (140 g) flour": the second part of a compound amount, which scales
        // with the first. groups: 1 prefix, 2 quantity, 3 space, 4 unit
        val continued = Regex(
            """^(\s*${units.plain}\s*$continuation)($QTY)(\s*)${units.captured}""",
            RegexOption.IGNORE_CASE
        )
    }

    internal fun patterns(words: LanguageWords): Patterns = words.compiled(Patterns::class) { Patterns(it) }

    // Nearest-fraction table used when formatting; anything further than TOLERANCE
    // from all of these falls back to a plain decimal.
    private val FRACTIONS = listOf(
        0.0 to "", 1 / 8.0 to "1/8", 1 / 4.0 to "1/4", 1 / 3.0 to "1/3", 3 / 8.0 to "3/8",
        1 / 2.0 to "1/2", 5 / 8.0 to "5/8", 2 / 3.0 to "2/3", 3 / 4.0 to "3/4",
        7 / 8.0 to "7/8", 1.0 to ""
    )
    private const val TOLERANCE = 0.02

    /** [words] null: a language the app has no words for, so the line stays as written. */
    fun scale(line: String, factor: Double, words: LanguageWords? = LanguageWords.ENGLISH): String {
        if (factor == 1.0 || words == null) return line
        if (AMBIGUOUS_COMMA.containsMatchIn(line)) return line
        val p = patterns(words)
        val match = p.leading.find(line) ?: return line
        val rest = line.substring(match.range.last + 1)
        if (p.notAnAmount.containsMatchIn(rest)) return line

        val comma = DECIMAL_COMMA.containsMatchIn(line)
        val low = parse(match.groupValues[2]) ?: return line
        val upperRaw = match.groupValues[4]
        val scaled = if (upperRaw.isEmpty()) {
            formatLeading(low * factor, comma)
        } else {
            val high = parse(upperRaw) ?: return line
            formatLeading(low * factor, comma) + match.groupValues[3] + formatLeading(high * factor, comma)
        }
        return match.groupValues[1] + scaled + scaleContinuation(p, rest, factor, comma)
    }

    // A line that writes "1,5" reads decimals, not fractions: "1,5 kg" x 1.5 is "2,25 kg".
    private fun formatLeading(value: Double, comma: Boolean): String =
        if (comma) withSeparator(formatDecimal(value), true) else format(value)

    private fun formatDecimal(value: Double): String =
        BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Scales "plus 2 tbsp" and whatever alternate measure follows it, else just the alternate. */
    private fun scaleContinuation(p: Patterns, rest: String, factor: Double, comma: Boolean): String {
        val m = p.continued.find(rest) ?: return scaleAlternateMeasure(p, rest, factor, comma)
        val unit = MeasureUnit.fromText(m.groupValues[4], p.words) ?: return scaleAlternateMeasure(p, rest, factor, comma)
        val value = parse(m.groupValues[2]) ?: return scaleAlternateMeasure(p, rest, factor, comma)
        val tail = m.groupValues[3] + m.groupValues[4] + rest.substring(m.range.last + 1)
        return m.groupValues[1] + formatFor(unit, value * factor, comma) +
                scaleAlternateMeasure(p, tail, factor, comma)
    }

    /** Keeps "(120 g)" or "/120 grams" in step with the leading amount that was just scaled. */
    private fun scaleAlternateMeasure(p: Patterns, rest: String, factor: Double, comma: Boolean): String {
        p.altParen.find(rest)?.let { m ->
            val inner = p.qtyUnit.replace(m.groupValues[2]) { scalePair(p, it, factor, comma) }
            return m.groupValues[1] + inner + m.groupValues[3] + rest.substring(m.range.last + 1)
        }
        p.altSlash.find(rest)?.let { m ->
            val unit = MeasureUnit.fromText(m.groupValues[4], p.words)
            val value = parse(m.groupValues[2])
            if (unit != null && value != null) {
                return m.groupValues[1] + formatFor(unit, value * factor, comma) + m.groupValues[3] +
                        m.groupValues[4] + rest.substring(m.range.last + 1)
            }
        }
        return rest
    }

    private fun scalePair(p: Patterns, match: MatchResult, factor: Double, comma: Boolean): String {
        val unit = MeasureUnit.fromText(match.groupValues[3], p.words) ?: return match.value
        val value = parse(match.groupValues[1]) ?: return match.value
        return formatFor(unit, value * factor, comma) + match.groupValues[2] + match.groupValues[3]
    }

    // Metric amounts read better as "240" or "7.5" than as "240" or "7 1/2".
    private fun formatFor(unit: MeasureUnit, value: Double, comma: Boolean): String = when {
        unit.metric -> withSeparator(formatMetric(value), comma)
        comma -> withSeparator(formatDecimal(value), true)
        else -> format(value)
    }

    internal fun formatMetric(value: Double): String {
        val scale = if (value >= 10) 0 else 1
        return BigDecimal(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    internal fun parse(quantity: String): Double? {
        // QTY only lets a comma through as a decimal comma, never before three digits.
        val q = quantity.trim().replace(',', '.')
        val last = q.last()
        UNICODE_VALUES[last]?.let { fraction ->
            val whole = q.dropLast(1).trim()
            return (if (whole.isEmpty()) 0.0 else whole.toDoubleOrNull() ?: return null) + fraction
        }
        var total = 0.0
        for (part in q.split(Regex("""\s+"""))) {
            total += if ('/' in part) {
                val (n, d) = part.split('/').map { it.toDoubleOrNull() ?: return null }
                if (d == 0.0) return null
                n / d
            } else {
                part.toDoubleOrNull() ?: return null
            }
        }
        return total
    }

    internal fun format(value: Double): String {
        val whole = floor(value).toInt()
        val fraction = value - whole
        val (nearest, text) = FRACTIONS.minBy { abs(it.first - fraction) }
        if (abs(nearest - fraction) <= TOLERANCE) {
            val w = if (nearest == 1.0) whole + 1 else whole
            when {
                text.isNotEmpty() -> return if (w == 0) text else "$w $text"
                w > 0 -> return "$w"
            }
        }
        return BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
