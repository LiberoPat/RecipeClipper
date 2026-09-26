package com.example.recipeclipper.data.model

/**
 * Which part of a page's text goes to the on-device model (#103), which reads only a few
 * thousand words. Pure; the iOS app's `RecipeTextWindow` is the same.
 *
 * The anchor is the ingredients heading ("Ingredients", "Zutaten", "材料": `headings.json`, every
 * shipped language at once) followed by the most lines that look like ingredient lines, a
 * little more if a steps heading follows. With no such heading, it is the densest run of
 * ingredient-looking lines. With neither, the page has no recipe to find: null, and the model
 * isn't asked. The window keeps a few lines before the anchor (the recipe card's title, times
 * and servings), then as many lines after it as fit, and starts with the page's title.
 */
object RecipeTextWindow {
    /** A heading is a short line. */
    private const val HEADING_MAX = 60

    /** The lines after a heading that count towards it. */
    private const val LOOK_AHEAD = 40

    /** The most lines kept before the anchor, within a fifth of the budget. */
    private const val LEAD_LINES = 12

    private const val INGREDIENT_MAX = 100
    private const val SHORT_LINE = 40

    private val headings: Pair<List<String>, List<String>> by lazy {
        val all = LanguageWords.SHIPPED.mapNotNull { LanguageWords.forTag(it) }
        all.flatMap { it.strings("headings", "ingredients") } to all.flatMap { it.strings("headings", "steps") }
    }

    /** The window's text, lines joined by "\n", at most [maxChars] long; null if no recipe shows. */
    fun window(page: PageText, maxChars: Int): String? {
        val lines = page.lines.map { it.trim() }.filter { it.isNotEmpty() }
        val anchor = anchor(lines) ?: return null
        val title = page.title?.trim()?.takeIf { it.isNotEmpty() && it.length < maxChars / 4 }
        var budget = maxChars - (title?.length?.plus(1) ?: 0)

        val lead = ArrayDeque<String>()
        var leadChars = 0
        var i = anchor - 1
        while (i >= 0 && lead.size < LEAD_LINES && leadChars + lines[i].length + 1 <= maxChars / 5) {
            lead.addFirst(lines[i]); leadChars += lines[i].length + 1; i--
        }
        budget -= leadChars
        val body = mutableListOf<String>()
        var j = anchor
        while (j < lines.size && lines[j].length + 1 <= budget) {
            body += lines[j]; budget -= lines[j].length + 1; j++
        }
        if (body.isEmpty()) return null
        val window = lead + body
        val withTitle = if (title == null || title in window) window else listOf(title) + window
        return withTitle.joinToString("\n")
    }

    /** The index of the line the recipe starts at, or null. */
    internal fun anchor(lines: List<String>): Int? {
        val looks = lines.map(::looksLikeIngredient)
        var best: Int? = null
        var bestScore = 1
        for ((i, line) in lines.withIndex()) {
            if (!isHeading(line, headings.first)) continue
            val ahead = (i + 1 until minOf(lines.size, i + 1 + LOOK_AHEAD)).count { looks[it] }
            val steps = (i + 1 until minOf(lines.size, i + 1 + 2 * LOOK_AHEAD)).any { isHeading(lines[it], headings.second) }
            val score = ahead + if (steps) 2 else 0
            if (ahead >= 1 && score > bestScore) { best = i; bestScore = score }
        }
        if (best != null) return best
        // No heading: the start of the densest run of ingredient-looking lines, three or more in ten.
        var dense: Int? = null
        var denseCount = 2
        for (i in lines.indices) {
            if (!looks[i]) continue
            val count = (i until minOf(lines.size, i + 10)).count { looks[it] }
            if (count > denseCount) { dense = i; denseCount = count }
        }
        return dense
    }

    /** A short line that starts with one of [words] as whole words ("Ingredients:", "INGREDIENTS"). */
    internal fun isHeading(line: String, words: List<String> = headings.first + headings.second): Boolean {
        if (line.length > HEADING_MAX) return false
        val text = line.lowercase().trimStart { !it.isLetterOrDigit() }
        return words.any { word ->
            text.startsWith(word) && (text.length == word.length || !text[word.length].isLetterOrDigit())
        }
    }

    /** "2 cups flour", "• ½ tsp salt", "Mehl 200 g", "砂糖 大さじ2": an amount first, or a short line with one. */
    internal fun looksLikeIngredient(line: String): Boolean {
        if (line.length > INGREDIENT_MAX) return false
        val text = line.trimStart { !it.isLetterOrDigit() && it !in VULGAR_FRACTIONS }
        val first = text.firstOrNull() ?: return false
        return first.isDigit() || first in VULGAR_FRACTIONS || (text.length <= SHORT_LINE && text.any { it.isDigit() })
    }

    private const val VULGAR_FRACTIONS = "¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"
}
