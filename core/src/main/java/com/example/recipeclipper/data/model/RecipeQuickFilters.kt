package com.example.recipeclipper.data.model

/**
 * The chips under Recipes' search (#378). They combine: a recipe must pass every one chosen, and
 * the search and the sort apply as before. Questions about what is stored, never a filing step,
 * like the views (#345). Not remembered: Recipes opens with none on.
 */
enum class RecipeQuickFilter {
    /** Total time under [RecipeQuickFilters.QUICK_MINUTES]. */
    UNDER_30,

    /** No cooking entry yet: the Cooked view's complement. */
    NOT_COOKED,

    /** At least half of its nameable ingredients are in the pantry. */
    USES_PANTRY
}

/**
 * How many of a recipe's nameable ingredient lines the pantry covers ([have] of [total]): what
 * "Uses what's in my pantry" decides on, and what each matching row says ("You have 6 of 8").
 */
data class PantryCoverage(val have: Int, val total: Int) {
    /** At least half, and at least one line: a recipe with nothing nameable never matches. */
    val matches: Boolean get() = total > 0 && have * 2 >= total
}

/**
 * The quick filters of Recipes (#378), pure and pinned for iOS by the corpus's `Mins`, `Quick`
 * and `Cover` rows.
 *
 * - **Under 30 min** reads the recipe's total time as stored; with none, prep plus cook, and
 *   only when both are present and readable ([Durations.minutes]). A time that can't be read
 *   ("Overnight", "20 to 25 minutes", a zero) never matches: nothing is guessed. A total that is
 *   present but unreadable doesn't fall back to prep plus cook. Strictly under: 30 min is not.
 * - **Not cooked yet** is `lastCookedDay == null`: no cooking entry (a photo of it, or "Mark as
 *   cooked"), the Cooked view's own `EXISTS` turned round.
 * - **Uses what's in my pantry**: of the recipe's nameable ingredient lines (a heading or a line
 *   with no name aside), those the pantry has by [PantryMatch], in stock or a staple, presence
 *   only: [PantryCoverage.matches] when that is at least half. "rice flour" is never "flour".
 */
object RecipeQuickFilters {

    const val QUICK_MINUTES = 30

    /** The minutes [recipe] takes by its stored times, or null when they can't be read. */
    fun minutes(recipe: RecipeSummary): Int? {
        val words = LanguageWords.forTag(recipe.language) ?: if (recipe.language == null) LanguageWords.ENGLISH else null
        recipe.totalTime?.takeIf { it.isNotBlank() }?.let { return Durations.minutes(it, words) }
        val prep = recipe.prepTime?.let { Durations.minutes(it, words) } ?: return null
        val cook = recipe.cookTime?.let { Durations.minutes(it, words) } ?: return null
        return (prep.toLong() + cook).takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    fun underThirty(recipe: RecipeSummary): Boolean = minutes(recipe)?.let { it < QUICK_MINUTES } ?: false

    fun notCooked(recipe: RecipeSummary): Boolean = recipe.lastCookedDay == null

    /** [recipe]'s coverage by [pantry], or null when its language has no tables (nothing can be named). */
    fun coverage(recipe: SavedRecipe, pantry: List<PantryItem>): PantryCoverage? =
        UseItUp.names(recipe)?.let { coverage(it, pantry) }

    fun coverage(names: RecipeNames, pantry: List<PantryItem>): PantryCoverage =
        PantryCoverage(
            have = names.names.count { PantryMatch.status(PantryMatch.find(it, names.language, pantry)) != NeedStatus.BUY },
            total = names.names.size
        )

    /** Every recipe's coverage by id; a recipe whose language has no tables has none. */
    fun coverages(recipes: List<SavedRecipe>, pantry: List<PantryItem>): Map<Long, PantryCoverage> =
        recipes.mapNotNull { r -> coverage(r, pantry)?.let { r.id to it } }.toMap()

    /**
     * [recipes] that pass every chip in [filters], in their order. [coverage] is [coverages]'
     * answer, needed only for [RecipeQuickFilter.USES_PANTRY]; a recipe with none there never
     * matches that chip.
     */
    fun apply(
        recipes: List<RecipeSummary>,
        filters: Set<RecipeQuickFilter>,
        coverage: Map<Long, PantryCoverage> = emptyMap()
    ): List<RecipeSummary> {
        if (filters.isEmpty()) return recipes
        return recipes.filter { r ->
            (RecipeQuickFilter.UNDER_30 !in filters || underThirty(r)) &&
                (RecipeQuickFilter.NOT_COOKED !in filters || notCooked(r)) &&
                (RecipeQuickFilter.USES_PANTRY !in filters || coverage[r.id]?.matches == true)
        }
    }
}
