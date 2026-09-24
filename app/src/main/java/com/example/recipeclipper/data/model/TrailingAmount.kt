package com.example.recipeclipper.data.model

/**
 * Ingredient lines written name first and amount last, as Japanese sites write them (#16):
 * "鶏もも肉 2枚（約700g）", "だし汁 2と1/2カップ", "醤油 大さじ1", "☆砂糖 小さじ1/2". A language
 * whose amounts.json sets "amountAfterName" has its lines read here instead of by their leading
 * number. Pure: string in, data out.
 *
 * The amount is the text after the line's last space (half-width or full-width). It is read
 * only when it is, in this order: an optional word from amounts.json "beforeNumber" (各 "each",
 * 約 "about", 大/中/小), an optional unit, a number or a range, an optional unit, then text with
 * no digit in it (a counter such as 個 or 本, 弱 "scant"), which may hold one measure in
 * brackets ("缶（200g）"): that measure is the same amount weighed, so it scales with it.
 * Anything else stays as written: 少々 and 適量 have no number, "1半丁" has a half in words,
 * "10cm" is a size, and after a name ending in a digit ("大さじ2 1/2") part of the amount may be
 * in the name.
 *
 * Full-width digits and letters ("１００ＣＣ") are read as half-width ones, and a mixed number's
 * joiner ("2と1/2", "2・1/2", amounts.json "mixedJoiners") as a space. Both keep the text's
 * length, so what is found in the read text is spliced back into the line as written.
 */
internal object TrailingAmount {

