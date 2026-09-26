package com.example.recipeclipper.data.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * Scales the leading quantity of an ingredient line ("1 1/2 cups flour",
 * "½ tsp salt", "1-2 tbsp oil"). Pure: string in, string out.
 *
 * The leading quantity is scaled, with the measures that restate it: "(120 g)" after the unit,
 * a second part ("plus 2 tbsp", "minus 2 tbsp"), an alternative ("or 1/2 cup oil", #61), a part
 * added later ("plus 3 egg yolks", #62) and a total in brackets after the name ("(8 ½ ounces)",
 * #63). A line that doesn't start with a number ("salt to taste"), or whose number is a
 * measurement rather than an amount ("1-inch piece ginger", "2% milk"), is returned unchanged,
 * and so is one where any of those can't be scaled with the rest. Wrong scaling is worse than
 * no scaling, so anything ambiguous is left alone.
 */
object IngredientScaler {

    private const val UNICODE_FRACTIONS = "¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"

    private val UNICODE_VALUES = mapOf(
        '¼' to 1 / 4.0, '½' to 1 / 2.0, '¾' to 3 / 4.0, '⅐' to 1 / 7.0, '⅑' to 1 / 9.0,
        '⅒' to 1 / 10.0, '⅓' to 1 / 3.0, '⅔' to 2 / 3.0, '⅕' to 1 / 5.0, '⅖' to 2 / 5.0,
        '⅗' to 3 / 5.0, '⅘' to 4 / 5.0, '⅙' to 1 / 6.0, '⅚' to 5 / 6.0, '⅛' to 1 / 8.0,
        '⅜' to 3 / 8.0, '⅝' to 5 / 8.0, '⅞' to 7 / 8.0
    )

    // "1-1/2", "1 1/2", "2 and 1/2", "1 and ½", "1½", "1/2", "1.5", "1,5", "½", "2" — tried in
    // that order. A whole number, a dash and a fraction with no spaces ("1-1/2", "2–½", as Taste
    // of Home writes them) are a mixed number, never a range down to the fraction (#125), and
    // [parse] reads only a proper fraction there. Not after a slash or a decimal, so "1/2-3/4"
    // and "0.17-1/3" stay ranges. The "and" is the language's (amounts.json "mixedJoiners").
    // A fraction's slash may be the typographic U+2044 ("1⁄2", as BBC Good Food writes it), a
    // symbol rather than a word, so it stays here. A comma followed by one or two digits is a
    // decimal comma; one followed by three ("1,500") may be a thousands separator, so it is
    // never read as part of a quantity, and AMBIGUOUS_COMMA leaves the line. Where the language writes thousands
    // with a dot (amounts.json "thousandsDot"), "1.500" is 1500 (#76): only a dot before
    // exactly three digits, so "1.5" and "0.25" stay decimals.
    private fun qtyPattern(joiner: String, thousandsDot: Boolean) =
        """(?:(?<![\d.,])(?<!\d[/⁄])\d+[-–—](?:\d+[/⁄]\d+|[$UNICODE_FRACTIONS])|""" +
            """\d+\s+(?:$joiner\s+)?\d+[/⁄]\d+|\d+\s+$joiner\s+[$UNICODE_FRACTIONS]|\d+\s*[$UNICODE_FRACTIONS]|""" +
            """\d+[/⁄]\d+|""" + (if (thousandsDot) """\d{1,3}\.\d{3}(?![\d.,])|""" else "") +
            """\d+(?:\.\d+|,\d{1,2}(?!\d))?|[$UNICODE_FRACTIONS])"""

    /** "1,5": the line writes decimals with a comma, so its output does too. */
    internal val DECIMAL_COMMA = Regex("""\d,\d{1,2}(?!\d)""")

    /** "1,500" is 1.5 or 1500 depending on who wrote it: a line holding one is left as written. */
    internal val AMBIGUOUS_COMMA = Regex("""\d,\d{3}""")

    /** Where dots mark thousands, "1.500,5" or "1.2345" is no amount the pattern reads whole. */
    private val AMBIGUOUS_DOT = Regex("""\d\.\d{3}[\d.,]""")

    private val THOUSANDS_DOT = Regex("""(?<=\d)\.(?=\d{3}(?!\d))""")

    private val DECIMAL_POINT = Regex("""(?<=\d)\.(?=\d)""")

    /** "2.25" as "2,25" when [comma], for a line that writes its decimals that way. */
    internal fun withSeparator(text: String, comma: Boolean): String =
        if (comma) DECIMAL_POINT.replace(text, ",") else text

    /** The patterns that read one language's words (shared/tables/<language>/amounts.json). */
    internal class Patterns(val words: LanguageWords) {
        private val units = UnitPatterns.of(words)

        private val amounts = words.table("amounts")

        /** "1.500 g" is 1500 g (amounts.json "thousandsDot"). */
        val thousandsDot: Boolean = amounts.getBoolean("thousandsDot")

        /** Lines are "name amount" ("醤油 大さじ1"), read by [TrailingAmount] (amounts.json "amountAfterName"). */
        val amountAfterName: Boolean = amounts.optBoolean("amountAfterName", false)

        /** A quantity, in this language's words ("2 and 1/2"). No capturing group. */
        val qty = qtyPattern(SharedTables.alternation(words.strings("amounts", "mixedJoiners")), thousandsDot)

        // "1 taza y media", "2 e meia": a half in words, which the quantity pattern can't read.
        private val spelledHalf = Regex(
            """(?<!\p{L})${SharedTables.alternation(words.strings("amounts", "spelledHalves"))}(?!\p{L})""",
            RegexOption.IGNORE_CASE
        )

        /**
         * A line whose amount can't be read without guessing: "1,500" (1.5 or 1500?), a half in
         * words, or where dots mark thousands a number like "1.500,5". It stays as written.
         */
        fun unreadable(line: String): Boolean =
            AMBIGUOUS_COMMA.containsMatchIn(line) || spelledHalf.containsMatchIn(line) ||
                (thousandsDot && AMBIGUOUS_DOT.containsMatchIn(line))

        /** A quantity this pattern matched, read with this language's thousands separator. */
        fun parse(quantity: String): Double? = IngredientScaler.parse(quantity, thousandsDot)

        // groups: 1 leading space, 2 quantity, 3 range separator, 4 range upper bound
        val leading = Regex("""^(\s*)($qty)(?:(\s*[-–—]\s*|\s+${words.rangeWords}\s+)($qty))?""")

        // What follows the number when it is a size or a percentage, not an amount.
        val notAnAmount = Regex(
            """^\s*-?\s*""" + (listOf("%") + words.strings("amounts", "sizes") + listOf("""cm\b""", """mm\b"""))
                .joinToString("|", "(?:", ")"),
            RegexOption.IGNORE_CASE
        )

        // A quantity followed by a unit. groups: 1 quantity, 2 space, 3 unit
        val qtyUnit = Regex("""($qty)(\s*)${units.captured}""", RegexOption.IGNORE_CASE)

        // "1 cup (120 g) flour": a second measure of the same amount, right after a unit word.
        // A parenthesis straight after the number ("1 (14 oz) can") or after a container word
        // ("1 can (14 oz)") is a package size, not an alternate measure, and is never scaled.
        val altParen = Regex("""^(\s*${units.plain}\s*\()([^)]*)(\))""", RegexOption.IGNORE_CASE)

        // "1 cup/120 grams flour", or a range "250 - 300 g / 8 - 10 oz pasta".
        // groups: 1 prefix, 2 quantity, 3 range separator, 4 range upper bound, 5 space, 6 unit
        val altSlash = Regex(
            """^(\s*${units.plain}\s*/\s*)($qty)(?:(\s*[-–—]\s*|\s+${words.rangeWords}\s+)($qty))?(\s*)${units.captured}""",
            RegexOption.IGNORE_CASE
        )

        // The word joining the two parts of a compound amount: "1 cup plus 2 tbsp", "1 cup + 2 tbsp".
        val continuation = (words.strings("amounts", "continuation") + """\+\s*""").joinToString("|", "(?:", ")")

        /** A second part taken away rather than added: "2 cups minus 2 tbsp" (#62). */
        val subtraction = SharedTables.alternation(words.strings("amounts", "subtractions"))

        // "1 cup plus 2 tbsp (140 g) flour": the second part of a compound amount, which scales
        // with the first. groups: 1 prefix, 2 quantity, 3 space, 4 unit
        val continued = Regex(
            """^(\s*${units.plain}\s*(?:$continuation|$subtraction))($qty)(\s*)${units.captured}""",
            RegexOption.IGNORE_CASE
        )

        /** The amount is a measure (it has a unit), not a count: "2 cups", "¾ de taza". */
        val unitAtStart = Regex(
            // Wrapped, since iOS's ICU rejects a quantifier straight after "(?!)" (no prefixes).
            """^\s*(?:${SharedTables.alternation(words.strings("amounts", "unitPrefixes"))})?${units.plain}""",
            RegexOption.IGNORE_CASE
        )

        // A second amount later in the line (#61, #62). Group 1 holds an alternative's word
        // ("or 1/2 cup oil"), an amount standing for the whole; otherwise the amount is a part
        // added or taken away ("plus 3 egg yolks", "+ 1 egg yolk", "minus 2 tbsp").
        val joined = Regex(
            """(?:(?<!\p{L})(${SharedTables.alternation(words.strings("amounts", "alternatives"))})|""" +
                """(?<!\p{L})${SharedTables.alternation(words.strings("amounts", "additions"))}|""" +
                """(?<!\p{L})$subtraction|\+\s*)(?=$qty)""",
            RegexOption.IGNORE_CASE
        )

        private val rangeSeparator = """\s*[-–—]\s*|\s+${words.rangeWords}\s+"""

        // An amount in a bracket: a quantity or range and its unit.
        // groups: 1 quantity, 2 range separator, 3 upper bound, 4 space, 5 unit
        val measure = Regex("""($qty)(?:($rangeSeparator)($qty))?(\s*)${units.captured}""", RegexOption.IGNORE_CASE)

        private val plainMeasure = """(?:$qty)(?:(?:$rangeSeparator)(?:$qty))?\s*${units.plain}"""

        // "(about 1/4 cup)", "(200ml/7fl oz)", "(ca. 800g)", "(180 g.)": a bracket holding nothing
        // but an amount, perhaps written two ways, perhaps "about" it (#63).
        val total = Regex(
            """^\s*(?:${SharedTables.alternation(words.strings("amounts", "approximately"))}\s*)?(?:[~≈]\s*)?""" +
                """$plainMeasure(?:\s*/\s*$plainMeasure)?\s*\.?\s*$""",
            RegexOption.IGNORE_CASE
        )

        /** "(8 oz each)": a per-item size. */
        val perItem = Regex(
            """(?<!\p{L})${SharedTables.alternation(words.strings("amounts", "perItem"))}(?!\p{L})""",
            RegexOption.IGNORE_CASE
        )

        private val containers = SharedTables.alternation(words.strings("amounts", "containers"))

        /** "1 can (14 oz)": a bracket straight after a container word is the container's size. */
        val afterContainer = Regex("""(?<!\p{L})$containers\s*$""", RegexOption.IGNORE_CASE)

        /** "1 lata leite condensado (397 g)": one container, so its bracket is the container's size. */
        val containerFirst = Regex("""^\s*$containers(?!\p{L})""", RegexOption.IGNORE_CASE)
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

    /**
     * [words] null: a language the app has no words for, so the line stays as written.
     * [bracket] is the model's definite answer about a count's bracket (#104), if any: null
     * keeps such a line as written, as before.
     */
    fun scale(
        line: String,
        factor: Double,
        words: LanguageWords? = LanguageWords.ENGLISH,
        bracket: CountBracket? = null
    ): String {
        if (factor == 1.0 || words == null) return line
        val p = patterns(words)
        if (p.amountAfterName) return TrailingAmount.scale(line, factor, words)
        if (p.unreadable(line)) return line
        val comma = DECIMAL_COMMA.containsMatchIn(line)
        return walk(p, line, factor, comma, bracket)?.joinToString("") { it.text } ?: line
    }

    /**
     * True when [line] stays as written only because of a count's bracket holding nothing but
     * an amount ("4 Apfel (ca. 800g)", [BracketKind.COUNT]): the one question the model is
     * asked about it (#104). Any other reason to leave the line alone means no question.
     */
    fun needsCountDecision(line: String, words: LanguageWords?): Boolean {
        if (words == null) return false
        val p = patterns(words)
        if (p.amountAfterName || p.unreadable(line)) return false
        return walk(p, line, 1.0, false, null) == null && walk(p, line, 1.0, false, CountBracket.EACH) != null
    }

    /**
     * One amount of a line and the text it governs, up to the next amount's joining word:
     * "1 cup butter " and "1/2 cup oil" in "1 cup butter or 1/2 cup oil". [text] is the side
     * scaled, followed by the joining word as written ([end] is where that word starts).
     */
    private class Side(val start: Int, val end: Int, val text: String)

    /**
     * Where each amount of [line] starts and ends: the first, then any after an alternative's
     * or a second part's word ("or 1/2 cup oil", "plus 3 egg yolks"). Null when the line can't be
     * scaled whole, so the converter reads it as one amount, as before #61.
     */
    internal fun sides(p: Patterns, line: String): List<IntRange>? =
        walk(p, line, 1.0, false)?.map { it.start until it.end }

    // Scales every amount of the line, or none: null leaves the whole line as written.
    private fun walk(
        p: Patterns, line: String, factor: Double, comma: Boolean, bracket: CountBracket? = null
    ): List<Side>? {
        val sides = mutableListOf<Side>()
        var start = 0
        var alternative = false
        while (true) {
            val text = line.substring(start)
            val match = p.leading.find(text) ?: return null
            val rest = text.substring(match.range.last + 1)
            if (p.notAnAmount.containsMatchIn(rest)) return null
            val measure = p.unitAtStart.containsMatchIn(rest)
            // "or 2 small onions": an alternative needs a unit to be read as one (#61).
            if (alternative && !measure) return null

            val low = p.parse(match.groupValues[2]) ?: return null
            val upperRaw = match.groupValues[4]
            val scaled = if (upperRaw.isEmpty()) {
                formatLeading(low * factor, comma)
            } else {
                val high = p.parse(upperRaw) ?: return null
                formatLeading(low * factor, comma) + match.groupValues[3] + formatLeading(high * factor, comma)
            }
            val (region, regionLength) = scaleRegion(p, rest, factor, comma)
            val tailStart = start + match.range.last + 1 + regionLength
            val next = nextAmount(p, line, tailStart)
            val end = next?.range?.first ?: line.length
            val one = upperRaw.isEmpty() && low == 1.0
            val tail = scaleBrackets(p, line.substring(tailStart, end), factor, comma, measure, one, bracket)
                ?: return null
            val joiner = if (next == null) "" else next.value
            sides += Side(start, end, match.groupValues[1] + scaled + region + tail + joiner)
            if (next == null) return sides
            start = next.range.last + 1
            alternative = next.groupValues[1].isNotEmpty()
        }
    }

    // The next joining word followed by an amount, outside brackets or opening one:
    // "or 2 cups" and "(or 1/2 cup oil)" count, the "or" in "(14 oz or 400 g)" doesn't.
    private fun nextAmount(p: Patterns, line: String, from: Int): MatchResult? {
        var match = p.joined.find(line, from)
        while (match != null) {
            val before = line.substring(from, match.range.first)
            if (depth(before) == 0 || before.trimEnd().endsWith('(')) return match
            match = match.next()
        }
        return null
    }

    private fun depth(text: String): Int {
        var depth = 0
        for (c in text) {
            if (c == '(') depth++ else if (c == ')' && depth > 0) depth--
        }
        return depth
    }

    // A line that writes "1,5" reads decimals, not fractions: "1,5 kg" x 1.5 is "2,25 kg".
    private fun formatLeading(value: Double, comma: Boolean): String =
        if (comma) withSeparator(formatDecimal(value), true) else format(value)

    private fun formatDecimal(value: Double): String =
        BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /**
     * Scales "plus 2 tbsp" and whatever alternate measure follows it, else just the alternate.
     * Returns the scaled text and how much of [rest] it stands for.
     */
    private fun scaleRegion(p: Patterns, rest: String, factor: Double, comma: Boolean): Pair<String, Int> {
        val m = p.continued.find(rest) ?: return scaleAlternateMeasure(p, rest, factor, comma)
        val unit = MeasureUnit.fromText(m.groupValues[4], p.words) ?: return scaleAlternateMeasure(p, rest, factor, comma)
        val value = p.parse(m.groupValues[2]) ?: return scaleAlternateMeasure(p, rest, factor, comma)
        // The alternate measure is read from the second part's unit on.
        val unitText = m.groupValues[3] + m.groupValues[4]
        val (alternate, length) = scaleAlternateMeasure(p, unitText + rest.substring(m.range.last + 1), factor, comma)
        return Pair(m.groupValues[1] + formatFor(unit, value * factor, comma) + alternate, m.range.last + 1 - unitText.length + length)
    }

    /**
     * Keeps "(120 g)" or "/120 grams" in step with the leading amount that was just scaled.
     * Returns the scaled text and how much of [rest] it stands for (none when there is none).
     */
    private fun scaleAlternateMeasure(p: Patterns, rest: String, factor: Double, comma: Boolean): Pair<String, Int> {
        p.altParen.find(rest)?.let { m ->
            val inner = p.qtyUnit.replace(m.groupValues[2]) { scalePair(p, it, factor, comma) }
            return Pair(m.groupValues[1] + inner + m.groupValues[3], m.range.last + 1)
        }
        p.altSlash.find(rest)?.let { m ->
            val unit = MeasureUnit.fromText(m.groupValues[6], p.words)
            val value = p.parse(m.groupValues[2])
            val upper = m.groupValues[4]
            val high = if (upper.isEmpty()) null else p.parse(upper)
            if (unit != null && value != null && (upper.isEmpty() || high != null)) {
                val range = if (high == null) "" else m.groupValues[3] + formatFor(unit, high * factor, comma)
                return Pair(
                    m.groupValues[1] + formatFor(unit, value * factor, comma) + range + m.groupValues[5] + m.groupValues[6],
                    m.range.last + 1
                )
            }
        }
        return Pair("", 0)
    }

    /** A bracket in a side's text: [start] and [end] include the brackets, the content is between. */
    internal class Bracket(val start: Int, val end: Int, val contentStart: Int, val contentEnd: Int)

    /** Brackets at the outer level, nested ones inside them; an unclosed one runs to the end. */
    internal fun brackets(text: String): List<Bracket> {
        val found = mutableListOf<Bracket>()
        var depth = 0
        var open = -1
        for (i in text.indices) {
            when (text[i]) {
                '(' -> { if (depth == 0) open = i; depth++ }
                ')' -> if (depth > 0) {
                    depth--
                    if (depth == 0) found += Bracket(open, i + 1, open + 1, i)
                }
            }
        }
        if (depth > 0) found += Bracket(open, text.length, open + 1, text.length)
        return found
    }

    /** What a bracket after the name holds (#63). */
    internal enum class BracketKind {
        /** No amount with a unit: "(packed)", "(Note 2)", "(2-inch pieces)". Left alone. */
        OTHER,

        /** A package or per-item size: "1 can (14 oz)", "2 (400 g) tins", "(8 oz each)". Never scaled. */
        PACKAGE,

        /** The line's own amount written another way: "(8 ½ ounces)", "(about 1/4 cup)". Scales with it. */
        TOTAL,

        /**
         * A count's bracket holding nothing but an amount: "4 Apfel (ca. 800g)" (a total?) or
         * "1 patate douce (300-400 g)" (each one's?). Unsure unless the model decided (#104).
         */
        COUNT,

        /** Anything else holding an amount: scaling beside it could contradict it. */
        UNSURE
    }

    /**
     * [before] is the side's text before the bracket, [measure] whether the side's amount has a
     * unit, and [one] whether it is the count 1. Only a measure's bracket can't be a per-item
     * size: "4 Apfel (ca. 800g)" and "1 patate douce (300-400 g)" may give each one's weight, so
     * a count's bracket is a package size or unsure, never a total.
     */
    internal fun kind(p: Patterns, before: String, content: String, measure: Boolean, one: Boolean): BracketKind = when {
        !p.qtyUnit.containsMatchIn(content) -> BracketKind.OTHER
        (!measure && before.isBlank()) || p.afterContainer.containsMatchIn(before) ||
            (!measure && one && p.containerFirst.containsMatchIn(before)) ||
            p.perItem.containsMatchIn(content) -> BracketKind.PACKAGE
        measure && p.total.matches(unwrapped(content)) -> BracketKind.TOTAL
        !measure && p.total.matches(unwrapped(content)) -> BracketKind.COUNT
        else -> BracketKind.UNSURE
    }

    // "((~250g/8oz))": recipetineats.com doubles every bracket.
    private fun unwrapped(content: String): String {
        var text = content.trim()
        while (text.length >= 2 && text.first() == '(' && text.last() == ')') {
            val inner = text.substring(1, text.length - 1)
            if (!balanced(inner)) break
            text = inner.trim()
        }
        return text
    }

    private fun balanced(text: String): Boolean {
        var depth = 0
        for (c in text) {
            if (c == '(') depth++ else if (c == ')' && --depth < 0) return false
        }
        return depth == 0
    }

    /**
     * Scales the totals in brackets after the name, leaving package sizes and other brackets as
     * written. Null when a bracket is [BracketKind.UNSURE]: the line stays as written.
     */
    private fun scaleBrackets(
        p: Patterns, tail: String, factor: Double, comma: Boolean, measure: Boolean, one: Boolean,
        bracket: CountBracket?
    ): String? {
        val out = StringBuilder()
        var cursor = 0
        for (b in brackets(tail)) {
            val content = tail.substring(b.contentStart, b.contentEnd)
            var kind = kind(p, tail.substring(0, b.start), content, measure, one)
            // The model's answer (#104): a total scales; each item's size is left as written.
            if (kind == BracketKind.COUNT) kind = when (bracket) {
                CountBracket.TOTAL -> BracketKind.TOTAL
                CountBracket.EACH -> BracketKind.PACKAGE
                null -> BracketKind.UNSURE
            }
            when (kind) {
                BracketKind.OTHER, BracketKind.PACKAGE -> continue
                BracketKind.UNSURE, BracketKind.COUNT -> return null
                BracketKind.TOTAL -> {
                    out.append(tail, cursor, b.contentStart).append(scaleTotal(p, content, factor, comma) ?: return null)
                    cursor = b.contentEnd
                }
            }
        }
        return out.append(tail.substring(cursor)).toString()
    }

    private fun scaleTotal(p: Patterns, content: String, factor: Double, comma: Boolean): String? {
        var failed = false
        val scaled = p.measure.replace(content) { m ->
            val unit = MeasureUnit.fromText(m.groupValues[5], p.words)
            val low = p.parse(m.groupValues[1])
            val high = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { p.parse(it) }
            if (unit == null || low == null || (m.groupValues[3].isNotEmpty() && high == null)) {
                failed = true
                m.value
            } else {
                formatFor(unit, low * factor, comma) +
                    (if (high == null) "" else m.groupValues[2] + formatFor(unit, high * factor, comma)) +
                    m.groupValues[4] + m.groupValues[5]
            }
        }
        return if (failed) null else scaled
    }

    private fun scalePair(p: Patterns, match: MatchResult, factor: Double, comma: Boolean): String {
        val unit = MeasureUnit.fromText(match.groupValues[3], p.words) ?: return match.value
        val value = p.parse(match.groupValues[1]) ?: return match.value
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

    // The language's word between a whole number and its fraction, which the quantity pattern
    // has already checked: "2 and 1/2" is "2 1/2" in any language.
    private val JOINER = Regex("""\s+\p{L}[\p{L}\s]*?\s+(?=[\d$UNICODE_FRACTIONS])""")

    // "1-1/2" is "1 1/2", the one dash the quantity pattern lets through (#125).
    private val MIXED_DASH = Regex("""(?<=\d)[-–—]""")

    /** [thousandsDot]: the quantity comes from a language that writes "1.500" for 1500. */
    internal fun parse(quantity: String, thousandsDot: Boolean = false): Double? {
        // The quantity pattern only lets a comma through as a decimal comma, never before three digits.
        // "2 and 1/2" is "2 1/2", and "1⁄2" (U+2044) is "1/2".
        val digits = if (thousandsDot) THOUSANDS_DOT.replace(quantity.trim(), "") else quantity.trim()
        val written = digits.replace(',', '.').replace('⁄', '/').replace(JOINER, " ")
        val dashed = MIXED_DASH.containsMatchIn(written)
        val q = MIXED_DASH.replace(written, " ")
        val last = q.last()
        UNICODE_VALUES[last]?.let { fraction ->
            val whole = q.dropLast(1).trim()
            return (if (whole.isEmpty()) 0.0 else whole.toDoubleOrNull() ?: return null) + fraction
        }
        var total = 0.0
        for (part in q.split(Regex("""\s+"""))) {
            total += if ('/' in part) {
                val (n, d) = part.split('/').map { it.toDoubleOrNull() ?: return null }
                // "1-3/2" is neither a mixed number nor a range anyone writes.
                if (d == 0.0 || (dashed && n >= d)) return null
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
