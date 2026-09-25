package com.example.recipeclipper.data.model

/**
 * The ingredient's name in an ingredient line ("2 large eggs, beaten" is "eggs"), for matching
 * a recipe's lines against the pantry (#46). Pure, and built from the scaler's, converter's
 * and density table's own parsing, so a name is read the way an amount and unit are.
 *
 * It answers only "which ingredient", never "how much", and returns null for anything it
 * doesn't understand (a heading, "salt and pepper", "juice of 1 lemon"), so a line it can't
 * name is never matched to the wrong thing. It reads the line with its recipe's language's words
 * (#14); a language the app has no words for gives no name.
 */
object IngredientName {

    // The words are shared with iOS: shared/tables/<language>/names.json.
    private class Words(words: LanguageWords) {
        val leadingWords = words.strings("names", "leadingWords").toSet()
        val trailingWords = words.strings("names", "trailingWords").toSet()
        val conjunctions = words.strings("names", "conjunctions").toSet()
        // The words that may come before a shorter name and still mean it ("unsalted" butter),
        // longest first, so "extra virgin" is tried before "extra".
        val matchModifiers = (words.strings("names", "matchModifiers") + words.strings("names", "leadingWords"))
            .distinct().sortedByDescending { it.length }

        // " for dusting", " to taste": the name ends before the first one.
        val cut = Regex(
            """\s+""" + SharedTables.alternation(
                words.strings("names", "cutPhrases").map { it.replace(" ", """\s+""") }
            ) + """(?![A-Za-z])""",
            RegexOption.IGNORE_CASE
        )
    }

    private fun words(words: LanguageWords): Words = words.compiled(Words::class) { Words(it) }

    private val WHITESPACE = Regex("""\s+""")

    /**
     * The ingredient's name, lowercase ("unsalted butter"), or null when there isn't one, or
     * when [words] is null (a language the app has no words for).
     */
    fun of(line: String, words: LanguageWords? = LanguageWords.ENGLISH): String? {
        if (words == null || line.isBlank() || line.trim().endsWith(":")) return null
        // "☆醤油 大さじ1": the name comes first (#16).
        if (IngredientScaler.patterns(words).amountAfterName) return TrailingAmount.nameOfLine(line, words)
        val w = words(words)
        val scaler = IngredientScaler.patterns(words)
        val converter = UnitConverter.patterns(words)

        var text = line
        scaler.leading.find(text)?.let { lead ->
            text = text.substring(lead.range.last + 1)
            // "1-inch piece ginger": the number was a size, not an amount.
            scaler.notAnAmount.find(text)?.let { text = text.substring(it.range.last + 1) }
            // Package sizes and alternate measures: "1 (14 oz) can", "1 cup (120 g) flour".
            text = IngredientDensities.stripParentheses(text)
            // "1 heaping cup flour": a size word can come before the unit.
            text = dropLeadingWords(text, w)
            converter.unitAtStart.find(text)?.let { unit ->
                text = text.substring(unit.range.last + 1)
                converter.continuationAtStart.find(text)?.let { text = text.substring(it.range.last + 1) }
                converter.slashAtStart.find(text)?.let { text = text.substring(it.range.last + 1) }
            }
        }
        text = dropLeadingWords(text, w)

        w.cut.find(text)?.let { text = text.substring(0, it.range.first) }

        val trailingModifiers = IngredientDensities.trailingModifiers(words)
        val name = IngredientDensities.headPhrase(text, words).split(' ').filter { it.isNotEmpty() }
            .dropLastWhile { it in w.trailingWords || it in trailingModifiers }
            .joinToString(" ")
        return name.takeIf { understood(it, words, w) }
    }

    /**
     * True when [a] and [b] name the same ingredient (#51): they're equal, or the longer ends
     * with the shorter at a word boundary and every word before it is a plain modifier (the
     * names table's `matchModifiers` or `leadingWords`). So "unsalted butter" matches "butter",
     * while "rice flour", "butter beans" and "peanut butter" don't match "flour" or "butter", in
     * either direction: a word the table doesn't know makes a different ingredient, and the
     * line is Buy. Either may be a name from [of] or one a person typed.
     */
    fun matches(a: String, b: String, words: LanguageWords = LanguageWords.ENGLISH): Boolean {
        val x = IngredientDensities.headPhrase(a, words)
        val y = IngredientDensities.headPhrase(b, words)
        if (x.isEmpty() || y.isEmpty()) return false
        val (longer, shorter) = if (x.length >= y.length) x to y else y to x
        if (!IngredientDensities.endsWithName(longer, shorter, words.spaced)) return false
        return onlyModifiers(longer.substring(0, longer.length - shorter.length).trim(), words)
    }

    /** True when [text] is nothing but match modifiers, one after another. */
    private fun onlyModifiers(text: String, words: LanguageWords): Boolean {
        val modifiers = words(words).matchModifiers
        var rest = text
        while (rest.isNotEmpty()) {
            val modifier = modifiers.firstOrNull {
                rest == it || rest.startsWith(if (words.spaced) "$it " else it)
            } ?: return false
            rest = rest.substring(modifier.length).trim()
        }
        return true
    }

    /** Drops "large", "cloves", "pinch of": sizes, containers and cuts before the name. */
    private fun dropLeadingWords(text: String, w: Words): String {
        val words = WHITESPACE.split(text.trim()).filter { it.isNotEmpty() }
        var start = 0
        while (start < words.size && bare(words[start]) in w.leadingWords) start++
        if (start > 0 && start < words.size && bare(words[start]) == "of") start++
        return words.drop(start).joinToString(" ")
    }

    /** A word without the punctuation that can trail it in a line ("large," "pkg."). */
    private fun bare(word: String): String = word.trimEnd(',', '.', ';').lowercase()

    // A digit left in the name ("juice of 1 lemon") or two ingredients ("salt and pepper") are
    // beyond a name. A conjunction inside a table alias ("half and half") is part of the name.
    private fun understood(name: String, words: LanguageWords, w: Words): Boolean {
        if (name.isEmpty() || name.any { it in '0'..'9' }) return false
        val alias = IngredientDensities.aliasAtEnd(name, words)
        val rest = if (alias != null && alias.contains(' ')) name.removeSuffix(alias) else name
        return rest.split(' ').none { it in w.conjunctions || it.contains('/') }
    }
}
