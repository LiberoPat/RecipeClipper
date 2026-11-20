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
    val sortOrder: Int,
    /** The type's stable uid (#26); empty where a type was made without one (a test). */
    val uid: String = ""
) {
    val isBuiltIn: Boolean get() = builtInKey != null

    /**
     * What a setting stores to name this type (#392): [builtInKey] for a seeded one, which
     * survives a rename, else the [uid]. Never the name, which the cook can change.
     */
    val key: String get() = builtInKey ?: uid

    companion object {
        /** New plan entries default to Dinner, and a deleted type's entries move to it. */
        const val DINNER = "dinner"

        /** The type a new plan entry defaults to: Dinner, else the first; null with no types. */
        fun defaultTypeId(types: List<MealType>): Long? =
            (types.firstOrNull { it.builtInKey == DINNER } ?: types.firstOrNull())?.id

        /**
         * The keys ([MealType.key]) of the types ticked in "Plan my week" (#392): [picked] (what the cook
         * chose, or last time's choice) kept to the types that still exist. With nothing picked yet
         * (null), or every picked type gone, it is Dinner's (else the first type's); an empty
         * [picked] is the cook having unticked everything, and stays empty.
         */
        fun chosenKeys(types: List<MealType>, picked: Collection<String>?): Set<String> {
            if (picked != null && picked.isEmpty()) return emptySet()
            val kept = types.map { it.key }.filter { it.isNotEmpty() && picked?.contains(it) == true }.toSet()
            if (kept.isNotEmpty()) return kept
            val default = types.firstOrNull { it.builtInKey == DINNER } ?: types.firstOrNull()
            return setOfNotNull(default?.key?.takeIf { it.isNotEmpty() })
        }

        /**
         * The type a plan's "meal" names (#392): the one with that name, ignoring case and
         * surrounding spaces (the first in the cook's order when two share it); anything else,
         * or no meal, is [defaultTypeId] (Dinner). Never guessed from the recipe.
         */
        fun idForMeal(meal: String?, types: List<MealType>): Long? {
            val wanted = meal?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() }
            val named = wanted?.let { w -> types.firstOrNull { it.name.trim().lowercase(java.util.Locale.ROOT) == w } }
            return named?.id ?: defaultTypeId(types)
        }
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
