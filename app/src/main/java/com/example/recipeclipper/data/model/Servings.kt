package com.example.recipeclipper.data.model

/**
 * What a yield counts: people ("Serves 4", "4 servings") or things made ("Makes 16",
 * "24 cookies", "1 loaf"). Only changes the label word; the stepper and scaling are the same.
 */
enum class YieldKind { SERVES, MAKES }

object Servings {
    const val MAX = 99

    /** One language's yield words: shared/tables/<language>/yield.json and ranges.json. */
    private class Patterns(words: LanguageWords) {
        val range = Regex("""\d+\s*(?:[-–—]|${words.rangeWords})\s*\d+""", RegexOption.IGNORE_CASE)
        // Whole words by letters rather than \b, which the JDK, Android's ICU and iOS read
        // differently beside accented letters ("porções", #15).
        val servingWord = Regex(
            """(?<!\p{L})${SharedTables.alternation(words.strings("yield", "serving"))}(?!\p{L})""",
            RegexOption.IGNORE_CASE
        )
        val makesWord = Regex(
            """(?<!\p{L})${SharedTables.alternation(words.strings("yield", "makes"))}(?!\p{L})""",
            RegexOption.IGNORE_CASE
        )

        /**
         * A number followed by some other word: "24 cookies", "1 (9-inch) pie", "2 dozen".
         * The lookahead skips the "to" of a range, so a bare "4 to 6" isn't read as a noun.
         */
        val countedNoun = Regex("""\d[^\p{L}]*(?!${words.rangeWords}(?!\p{L}))\p{L}""", RegexOption.IGNORE_CASE)
    }

    private fun patterns(words: LanguageWords): Patterns = words.compiled(Patterns::class) { Patterns(it) }

    /** A range in a language the app has no words for: the dashes are anyone's. */
    private val DASH_RANGE = Regex("""\d+\s*[-–—]\s*\d+""")

    /**
     * Sites often list several forms of the same yield, e.g. `["4", "4 to 6 servings"]`.
     * Prefers the entry that states a range so "Original: 4-6 servings" isn't cut down
     * to "4"; otherwise keeps the first entry.
     */
    fun pickYield(candidates: List<String>, words: LanguageWords? = LanguageWords.ENGLISH): String? {
        val range = if (words == null) DASH_RANGE else patterns(words).range
        return candidates.firstOrNull { range.containsMatchIn(words?.readable(it) ?: it) } ?: candidates.firstOrNull()
    }

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

    /**
     * Pulls the serving count out of a schema.org `recipeYield` string such as
     * "4 servings", "Serves 4-6" or "Makes 24 cookies". Takes the first number.
     * Returns null when there is no usable number, which hides the scaling control, and for
     * a language the app has no words for ([words] null), whose lines couldn't be scaled.
     */
    fun parse(recipeYield: String?, words: LanguageWords? = LanguageWords.ENGLISH): Int? {
        if (words == null) return null
        val first = Regex("""\d+""").find(words.readable(recipeYield ?: return null))?.value?.toIntOrNull()
        return first?.takeIf { it in 1..MAX }
    }

    /**
     * Whether the yield counts servings or things made, which picks "Serves" or "Makes" as
     * the label. A serving word anywhere wins ("4 to 6 servings", "Makes 4 servings"); then
     * "makes"/"yields", or a number followed by any other noun, means MAKES. Anything else,
     * including a bare number, stays SERVES — the label the app always showed.
     */
    fun kind(recipeYield: String?, words: LanguageWords? = LanguageWords.ENGLISH): YieldKind {
        val text = recipeYield?.trim().orEmpty()
        if (text.isEmpty() || bareCount(text) != null || words == null) return YieldKind.SERVES
        val p = patterns(words)
        val read = words.readable(text)
        if (p.servingWord.containsMatchIn(read)) return YieldKind.SERVES
        if (p.makesWord.containsMatchIn(read) || p.countedNoun.containsMatchIn(read)) return YieldKind.MAKES
        return YieldKind.SERVES
    }
}
