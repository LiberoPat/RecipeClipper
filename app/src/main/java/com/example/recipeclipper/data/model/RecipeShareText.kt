package com.example.recipeclipper.data.model

/**
 * Turns the recipe as currently shown on screen — scaled servings, converted units — into
 * plain text for sharing outside the app (SMS, WhatsApp, Mail: none render Markdown, so this
 * is plain text only). Pure: takes the already-rendered ingredient and instruction strings,
 * so no scaling or unit-conversion logic is duplicated here.
 *
 * [servings] is null when the recipe has no usable yield, in which case there's nothing to
 * report a serves line for. The line reads "Makes 16" instead of "Serves 16" when the
 * recipe's yield counts things made rather than people ([Servings.kind]).
 *
 * The source link is deliberately left out: what's shared is the recipe as clipped, not a
 * pointer back to the page — the story and ads this app exists to skip.
 *
 * The words around the recipe ("Serves", "INGREDIENTS", "Prep") come in as [Labels], which
 * the screen reads from `strings.xml`, so a recipe shared from a German phone reads
 * "Portionen: 4 … ZUTATEN". This stays pure (no `Context`); [Labels.ENGLISH] is the default
 * for tests. The recipe's own text is shared as written.
 */
object RecipeShareText {

    /**
     * The translated words the message body uses. [serves] and [makes] turn a count into a
     * line ("Serves 3"); [scaled] adds the original count to one ("Serves 3 (originally 6)").
     */
    data class Labels(
        val serves: (Int) -> String,
        val makes: (Int) -> String,
        val scaled: (line: String, original: Int) -> String,
        val prep: String,
        val cook: String,
        val total: String,
        val ingredients: String,
        val instructions: String
    ) {
        companion object {
            val ENGLISH = Labels(
                serves = { "Serves $it" },
                makes = { "Makes $it" },
                scaled = { line, original -> "$line (originally $original)" },
                prep = "Prep",
                cook = "Cook",
                total = "Total",
                ingredients = "INGREDIENTS",
                instructions = "INSTRUCTIONS"
            )
        }
    }

    fun format(
        recipe: Recipe,
        servings: ServingsScale?,
        ingredients: List<String>,
        instructions: List<String>,
        labels: Labels = Labels.ENGLISH
    ): String {
        val lines = mutableListOf<String>()
        lines += recipe.name
        lines += ""

        val meta = listOfNotNull(
            servesLine(servings, Servings.kind(recipe.yield), labels),
            timesLine(recipe, labels)
        )
        if (meta.isNotEmpty()) {
            lines += meta
            lines += ""
        }

        lines += labels.ingredients
        lines += ingredients
        lines += ""

        lines += labels.instructions
        lines += instructions.mapIndexed { index, step -> "${index + 1}. $step" }

        return lines.joinToString("\n")
    }

    private fun servesLine(servings: ServingsScale?, kind: YieldKind, labels: Labels): String? {
        if (servings == null) return null
        val (base, target) = servings
        val word = if (kind == YieldKind.MAKES) labels.makes else labels.serves
        return if (target != base) labels.scaled(word(target), base) else word(base)
    }

    private fun timesLine(recipe: Recipe, labels: Labels): String? {
        val entries = listOfNotNull(
            recipe.prepTime?.let { "${labels.prep} $it" },
            recipe.cookTime?.let { "${labels.cook} $it" },
            recipe.totalTime?.let { "${labels.total} $it" }
        )
        return entries.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
