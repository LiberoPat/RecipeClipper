package com.example.recipeclipper.data.model

/**
 * What a yield counts: people ("Serves 4", "4 servings") or things made ("Makes 16",
 * "24 cookies", "1 loaf"). Only changes the label word; the stepper and scaling are the same.
 */
enum class YieldKind { SERVES, MAKES }

object Servings {
    const val MAX = 99

    /**
     * Pulls the serving count out of a schema.org `recipeYield` string such as
     * "4 servings", "Serves 4-6" or "Makes 24 cookies". Takes the first number.
     * Returns null when there is no usable number, which hides the scaling control.
     */
    private val RANGE = Regex("""\d+\s*(?:[-–—]|${SharedTables.RANGE_WORDS})\s*\d+""", RegexOption.IGNORE_CASE)

    /**
     * Sites often list several forms of the same yield, e.g. `["4", "4 to 6 servings"]`.
     * Prefers the entry that states a range so "Original: 4-6 servings" isn't cut down
     * to "4"; otherwise keeps the first entry.
     */
    fun pickYield(candidates: List<String>): String? =
        candidates.firstOrNull { RANGE.containsMatchIn(it) } ?: candidates.firstOrNull()

    /**
     * Sites often publish just a number (`recipeYield: 6`). This only decides *whether* the
     * yield is bare — the caller supplies the word via a plural resource, since "6 servings"
     * vs. "6 servings" isn't just an `if` in every language. Text that already says what it
     * is ("4 to 6 servings", "24 cookies") isn't bare, so the caller shows it as published.
     */
    fun bareCount(recipeYield: String): Int? {
        val text = recipeYield.trim()
        if (text.isEmpty() || !text.all { it.isDigit() }) return null
        return text.toIntOrNull()
    }

    fun parse(recipeYield: String?): Int? {
        val first = Regex("""\d+""").find(recipeYield ?: return null)?.value?.toIntOrNull()
        return first?.takeIf { it in 1..MAX }
    }

    // The yield words are shared with iOS: shared/tables/en/yield.json.
    private val TABLE = SharedTables.load("yield")
    private val SERVING_WORD = Regex(
        """\b${SharedTables.alternation(SharedTables.strings(TABLE.getJSONArray("serving")))}\b""",
        RegexOption.IGNORE_CASE
    )
    private val MAKES_WORD = Regex(
        """\b${SharedTables.alternation(SharedTables.strings(TABLE.getJSONArray("makes")))}\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A number followed by some other word: "24 cookies", "1 (9-inch) pie", "2 dozen".
     * The lookahead skips the "to" of a range, so a bare "4 to 6" isn't read as a noun.
     */
    private val COUNTED_NOUN = Regex("""\d[^\p{L}]*(?!${SharedTables.RANGE_WORDS}\b)\p{L}""", RegexOption.IGNORE_CASE)

    /**
     * Whether the yield counts servings or things made, which picks "Serves" or "Makes" as
     * the label. A serving word anywhere wins ("4 to 6 servings", "Makes 4 servings"); then
     * "makes"/"yields", or a number followed by any other noun, means MAKES. Anything else,
     * including a bare number, stays SERVES — the label the app always showed.
     */
    fun kind(recipeYield: String?): YieldKind {
        val text = recipeYield?.trim().orEmpty()
        if (text.isEmpty() || bareCount(text) != null) return YieldKind.SERVES
        if (SERVING_WORD.containsMatchIn(text)) return YieldKind.SERVES
        if (MAKES_WORD.containsMatchIn(text) || COUNTED_NOUN.containsMatchIn(text)) return YieldKind.MAKES
        return YieldKind.SERVES
    }
}
