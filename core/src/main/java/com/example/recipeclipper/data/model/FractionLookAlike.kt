package com.example.recipeclipper.data.model

/**
 * What on-device text recognition gives for a fraction glyph it misreads (#245), measured on
 * `shared/fixtures/reddit/photos/card-fractions-*.jpg` and listed in `photo-fractions.json`:
 * "%" for ⅔, "&" for ⅛, "/4" for ¾, "Ys" for ⅓. Which fraction it was can't be told ("%" was ⅔
 * there, and may be ½ or ¾ elsewhere), so it is never replaced by itself: the photo review
 * (PhotoTextSorter) lists the line with fraction chips for the cook, and the scaler and
 * converter leave a line holding one as written. Language-independent; the unit after it is
 * the line's language's.
 */
object FractionLookAlike {

    private val TABLE = SharedTables.read("photo-fractions")
    private val AFTER_NUMBER_ONLY = SharedTables.strings(TABLE.getJSONArray("afterNumberOnly"))

    /** The fractions offered to replace a look-alike, in the order shown. */
    val FRACTIONS: List<String> = SharedTables.strings(TABLE.getJSONArray("fractions"))

    // A look-alike at the start, or after a whole number at the start, ending a word.
    // Groups: 1 the space before, 2 the whole number, 3 the space after it, 4 the look-alike.
    private val SLOT = Regex(
        """^(\s*)(?:(\d+)(\s*))?(""" +
            SharedTables.alternation(
                (SharedTables.strings(TABLE.getJSONArray("lookAlikes")) + AFTER_NUMBER_ONLY)
                    .sortedByDescending { it.length }.map { Regex.escape(it) }
            ) + """)(?![\p{L}\d])"""
    )

    /**
     * Where [line]'s amount holds a look-alike: at the start ("% cup sugar") or after a whole
     * number there ("2% cups oats", "1 a cups flour"), and then only before a unit
     * ([unitAtStart], the scaler's), unless it is a symbol at the very start ("& tsp", "/4 tsp").
     * So "2% milk" and "70% chocolate" are percentages, and "1/2 cup" a fraction. Null if none.
     */
    fun slot(line: String, unitAtStart: Regex): IntRange? {
        val m = SLOT.find(line) ?: return null
        val token = m.groups[4]!!
        val afterNumber = m.groupValues[2].isNotEmpty()
        if (!afterNumber && token.value in AFTER_NUMBER_ONLY) return null
        // "1/2" is a fraction; only "1 /2" has lost one.
        if (afterNumber && m.groupValues[3].isEmpty() && token.value.startsWith("/")) return null
        val unitNext = unitAtStart.containsMatchIn(line.substring(token.range.last + 1))
        val symbol = token.value.none { it.isLetter() }
        return token.range.takeIf { unitNext || (symbol && !afterNumber) }
    }

    /**
     * Where [line] holds a fraction glyph run into a digit or another glyph (#245, #276): "½⅓",
     * "3¾4", which no recipe writes but a recogniser gives for one glyph with its strokes read
     * again. The run is every glyph and digit from its first glyph on, so a chosen fraction
     * replaces it whole ("½⅓ cup" → "⅓ cup"); the digits before that first glyph are a whole
     * number and aren't part of it ("1½⅓ cup" → "1 ⅓ cup", the owner's call), nor is one before
     * an ordinary glyph ("1¼ cups" is no run). The first run in the line; null if none.
     */
    fun gluedRun(line: String): IntRange? = GLUED_RUN.find(line)?.groups?.get(1)?.range

    private const val GLYPHS = "¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"

    // A whole number, then (group 1) a glyph straight before a digit or another glyph, and the
    // rest of the run.
    private val GLUED_RUN = Regex("""(?<![\d$GLYPHS])\d*([$GLYPHS][\d$GLYPHS]+)""")
}
