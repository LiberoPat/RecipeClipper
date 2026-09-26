package com.example.recipeclipper.data.model

/**
 * Ingredient lines as the reading view shows them: each scaled by [factor], then converted to
 * [system], so a converted amount always matches the chosen servings. The conversion keeps
 * the decimal separator the line was written with ("2,5 lb" doubled is "5 lb", which no longer
 * shows its comma). Shared by every screen that shows or gathers a recipe's lines (#46).
 * [words] are the recipe's language's (#14); null leaves every line as written. [decisions]
 * are the model's definite answers about count brackets (#104); [Decisions.NONE] is as before.
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
        lines.map {
            val scaled = IngredientScaler.scale(it, factor, words, decisions.countBracket(it, words?.language))
            UnitConverter.convert(scaled, system, convertLiquids, separatorFrom = it, words = words)
        }
}
