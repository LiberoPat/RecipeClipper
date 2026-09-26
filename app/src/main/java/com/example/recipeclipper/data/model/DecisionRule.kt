package com.example.recipeclipper.data.model

/** One reply from the model: its pick and how sure it says it is ("high", "medium", "low"). */
data class DecisionReply(val answer: String, val confidence: String)

/**
 * The confidence rule (#104). On-device models give no calibrated probabilities, so a decision
 * counts only when the model is asked [ASKS] times, with the options listed in a different
 * order each time ([options]: against a bias for the first or last option), and every reply
 * picks the same definite option with confidence "high". Anything else (a disagreement,
 * "unsure", medium or low confidence, an option not on the list) is [DecisionKind.UNSURE].
 */
object DecisionRule {

    const val ASKS = 2

    fun judge(kind: DecisionKind, replies: List<DecisionReply>): String {
        if (replies.size != ASKS) return DecisionKind.UNSURE
        val answers = replies.map { it.answer.trim().lowercase() }
        val first = answers.first()
        val agreed = first in kind.options && answers.all { it == first }
        val sure = replies.all { it.confidence.trim().lowercase() == "high" }
        return if (agreed && sure) first else DecisionKind.UNSURE
    }

    /** The choices as ask [n] (0-based) lists them: as declared, then reversed; "unsure" always last. */
    fun options(kind: DecisionKind, n: Int): List<String> =
        (if (n % 2 == 0) kind.options else kind.options.reversed()) + DecisionKind.UNSURE
}

/** What the model is asked: [instructions], then [text], answering with one of [options]. */
data class DecisionPrompt(val kind: DecisionKind, val instructions: String, val text: String, val options: List<String>)

/** The words of each question. Pure; the same text on both platforms. */
object DecisionPrompts {

    private val LANGUAGES = mapOf(
        "en" to "English", "de" to "German", "es" to "Spanish", "fr" to "French",
        "it" to "Italian", "pt" to "Portuguese", "ja" to "Japanese"
    )

    fun prompt(question: DecisionQuestion, n: Int): DecisionPrompt {
        val options = DecisionRule.options(question.kind, n)
        val language = LANGUAGES[question.language] ?: question.language
        val (instructions, text) = when (question.kind) {
            DecisionKind.COUNT_BRACKET -> COUNT to "Ingredient line ($language): ${question.input}"
            DecisionKind.SAME_INGREDIENT -> question.input.split(DecisionQuestion.PAIR).let { (a, b) ->
                SAME to "Ingredient names ($language): \"$a\" and \"$b\""
            }
            DecisionKind.AISLE -> AISLE to "Ingredient ($language): ${question.input}"
            DecisionKind.SAME_GROCERY -> question.input.split(DecisionQuestion.PAIR).let { (a, b) ->
                SAME_GROCERY to "Shopping list items ($language): \"$a\" and \"$b\""
            }
            DecisionKind.TRAILING_TEXT -> TRAILING to "Text after the ingredient ($language): ${question.input}"
        }
        val closing = "\nAnswer with one of: ${options.joinToString(", ")}. Give your confidence: high, medium or low. " +
            "Answer \"unsure\" whenever you are not certain."
        return DecisionPrompt(question.kind, instructions + closing, text, options)
    }

    private val SAME_GROCERY = """
        Two items on a shopping list, each the ingredient named in a recipe line. Would a shopper buy the
        same product for both ("same"), or are they different products ("different")? Only a different
        wording of one product is the same: "ears of corn" and "corn", "corn on the cob" and "corn",
        "garlic cloves" and "garlic". A word that makes another product is different: "rice flour" is not
        "flour", "whole milk" is not "milk", "brown sugar" is not "sugar", "corn flour" is not "corn".
    """.trimIndent()

    private val TRAILING = """
        A shopping list line from a recipe has some text after the ingredient's name. What is that text?
        "note": only a description or preparation, no amount and no other ingredient (", shucked",
        ", finely chopped", "(optional)", ", at room temperature"). "second_amount": it gives another
        amount or size, or another ingredient or an alternative ("(about three cups)", "plus two yolks",
        ", or frozen corn", "and some for the pan"). "junk": meaningless characters or a typing error
        ("(dfsafs -", "--- xx").
    """.trimIndent()

    private val COUNT = """
        A recipe's ingredient line starts with a count of items and has an amount in brackets after the name.
        Is the bracketed amount the total for all the items together ("total"), or the size of each single
        item ("each")? For example "4 apples (about 800 g)" is total: four apples weigh 800 g together.
        "3 large apples, peeled and sliced (about 3 cups)" is total. "2 chicken breasts (200 g each)" is each.
    """.trimIndent()

    private val SAME = """
        One name is an ingredient in a recipe, the other an item in the cook's pantry. Are they the same
        ingredient, so the pantry item is what the recipe asks for ("same"), or different ("different")?
        A name with an extra word that makes a different product is different: "rice flour" is not "flour",
        "whole milk" is not "milk", "brown sugar" is not "sugar". Only names for the same product are the same:
        "plain flour" and "all-purpose flour", "double cream" and "heavy cream".
    """.trimIndent()

    private val AISLE = """
        Which supermarket aisle is this ingredient found in? Use "other" when none of the aisles fits.
    """.trimIndent()
}