    /**
     * [text] with full-width digits, Latin letters, slash and full stop half-width, a wave dash
     * as "~" and an ideographic space as a space. Every change is one UTF-16 unit for one, so
     * offsets into the result are offsets into [text].
     */
    fun halfWidth(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            out.append(
                when (c) {
                    in '０'..'９', in 'Ａ'..'Ｚ', in 'ａ'..'ｚ', '／', '．' -> c - 0xFEE0
                    '～', '〜' -> '~'
                    '　' -> ' '
                    else -> c
                }
            )
        }
        return out.toString()
    }

    /** One language's patterns: amounts.json, units.json and names.json. */
    private class Patterns(val words: LanguageWords) {
        val scaler = IngredientScaler.patterns(words)
        private val units = SharedTables.alternation(words.strings("units", "patterns"))
        private val before = SharedTables.alternation(words.strings("amounts", "beforeNumber"))
        private val qty = """\d+ +\d+/\d+|\d+/\d+|\d+(?:\.\d+)?"""

        /** How a mixed number is written back ("2と1/2"): the first joiner. */
        val joiner: String = words.strings("amounts", "mixedJoiners").firstOrNull() ?: " "

        // "2と1/2": the joiner between a whole number and a fraction, read as a space.
        val mixed = Regex("""(?<=\d)${SharedTables.alternation(words.strings("amounts", "mixedJoiners"))}(?=\d+/\d)""")

        // The name, the last run of spaces, the amount. groups: 1 name, 2 amount
        val line = Regex("""^(.*\S)\s+(\S+)\s*$""")

        // groups: 1 before-word, 2 unit before, 3 quantity, 4 range separator, 5 upper bound,
        // 6 unit after, 7 the rest
        val amount = Regex(
            """^($before)?(?:($units)\s*)?($qty)(?:(\s*(?:[-–—]|${words.rangeWords})\s*)($qty))?""" +
                """\s*(?:($units)(?![A-Za-z]))?(.*)$""",
            RegexOption.IGNORE_CASE
        )

        // The rest: no digits, or one measure in brackets. groups: 1 text before the bracket,
        // 2 the bracket and any before-word, 3 quantity, 4 unit, 5 the closing bracket
        val rest = Regex(
            """^([^\d]*?)([(（]\s*(?:$before)?\s*)($qty)\s*($units)(?![A-Za-z])(\s*[)）])[^\d]*$|^[^\d]*$""",
            RegexOption.IGNORE_CASE
        )

        // "1半丁": a half in words straight after the number.
        val half = Regex("""^\s*${SharedTables.alternation(words.strings("amounts", "spelledHalves"))}""")

        // names.json "groupMarkers": "☆", "A", "【A】" before a name, repeatedly.
        val markers = Regex("""^\s*${SharedTables.alternation(words.strings("names", "groupMarkers"))}+""")

        // "(みじん切り)", "[乾燥]": an aside in brackets, innermost first.
        val aside = Regex("""[(（][^()（）]*[)）]|[\[［][^\[\]［］]*[\]］]""")

        // Brackets left unmatched, and the quote marks around a brand name, kept inside.
        val stray = Regex("""[()（）\[\]［］「」『』〈〉]""")

        val conjunctions = words.strings("names", "conjunctions")
    }

    private fun patterns(words: LanguageWords): Patterns = words.compiled(Patterns::class) { Patterns(it) }

    /** A number in the line: where it is, and what it reads. */
    class Number(val range: IntRange, val value: Double)

    /** The measure in brackets after the amount ("（約700g）"). [end] is just past its bracket. */
    class Measure(val number: Number, val unit: MeasureUnit, val text: String, val atStart: Boolean, val end: Int)

    /**
     * An amount found at the end of a line. Offsets are into the line as written. [unit] is
     * null for a counter (個, 本) or a bare number: it scales, but never converts.
     */
    class Found(
        val name: String,
        val start: Int,
        val beforeWord: String,
        val low: Number,
        val separator: String,
        val high: Number?,
        val unit: MeasureUnit?,
        val restStart: Int,
        val measure: Measure?
    )

    /** The amount at the end of [line], or null when there is none this can read without guessing. */
    fun find(line: String, words: LanguageWords): Found? {
        val p = patterns(words)
        if (p.scaler.unreadable(line)) return null
        val split = p.line.find(halfWidth(line)) ?: return null
        val name = split.groupValues[1]
        if (name.last() in '0'..'9') return null
        val start = split.groups[2]!!.range.first
        val text = p.mixed.replace(split.groupValues[2]) { " ".repeat(it.value.length) }
        val m = p.amount.find(text) ?: return null
        val restAt = m.groups[7]!!.range.first
        val rest = m.groupValues[7]
        if (p.half.containsMatchIn(rest) || p.scaler.notAnAmount.containsMatchIn(rest)) return null
        val r = p.rest.find(rest) ?: return null

        val unitBefore = m.groupValues[2]
        val unitAfter = m.groupValues[6]
        if (unitBefore.isNotEmpty() && unitAfter.isNotEmpty()) return null
        val unitText = unitBefore.ifEmpty { unitAfter }
        val unit = if (unitText.isEmpty()) null else MeasureUnit.fromText(unitText, words) ?: return null

        fun number(group: MatchResult, index: Int, offset: Int): Number? {
            val g = group.groups[index] ?: return null
            val value = p.scaler.parse(g.value) ?: return null
            return Number(IntRange(start + offset + g.range.first, start + offset + g.range.last), value)
        }
        val low = number(m, 3, 0) ?: return null
        val high = if (m.groups[5] == null) null else number(m, 5, 0) ?: return null

        val measure = r.groups[3]?.let {
            val measureUnit = MeasureUnit.fromText(r.groupValues[4], words) ?: return null
            val number = number(r, 3, restAt) ?: return null
            val textEnd = start + restAt + r.groups[4]!!.range.last + 1
            Measure(
                number, measureUnit, line.substring(number.range.first, textEnd),
                atStart = r.groupValues[1].isBlank(),
                end = start + restAt + r.groups[5]!!.range.last + 1
            )
        }
        val beforeWord = m.groups[1]?.let { line.substring(start + it.range.first, start + it.range.last + 1) } ?: ""
        val restStart = start + restAt
        return Found(
            name = line.substring(0, start),
            start = start,
            beforeWord = beforeWord,
            low = low,
            separator = m.groups[4]?.let { line.substring(start + it.range.first, start + it.range.last + 1) } ?: "",
            high = high,
            unit = unit,
            restStart = restStart,
            measure = measure
        )
    }

    /** [line] with its amount, and the measure in brackets after it, scaled by [factor]. */
    fun scale(line: String, factor: Double, words: LanguageWords): String {
        val found = find(line, words) ?: return line
        val p = patterns(words)
        val edits = mutableListOf(found.low.range to format(p, found.low.value * factor, found.unit))
        found.high?.let { edits += it.range to format(p, it.value * factor, found.unit) }
        found.measure?.let { edits += it.number.range to format(p, it.number.value * factor, it.unit) }
        val out = StringBuilder()
        var cursor = 0
        for ((range, text) in edits.sortedBy { it.first.first }) {
            out.append(line, cursor, range.first).append(text)
            cursor = range.last + 1
        }
        return out.append(line, cursor, line.length).toString()
    }

    // Grams and millilitres as "75" or "7.5"; anything else as a fraction, "2と1/2".
    private fun format(p: Patterns, value: Double, unit: MeasureUnit?): String =
        if (unit?.metric == true) IngredientScaler.formatMetric(value)
        else IngredientScaler.format(value).replace(" ", p.joiner)

    /**
     * The ingredient's name in [line], lowercase, without its group marker, asides in brackets
     * or quote marks; null for a line with no amount after a space, for a name holding a digit,
     * and for two ingredients ("砂糖・コンソメ").
     */
    fun nameOfLine(line: String, words: LanguageWords): String? {
        val split = patterns(words).line.find(halfWidth(line)) ?: return null
        return nameOf(split.groupValues[1], words)
    }

    /** [nameOfLine] for the text before the amount, as [Found.name] holds it. */
    fun nameOf(text: String, words: LanguageWords): String? {
        val p = patterns(words)
        var t = p.markers.replace(halfWidth(text).trim(), "")
        while (true) {
            val next = p.aside.replace(t, "")
            if (next == t) break
            t = next
        }
        t = p.markers.replace(p.stray.replace(t, ""), "").trim().lowercase()
        if (t.isEmpty() || t.any { it in '0'..'9' } || p.conjunctions.any { it in t }) return null
        return t
    }
}
