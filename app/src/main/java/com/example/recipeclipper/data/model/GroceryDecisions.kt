package com.example.recipeclipper.data.model

/**
 * The on-device model's help with the grocery list (#99, over #104's [DecisionRule]): which
 * lines' close names to ask about, which text after an ingredient to ask about, and how a
 * line reads once answered. Pure, both platforms. The model never writes a number: a definite
 * answer only lets [GroceryCombiner] group lines, or read a line without its trailing text,
 * and the combiner's own exact rules still decide every total.
 */
object GroceryDecisions {

    /** A line cut into its [core] ("2 eggs") and the [trailing] text after the name (", beaten"). */
    data class Split(val core: String, val trailing: String)

    private val SEPARATOR = Regex("""[,;(]|\s[-–—]+(?=\s|$)""")

    /**
     * The line cut at the first comma, semicolon, bracket or dash whose left side has an
     * [IngredientName], or null: no such cut, a language written without spaces or name-first,
     * or trailing text holding a digit (a figure is never ignored, whatever the model says).
     */
    fun split(line: String, words: LanguageWords): Split? {
        if (!words.spaced || IngredientScaler.patterns(words).amountAfterName) return null
        for (m in SEPARATOR.findAll(line)) {
            val core = line.substring(0, m.range.first).trimEnd()
            if (IngredientName.of(core, words) == null) continue
            val trailing = line.substring(m.range.first).trim()
            if (trailing.length < 2 || trailing.any { it.isDigit() }) return null
            return Split(core, trailing)
        }
        return null
    }

    /**
     * The name question for a line with no separator whose name has words the aisle table
     * doesn't match after ones it does ("2 onions dfsafs" reads "onions dfsafs", and "onions" is
     * produce), or null: only then could a name answer change the grouping or what shows.
     */
    fun nameQuestion(line: String, words: LanguageWords): DecisionQuestion? {
        if (!words.spaced || IngredientScaler.patterns(words).amountAfterName || split(line, words) != null) return null
        val name = IngredientName.of(line, words) ?: return null
        val parts = name.split(' ')
        if (parts.size < 2 || Aisles.ofName(name, words) != Aisle.OTHER) return null
        val known = (1 until parts.size).any { n -> Aisles.ofName(parts.take(n).joinToString(" "), words) != Aisle.OTHER }
        return if (known) DecisionQuestion.ingredientName(line, words.language) else null
    }

    /**
     * The line cut after the model's [name] for it: the name must be in the line as whole words
     * ([PageRecipeCheck.find]), the line up to it must read as an ingredient line whose name
     * ends with it (so no amount or unit is in the name), and what is left must be at least two
     * characters with no digit. Null otherwise: the line stays as it is.
     */
    fun nameSplit(line: String, name: String, words: LanguageWords): Split? {
        if (!words.spaced || IngredientScaler.patterns(words).amountAfterName) return null
        val found = PageRecipeCheck.find(line, name, PageRecipeCheck.Kind.NAME) ?: return null
        var at = line.indexOf(found)
        while (at >= 0) {
            val end = at + found.length
            val clean = (at == 0 || !line[at - 1].isLetterOrDigit()) && (end == line.length || !line[end].isLetterOrDigit())
            if (clean) {
                val core = line.substring(0, end).trimEnd()
                val trailing = line.substring(end).trim()
                val coreName = IngredientName.of(core, words) ?: return null
                if (!coreName.endsWith(DecisionQuestion.normalize(found))) return null
                if (trailing.length < 2 || trailing.any { it.isDigit() }) return null
                return Split(core, trailing)
            }
            at = line.indexOf(found, at + 1)
        }
        return null
    }

    /** [split], else, for a line with no separator, the cut after the model's name for it. */
    fun split(line: String, words: LanguageWords, decisions: Decisions): Split? =
        split(line, words) ?: decisions.ingredientName(line, words.language)?.let { nameSplit(line, it, words) }

    /** The text [GroceryCombiner] reads [item] as: its core once its trailing text is definitely a note or junk. */
    fun effectiveText(item: GroceryItem, decisions: Decisions): String {
        val words = LanguageWords.forTag(item.language) ?: return item.text
        val split = split(item.text, words, decisions) ?: return item.text
        return if (decisions.ignorableTrailing(split.trailing, words.language)) split.core else item.text
    }

