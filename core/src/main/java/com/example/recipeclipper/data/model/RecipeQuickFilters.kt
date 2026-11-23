package com.example.recipeclipper.data.model

/**
 * The chips under Recipes' search (#378). They combine: a recipe must pass every one chosen, and
 * the search and the sort apply as before. Questions about what is stored, never a filing step,
 * like the views (#345). Not remembered: Recipes opens with none on.
 */
enum class RecipeQuickFilter {
    /** Total time of [RecipeQuickFilters.QUICK_MINUTES] or less. */
    THIRTY_OR_LESS,

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
 * - **30 min or less** reads the recipe's total time as stored; with none, prep plus cook, and
 *   only when both are present and readable ([Durations.minutes]). A time that can't be read
 *   ("Overnight", "20 to 25 minutes", a zero) never matches: nothing is guessed. A total that is
 *   present but unreadable doesn't fall back to prep plus cook. 30 min or less: 30 min itself counts.
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

    fun thirtyOrLess(recipe: RecipeSummary): Boolean = minutes(recipe)?.let { it <= QUICK_MINUTES } ?: false

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
            (RecipeQuickFilter.THIRTY_OR_LESS !in filters || thirtyOrLess(r)) &&
                (RecipeQuickFilter.NOT_COOKED !in filters || notCooked(r)) &&
                (RecipeQuickFilter.USES_PANTRY !in filters || coverage[r.id]?.matches == true)
        }
    }
}

/** "Cooked" in the filter sheet (#454): one choice, folding the Cooked view and "Not cooked yet". */
enum class CookedChoice { ANY, COOKED, NOT_YET }

/** "Came from" in the filter sheet (#454): a recipe is typed by you or from a chat, never both, so one choice. */
enum class OriginChoice { ANYWHERE, TYPED, CHAT }

/** One filter on, as a pill under search (#454), in the sheet's order; a tap on it turns that filter off. */
enum class RecipeFilterPill { THIRTY_OR_LESS, COOKED, NOT_COOKED, USES_PANTRY, NOT_IN_A_LIST, TYPED, CHAT }

/**
 * The filter sheet's five controls (#454, `recipesFilters`), with the views folded in: two switches and a
 * choice about the recipe (30 min or less, Cooked: Any / Cooked / Not yet, Uses what's in my pantry), a
 * switch and a choice about where it is and where it came from (Not in a list, Came from: Anywhere /
 * Typed by you / From a chat). They combine (AND) with each other, the search and the sort; questions
 * about what is stored, never filing; not remembered.
 *
 * Nothing is re-derived. [views] are the view screens' own filters (#345), asked of the one SQL query
 * that answers those screens (the same `EXISTS` and `contentOrigin` tests, AND-ed); [quick] are #378's
 * rules, applied over its answer. Cooked is the Cooked view; Not yet is "Not cooked yet", that view's
 * complement (`RecipeQuickFiltersDaoTest`).
 */
data class RecipeFilterChoices(
    val thirtyOrLess: Boolean = false,
    val cooked: CookedChoice = CookedChoice.ANY,
    val usesPantry: Boolean = false,
    val notInAList: Boolean = false,
    val origin: OriginChoice = OriginChoice.ANYWHERE
) {
    /** The view screens' filters these ask for, answered by the database beside the search. */
    val views: Set<RecipeFilter>
        get() = buildSet {
            if (notInAList) add(RecipeFilter.NOT_IN_A_LIST)
            if (cooked == CookedChoice.COOKED) add(RecipeFilter.COOKED)
            when (origin) {
                OriginChoice.TYPED -> add(RecipeFilter.TYPED)
                OriginChoice.CHAT -> add(RecipeFilter.CHAT)
                OriginChoice.ANYWHERE -> Unit
            }
        }

    /** #378's filters these ask for, applied to the database's answer ([RecipeQuickFilters.apply]). */
    val quick: Set<RecipeQuickFilter>
        get() = buildSet {
            if (thirtyOrLess) add(RecipeQuickFilter.THIRTY_OR_LESS)
            if (cooked == CookedChoice.NOT_YET) add(RecipeQuickFilter.NOT_COOKED)
            if (usesPantry) add(RecipeQuickFilter.USES_PANTRY)
        }

    /** The filters on, in the sheet's order: the pills under search; their number is the badge's. */
    val pills: List<RecipeFilterPill>
        get() = buildList {
            if (thirtyOrLess) add(RecipeFilterPill.THIRTY_OR_LESS)
            when (cooked) {
                CookedChoice.COOKED -> add(RecipeFilterPill.COOKED)
                CookedChoice.NOT_YET -> add(RecipeFilterPill.NOT_COOKED)
                CookedChoice.ANY -> Unit
            }
            if (usesPantry) add(RecipeFilterPill.USES_PANTRY)
            if (notInAList) add(RecipeFilterPill.NOT_IN_A_LIST)
            when (origin) {
                OriginChoice.TYPED -> add(RecipeFilterPill.TYPED)
                OriginChoice.CHAT -> add(RecipeFilterPill.CHAT)
                OriginChoice.ANYWHERE -> Unit
            }
        }

    val isEmpty: Boolean get() = this == NONE

    /** These with [pill]'s filter off: a switch off, a choice back to Any / Anywhere. */
    fun without(pill: RecipeFilterPill): RecipeFilterChoices = when (pill) {
        RecipeFilterPill.THIRTY_OR_LESS -> copy(thirtyOrLess = false)
        RecipeFilterPill.COOKED, RecipeFilterPill.NOT_COOKED -> copy(cooked = CookedChoice.ANY)
        RecipeFilterPill.USES_PANTRY -> copy(usesPantry = false)
        RecipeFilterPill.NOT_IN_A_LIST -> copy(notInAList = false)
        RecipeFilterPill.TYPED, RecipeFilterPill.CHAT -> copy(origin = OriginChoice.ANYWHERE)
    }

    companion object {
        /** Nothing chosen: every recipe. */
        val NONE = RecipeFilterChoices()
    }
}
