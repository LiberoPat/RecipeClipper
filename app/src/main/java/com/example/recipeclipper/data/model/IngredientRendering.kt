package com.example.recipeclipper.data.model

/**
 * Ingredient lines as the reading view shows them: each scaled by [factor], then converted to
 * [system], so a converted amount always matches the chosen servings. The conversion keeps
 * the decimal separator the line was written with ("2,5 lb" doubled is "5 lb", which no longer
 * shows its comma). Shared by every screen that shows or gathers a recipe's lines (#46).
 * [words] are the recipe's language's (#14); null leaves every line as written. [decisions]
 * are the model's definite answers (#104): count brackets, and junk after the ingredient,
 * hidden first as Groceries hides it (#174, [GroceryDecisions.shownLine]), so the rest still
 * scales and converts. [Decisions.NONE] is as before.
 */
object IngredientRendering {

    fun render(
        lines: List<String>,
        factor: Double,
        system: UnitSystem,
        convertLiquids: Boolean,
        words: LanguageWords? = LanguageWords.ENGLISH,
        decisions: Decisions = Decisions.NONE
    ): List<String> =
        lines.map { line ->
            // A line with junk never has a count bracket: that holds a digit, which is never cut.
            val shown = GroceryDecisions.shownLine(line, words, decisions)
            val scaled = IngredientScaler.scale(shown, factor, words, decisions.countBracket(line, words?.language))
            UnitConverter.convert(scaled, system, convertLiquids, separatorFrom = shown, words = words)
        }
}
