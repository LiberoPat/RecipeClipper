package com.example.recipeclipper.data.model

/**
 * The typed decisions the on-device model makes (#104), each a pick from [options] or
 * [UNSURE]. [key] is what the cache stores. Code acts only on a definite answer that passed
 * [DecisionRule]; anything else keeps today's behaviour.
 */
enum class DecisionKind(val key: String, val options: List<String>) {
    /** A count's bracket ("4 Apfel (ca. 800g)"): the total for all the items, or each one's size. */
    COUNT_BRACKET("countBracket", listOf("total", "each")),

    /** A recipe's ingredient and a pantry item with close names: the same thing, or not. */
    SAME_INGREDIENT("sameIngredient", listOf("same", "different")),

    /** The aisle of an ingredient the keyword table puts in Other. "other" keeps it there. */
    AISLE("aisle", Aisle.entries.map { it.key }),

    /** Two grocery lines' close names (#99): the same thing to buy, so one row, or not. */
    SAME_GROCERY("sameGrocery", listOf("same", "different")),

    /**
     * The text after a grocery line's ingredient (#99): a note (", shucked"), maybe a second
     * amount or ingredient ("(about three cups)", ", or frozen"), or junk ("(dfsafs -").
     */
    TRAILING_TEXT("trailingText", listOf("note", "second_amount", "junk")),

    /**
     * The ingredient's name in a grocery line with no separator ("2 onions dfsafs" is "onions"),
     * answered as free text (no [options]). Used only when it is in the line as whole words
     * ([GroceryDecisions.nameSplit]); the rest of the line is then a [TRAILING_TEXT] question.
     */
    INGREDIENT_NAME("ingredientName", emptyList());

    /** True when the answer is free text, not a pick from [options]. */
    val freeText: Boolean get() = options.isEmpty()

    companion object {
        const val UNSURE = "unsure"

        fun fromKey(key: String): DecisionKind? = entries.firstOrNull { it.key == key }
    }
}

/** A definite answer about a count's bracket. Unsure is null: the line stays as written. */
enum class CountBracket { TOTAL, EACH }

/**
 * One question: its [kind], its [input] normalised (so the same question is asked once) and
 * the recipe's [language] tag. What the cache is keyed by.
 */
data class DecisionQuestion(val kind: DecisionKind, val input: String, val language: String) {
    companion object {
        private val WHITESPACE = Regex("""\s+""")

        fun normalize(text: String): String = WHITESPACE.replace(text.trim(), " ").lowercase()

        fun countBracket(line: String, language: String) =
            DecisionQuestion(DecisionKind.COUNT_BRACKET, normalize(line), language)

        /** Either order is the same question. */
        fun sameIngredient(a: String, b: String, language: String) =
            DecisionQuestion(DecisionKind.SAME_INGREDIENT, listOf(normalize(a), normalize(b)).sorted().joinToString(PAIR), language)

        fun aisle(name: String, language: String) = DecisionQuestion(DecisionKind.AISLE, normalize(name), language)

        /** Either order is the same question. */
        fun sameGrocery(a: String, b: String, language: String) =
            DecisionQuestion(DecisionKind.SAME_GROCERY, listOf(normalize(a), normalize(b)).sorted().joinToString(PAIR), language)

        fun trailingText(text: String, language: String) =
            DecisionQuestion(DecisionKind.TRAILING_TEXT, normalize(text), language)

        fun ingredientName(line: String, language: String) =
            DecisionQuestion(DecisionKind.INGREDIENT_NAME, normalize(line), language)

        /** What joins a pair's two names in [input]. */
        const val PAIR = " | "
    }
}

/** Every answer cached so far, by question: what the screens apply. [NONE] is today's behaviour. */
class Decisions(private val answers: Map<DecisionQuestion, String>) {

    fun countBracket(line: String, language: String?): CountBracket? = when (
        language?.let { answers[DecisionQuestion.countBracket(line, it)] }
    ) {
        "total" -> CountBracket.TOTAL
        "each" -> CountBracket.EACH
        else -> null
    }

    /** True only for a definite "same": never false-to-true on anything else. */
    fun sameIngredient(a: String, b: String, language: String?): Boolean =
        language != null && answers[DecisionQuestion.sameIngredient(a, b, language)] == "same"

    /** A definite aisle other than Other, or null. */
    fun aisle(name: String, language: String?): Aisle? =
        language?.let { answers[DecisionQuestion.aisle(name, it)] }
            ?.let { key -> Aisle.entries.firstOrNull { it.key == key && it != Aisle.OTHER } }

    /** True only for a definite "same" about two grocery names. */
    fun sameGrocery(a: String, b: String, language: String?): Boolean =
        language != null && answers[DecisionQuestion.sameGrocery(a, b, language)] == "same"

    /** True only when the text after a grocery line's ingredient is definitely a note or junk. */
    fun ignorableTrailing(text: String, language: String?): Boolean =
        language != null && answers[DecisionQuestion.trailingText(text, language)].let { it == "note" || it == "junk" }

    /** True only when the text after a grocery line's ingredient is definitely junk: Groceries hides it. */
    fun junkTrailing(text: String, language: String?): Boolean =
        language != null && answers[DecisionQuestion.trailingText(text, language)] == "junk"

    /** The model's agreed name for a grocery [line], unchecked ([GroceryDecisions.nameSplit] checks it), or null. */
    fun ingredientName(line: String, language: String?): String? =
        language?.let { answers[DecisionQuestion.ingredientName(line, it)] }?.takeIf { it != DecisionKind.UNSURE }

    fun isAnswered(question: DecisionQuestion): Boolean = question in answers

    override fun equals(other: Any?) = other is Decisions && other.answers == answers
    override fun hashCode() = answers.hashCode()

    companion object {
        val NONE = Decisions(emptyMap())
    }
}
