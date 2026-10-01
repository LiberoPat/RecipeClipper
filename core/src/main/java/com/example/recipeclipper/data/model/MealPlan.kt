package com.example.recipeclipper.data.model

/**
 * A meal type (#49): Breakfast, Lunch, Dinner and Snack are seeded; the user can add more,
 * rename and reorder any, and delete their own. [builtInKey] names a seeded one
 * ("breakfast", "lunch", "dinner", "snack") whatever it has been renamed to; it is null for
 * the user's own. Only those can be deleted.
 */
data class MealType(
    val id: Long,
    val name: String,
    val builtInKey: String?,
    val sortOrder: Int
) {
    val isBuiltIn: Boolean get() = builtInKey != null

    companion object {
        /** New plan entries default to Dinner, and a deleted type's entries move to it. */
        const val DINNER = "dinner"
    }
}

/**
 * One meal on the plan: a recipe with its planned servings, or a free-text note. [title] and
 * [imageUrl] are the recipe's, joined in; both null for a note.
 */
data class PlannedMeal(
    val id: Long,
    val day: Long,
    val mealTypeId: Long,
    val recipeId: Long?,
    val title: String?,
    val imageUrl: String?,
    val servings: Int?,
    val note: String?,
    /** The entry's stable uid (#26): what a calendar export (#52) names the event by. */
    val uid: String = ""
)

/**
 * A reusable weekly menu (#52): a named copy of a week's meals. [mealCount] is how many meals
 * applying it adds.
 */
data class Menu(val id: Long, val name: String, val mealCount: Int)
