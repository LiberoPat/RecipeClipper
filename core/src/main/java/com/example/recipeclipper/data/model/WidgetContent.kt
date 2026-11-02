package com.example.recipeclipper.data.model

/**
 * What the home-screen widgets show (#309). Pure; the widgets on each platform only draw it.
 */
object WidgetContent {

    /** One of today's meals: its meal type's name ("Dinner") and the recipe's title or the note. */
    data class Meal(val mealType: String, val title: String)

    /**
     * "Tonight": [today]'s meals in [meals], in meal type order (Breakfast before Dinner, as
     * [types] are sorted), then in the order the plan holds them. A meal with no title and no
     * note (a recipe that's gone) is left out.
     */
    fun tonight(meals: List<PlannedMeal>, types: List<MealType>, today: Long): List<Meal> {
        val order = types.sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .mapIndexed { index, type -> type.id to index }.toMap()
        val names = types.associate { it.id to it.name }
        return meals.filter { it.day == today }
            .sortedBy { order[it.mealTypeId] ?: Int.MAX_VALUE }
            .mapNotNull { meal ->
                val title = (meal.title ?: meal.note)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Meal(names[meal.mealTypeId].orEmpty(), title)
            }
    }

    /**
     * The grocery list's unticked lines as the Groceries screen shows them, in aisle order: a
     * combined row is its total ("300 g flour"), lines kept together are each listed. A row with
     * any ticked line is in the basket, as "Send list" has it ([GroceryShareText]).
     */
    fun groceries(sections: List<GroceryCombiner.Section>): List<String> =
        sections.flatMap { section ->
            section.rows.filter { row -> row.items.none { it.checked } }.flatMap { row ->
                when (row) {
                    is GroceryCombiner.Row.Single -> listOf(row.item.text.trim())
                    is GroceryCombiner.Row.Combined -> listOf(row.text)
                    is GroceryCombiner.Row.Together -> GroceryCombiner.lineGroups(row).map(GroceryCombiner::lineText)
                }
            }
        }
}