    /**
     * The text Groceries shows for [item]: its core once its trailing text is definitely junk,
     * else as written (a note still shows). Display only: the stored line is never rewritten.
     */
    fun shownText(item: GroceryItem, decisions: Decisions): String {
        val words = LanguageWords.forTag(item.language) ?: return item.text
        val split = split(item.text, words, decisions) ?: return item.text
        return if (decisions.junkTrailing(split.trailing, words.language)) split.core else item.text
    }

    /** The name questions worth asking ([nameQuestion]) for [items]. */
    fun ingredientNames(items: List<GroceryItem>): List<DecisionQuestion> =
        items.mapNotNull { item -> LanguageWords.forTag(item.language)?.let { nameQuestion(item.text, it) } }.distinct()

    /** [item]'s name as the combiner groups it, or null. */
    fun name(item: GroceryItem, decisions: Decisions): String? =
        LanguageWords.forTag(item.language)?.let { IngredientName.of(effectiveText(item, decisions), it) }

    // Two lines can share a row only in one aisle; one in Other may be filed beside the other.
    private fun aislesMeet(a: GroceryItem, b: GroceryItem) =
        a.aisle == b.aisle || a.aisle == Aisle.OTHER || b.aisle == Aisle.OTHER

    /**
     * The "same thing?" questions worth asking: two lines in one language whose names differ,
     * are [DecisionCandidates.close], and whose aisles could meet.
     */
    fun samePairs(items: List<GroceryItem>, decisions: Decisions): List<DecisionQuestion> {
        val named = items.mapNotNull { item -> name(item, decisions)?.let { item to it } }
        val out = LinkedHashSet<DecisionQuestion>()
        for (i in named.indices) for (j in i + 1 until named.size) {
            val (a, na) = named[i]
            val (b, nb) = named[j]
            if (a.language != b.language || na == nb || !aislesMeet(a, b)) continue
            val words = LanguageWords.forTag(a.language) ?: continue
            if (DecisionCandidates.close(na, nb, words)) out += DecisionQuestion.sameGrocery(na, nb, words.language)
        }
        return out.toList()
    }

    /**
     * The trailing-text questions for every line with trailing text (the owner's option 2): cut
     * at a separator ([split]), or after the model's name for it ([nameSplit]). Each is asked
     * once per text and language (the cache), so a lone "2 eggs (dfsafs -" can show "2 eggs".
     */
    fun trailingTexts(items: List<GroceryItem>, decisions: Decisions): List<DecisionQuestion> =
        items.mapNotNull { item ->
            val words = LanguageWords.forTag(item.language) ?: return@mapNotNull null
            split(item.text, words, decisions)?.let { DecisionQuestion.trailingText(it.trailing, words.language) }
        }.distinct()

    /**
     * Where answers that just landed ([fresh]) file lines from Other: beside a line in another
     * aisle now decided the same, or where the table puts a line's core once its trailing text
     * is a note or junk. Only lines in Other move, and only on a fresh answer, so an aisle the
     * user chose stands.
     */
    fun filing(items: List<GroceryItem>, fresh: Set<DecisionQuestion>, decisions: Decisions): Map<Aisle, List<Long>> {
        val moves = LinkedHashMap<Long, Aisle>()
        for (item in items.filter { it.aisle == Aisle.OTHER }) {
            val words = LanguageWords.forTag(item.language) ?: continue
            val split = split(item.text, words, decisions)
            if (split != null && DecisionQuestion.trailingText(split.trailing, words.language) in fresh &&
                decisions.ignorableTrailing(split.trailing, words.language)
            ) {
                Aisles.of(split.core, words).takeIf { it != Aisle.OTHER }?.let { moves[item.id] = it }
            }
            val name = name(item, decisions) ?: continue
            if (item.id in moves) continue
            items.firstOrNull { other ->
                other.aisle != Aisle.OTHER && other.language == item.language &&
                    name(other, decisions)?.let { n ->
                        DecisionQuestion.sameGrocery(name, n, words.language) in fresh &&
                            decisions.sameGrocery(name, n, words.language)
                    } == true
            }?.let { moves[item.id] = it.aisle }
        }
        return moves.entries.groupBy({ it.value }, { it.key })
    }
}
