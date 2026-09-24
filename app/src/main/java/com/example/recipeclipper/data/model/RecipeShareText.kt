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
 * Deliberately keeps its own English wording ("Serves 3 (originally 6)", "Prep 10m · Cook
 * 30m") rather than reading it from `strings.xml` like the rest of the app's UI text: this
 * isn't UI, it's the body of a message the user sends elsewhere (SMS, WhatsApp, Mail), so it
 * has no `Context`/`stringResource` to read from and stays English-only by design, same as
 * this whole app for now.
 */
object RecipeShareText {

    fun format(
        recipe: Recipe,
        servings: ServingsScale?,
        ingredients: List<String>,
        instructions: List<String>
    ): String {
        val lines = mutableListOf<String>()
        lines += recipe.name
        lines += ""

        val meta = listOfNotNull(servesLine(servings, Servings.kind(recipe.yield)), timesLine(recipe))
        if (meta.isNotEmpty()) {
            lines += meta
            lines += ""
        }

        lines += "INGREDIENTS"
        lines += ingredients
        lines += ""

        lines += "INSTRUCTIONS"
        lines += instructions.mapIndexed { index, step -> "${index + 1}. $step" }

        return lines.joinToString("\n")
    }

    private fun servesLine(servings: ServingsScale?, kind: YieldKind): String? {
        if (servings == null) return null
        val (base, target) = servings
        val word = if (kind == YieldKind.MAKES) "Makes" else "Serves"
        return if (target != base) "$word $target (originally $base)" else "$word $base"
    }

    private fun timesLine(recipe: Recipe): String? {
        val entries = listOfNotNull(
            recipe.prepTime?.let { "Prep $it" },
            recipe.cookTime?.let { "Cook $it" },
            recipe.totalTime?.let { "Total $it" }
        )
        return entries.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
