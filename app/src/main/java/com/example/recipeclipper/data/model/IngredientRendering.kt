package com.example.recipeclipper.data.model

/**
 * Ingredient lines as the reading view shows them: each scaled by [factor], then converted to
 * [system], so a converted amount always matches the chosen servings. The conversion keeps
 * the decimal separator the line was written with ("2,5 lb" doubled is "5 lb", which no longer
 * shows its comma). Shared by every screen that shows or gathers a recipe's lines (#46).
 */
object IngredientRendering {

    fun render(lines: List<String>, factor: Double, system: UnitSystem, convertLiquids: Boolean): List<String> =
        lines.map { UnitConverter.convert(IngredientScaler.scale(it, factor), system, convertLiquids, separatorFrom = it) }
}
