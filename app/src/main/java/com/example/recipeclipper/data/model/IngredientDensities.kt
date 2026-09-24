package com.example.recipeclipper.data.model

/**
 * [gramsPerCup] is per US cup (236.6 ml); null means "recognised, but deliberately not
 * converted" (ingredients whose weight varies too much to state a number honestly).
 * [liquid] means pourable, and is what the "convert liquids too" option controls.
 * [stickable] allows the "stick" unit (butter and margarine only).
 */
internal data class Density(
    val gramsPerCup: Double?,
    val liquid: Boolean,
    val stickable: Boolean = false
)

/**
 * Approximate weights of common baking ingredients. Dry goods follow King Arthur Baking's
 * published ingredient weight chart (spooned-and-levelled cups); liquids and fats use
 * physical densities (USDA), so a cup of milk is 245 g rather than a rounded 227 g.
 *
 * Only ingredients that weigh roughly the same every time belong here. Salt (table vs
 * kosher differ ~2x), chopped produce, shredded cheese, nuts, rolled oats and rice
 * (cooked vs raw) are left out on purpose, so those lines stay as written.
 */
internal object IngredientDensities {

    private class Entry(val aliases: List<String>, val density: Density)

    // The table is shared with iOS: shared/tables/<language>/densities.json. A null gramsPerCup
    // is a skip entry, which matches by name but converts nothing, beating a shorter alias like
    // plain "flour".
    private class Table(words: LanguageWords) {
        private val table = words.table("densities")

        private val entries: List<Entry> = SharedTables.objects(table.getJSONArray("entries")).map { e ->
            Entry(
                SharedTables.strings(e.getJSONArray("aliases")),
                Density(
                    gramsPerCup = if (e.isNull("gramsPerCup")) null else e.getDouble("gramsPerCup"),
                    liquid = e.optBoolean("liquid", false),
                    stickable = e.optBoolean("stickable", false)
                )
            )
        }

        // Longest alias first, so "brown sugar" wins over "sugar" and "peanut butter" over "butter".
        val aliases: List<Pair<String, Density>> = entries
            .flatMap { entry -> entry.aliases.map { it to entry.density } }
            .sortedByDescending { it.first.length }

        val trailingModifiers = SharedTables.strings(table.getJSONArray("trailingModifiers")).toSet()
    }

    /**
     * Looks the ingredient up by the *end* of its name, so "unsalted butter" and "light
     * brown sugar" match while "butter beans" and "flour tortillas" don't.
     */
    fun find(ingredientText: String, words: LanguageWords = LanguageWords.ENGLISH): Density? {
        val table = words.compiled(Table::class) { Table(it) }
        val phrase = headPhrase(ingredientText, table.trailingModifiers)
        return table.aliases.firstOrNull { (alias, _) -> phrase == alias || phrase.endsWith(" $alias") }?.second
    }

    private val INNERMOST_PARENS = Regex("""\([^()]*\)""")

    /**
     * Removes parenthesised text, including nested or doubled parentheses ("((all-purpose
     * flour))"), innermost first until nothing changes, then drops any unmatched paren.
     */
    private fun stripParentheses(text: String): String {
        var current = text
        while (true) {
            val next = INNERMOST_PARENS.replace(current, " ")
            if (next == current) break
            current = next
        }
        return current.replace('(', ' ').replace(')', ' ')
    }

    /** The ingredient name: text before the first comma, without parentheses or modifiers. */
    private fun headPhrase(text: String, trailingModifiers: Set<String>): String {
        val words = stripParentheses(text)
            .substringBefore(',')
            .lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace('-', ' ')
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
        return words.dropLastWhile { it in trailingModifiers }.joinToString(" ")
    }
}
