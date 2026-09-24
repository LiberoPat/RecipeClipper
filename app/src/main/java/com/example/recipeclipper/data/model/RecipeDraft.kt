package com.example.recipeclipper.data.model

/**
 * What the Edit screen saves (#29): a recipe's content as the user typed it, before it is
 * stored. Pure, like the parsers: text in, data out. Nothing here is guessed or converted;
 * the user's words are kept as written, only trimmed.
 */
data class RecipeDraft(
    val name: String = "",
    val yield: String = "",
    val prepTime: String = "",
    val cookTime: String = "",
    val totalTime: String = "",
    /** One ingredient per line, as typed in the ingredients box. */
    val ingredientsText: String = "",
    /** One step per line, as typed in the steps box. */
    val instructionsText: String = "",
    val image: String = ""
) {
    val ingredients: List<String> get() = lines(ingredientsText)
    val instructions: List<String> get() = lines(instructionsText)

    /** The parsers' rule for a recipe: a name, plus ingredients or steps. */
    val isValid: Boolean
        get() = name.isNotBlank() && (ingredients.isNotEmpty() || instructions.isNotEmpty())

    /**
     * [base]'s identity and user state with this draft's content. A blank optional field is
     * absent (null), never an empty string.
     */
    fun applyTo(base: Recipe): Recipe = base.copy(
        name = name.trim(),
        image = image.trim().ifEmpty { null },
        ingredients = ingredients,
        instructions = instructions,
        prepTime = prepTime.trim().ifEmpty { null },
        cookTime = cookTime.trim().ifEmpty { null },
        totalTime = totalTime.trim().ifEmpty { null },
        yield = yield.trim().ifEmpty { null }
    )

    companion object {
        /** A recipe's content as the Edit screen first shows it. */
        fun of(recipe: Recipe) = RecipeDraft(
            name = recipe.name,
            yield = recipe.yield.orEmpty(),
            prepTime = recipe.prepTime.orEmpty(),
            cookTime = recipe.cookTime.orEmpty(),
            totalTime = recipe.totalTime.orEmpty(),
            ingredientsText = recipe.ingredients.joinToString("\n"),
            instructionsText = recipe.instructions.joinToString("\n"),
            image = recipe.image.orEmpty()
        )

        /** One entry per non-blank line, trimmed. */
        fun lines(text: String): List<String> =
            text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
