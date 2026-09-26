package com.example.recipeclipper.data.model

/**
 * Which questions are worth asking the model (#104): only where today's rules give up. Pure,
 * so the same lines ask the same questions on both platforms.
 */
object DecisionCandidates {

    /** A count bracket per line that stays as written only because of one ([IngredientScaler.needsCountDecision]). */
    fun countBrackets(lines: List<String>, words: LanguageWords?): List<DecisionQuestion> {
        words ?: return emptyList()
        return lines.filter { IngredientScaler.needsCountDecision(it, words) }
            .map { DecisionQuestion.countBracket(it, words.language) }
    }

    /**
     * True when [a] and [b] are close enough to ask about but [IngredientName.matches] says no:
     * they share a word of three or more letters ("rice flour" and "flour", "farine de riz" and
     * "farine"), or one's last word ends with the other's ("Weizenmehl" and "Mehl"). Never in a
     * language written without spaces.
     */
    fun close(a: String, b: String, words: LanguageWords): Boolean {
        if (!words.spaced || IngredientName.matches(a, b, words)) return false
        val x = wordsOf(IngredientDensities.headPhrase(a, words))
        val y = wordsOf(IngredientDensities.headPhrase(b, words))
        if (x.isEmpty() || y.isEmpty() || x == y) return false
        if (x.any { it.length >= 3 && it in y }) return true
        val (lx, ly) = x.last() to y.last()
        return (lx.length > ly.length && ly.length >= 3 && lx.endsWith(ly)) ||
            (ly.length > lx.length && lx.length >= 3 && ly.endsWith(lx))
    }

    private fun wordsOf(text: String): List<String> =
        text.lowercase().split(' ', '-').map { it.trim(',', '.', ';') }.filter { it.isNotEmpty() }

    /**
     * For each of [names] (a line's [IngredientName], in [language]) that no pantry item matches,
     * a question per close pantry item that would make it Have (in stock or a staple).
     */
    fun samePairs(names: Collection<String>, language: String?, pantry: List<PantryItem>): List<DecisionQuestion> {
        val words = LanguageWords.forTag(language) ?: return emptyList()
        val candidates = pantry.filter { it.language == words.language && (it.inStock || it.alwaysHave) }
        return names.distinct().filter { PantryMatch.find(it, words.language, pantry) == null }.flatMap { name ->
            candidates.filter { close(name, it.name, words) }
                .map { DecisionQuestion.sameIngredient(name, it.name, words.language) }
        }
    }

    /** The aisle question for [line], when it has a name and the keyword table puts it in Other. */
    fun aisle(line: String, language: String?): DecisionQuestion? {
        val words = LanguageWords.forTag(language) ?: return null
        val name = IngredientName.of(line, words) ?: return null
        if (Aisles.ofName(name, words) != Aisle.OTHER) return null
        return DecisionQuestion.aisle(name, words.language)
    }
}
