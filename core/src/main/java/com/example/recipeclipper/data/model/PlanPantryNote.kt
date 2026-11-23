package com.example.recipeclipper.data.model

/**
 * What the pantry hasn't got of one planned recipe (#456): every [missing] name not yet on the
 * grocery list, in the recipe's order, and how many more missing names [onList] already are. The
 * note shows [names] and how many [more] there are beyond them ("Not in your pantry: tahini,
 * sumac and 2 more"); opened, it lists [missing], all of them. With nothing left but what is on
 * the list ([allOnList]) it says "On your grocery list".
 */
data class PantryGap(val missing: List<String>, val onList: Int = 0) {
    /** All of them up to [PlanPantryNote.MAX_NAMES]; past that, one fewer, so "more" is never 1. */
    val names: List<String>
        get() = if (missing.size <= PlanPantryNote.MAX_NAMES) missing else missing.take(PlanPantryNote.MAX_NAMES - 1)

    val more: Int get() = missing.size - names.size

    /** Whether the note is capped ("and 3 more"). */
    val capped: Boolean get() = more > 0

    /** Every missing name is on the grocery list already: the note says so, and offers nothing. */
    val allOnList: Boolean get() = missing.isEmpty()
}

/**
 * The Plan's quiet pantry note (#456, `planPantryNote`): under a planned recipe, the ingredients
 * the pantry doesn't have, by name only. Pure, and pinned for iOS by the corpus's `Gap` rows.
 *
 * - The lines are the recipe's **nameable** ones, as "Uses what's in my pantry" (#378) and Use it
 *   up read them ([UseItUp.names]): headings and blank lines aside, then each line's
 *   [IngredientName.of] in the recipe's language; a line with no name is never counted.
 * - Missing is [PantryMatch] and nothing else: no pantry item of that name in the same language,
 *   or one that's out of stock. A staple or an item in stock is had; "rice flour" is never
 *   "flour"; the quantity is never compared (presence only, never "enough"). The model's "same
 *   ingredient" answers (#104, [decisions]) can only turn a missing name into a had one, as in
 *   What I need.
 * - **On the grocery list**: a missing name an unticked grocery line names too (the same
 *   [IngredientName.matches], in the same language) is counted in [PantryGap.onList], not named:
 *   the cook has already seen to it. A ticked line doesn't count (bought is the pantry's to say,
 *   through "Done shopping").
 * - **Nothing to say, no note**: a recipe in a language with no tables, a pantry with nothing in
 *   that language (a cook who doesn't keep a pantry would see it on every meal), or every name had.
 * - One name per ingredient ("onion" and "onions" are one, [IngredientName.key]). Up to
 *   [MAX_NAMES] are named; past that, one fewer and "and N more", so "more" is never 1.
 */
object PlanPantryNote {

    const val MAX_NAMES = 3

    /** The note for a recipe titled [title] in [language] with [lines], or null when there is none. */
    fun gap(
        title: String,
        language: String?,
        lines: List<String>,
        pantry: List<PantryItem>,
        decisions: Decisions = Decisions.NONE,
        groceries: List<GroceryItem> = emptyList()
    ): PantryGap? {
        val names = UseItUp.names(SavedRecipe(0, title, language, lines)) ?: return null
        if (pantry.none { it.language == names.language }) return null
        val words = LanguageWords.forTag(names.language) ?: return null
        val missing = LinkedHashMap<String, String>()
        for (name in names.names) {
            val item = PantryMatch.find(name, names.language, pantry, decisions)
            if (PantryMatch.status(item) == NeedStatus.BUY) missing.putIfAbsent(IngredientName.key(name, words), name)
        }
        if (missing.isEmpty()) return null
        val listed = groceries.filter { !it.checked && LanguageWords.forTag(it.language)?.language == names.language }
            .mapNotNull { IngredientName.of(it.text, words) }
        val (onList, left) = missing.values.partition { name -> listed.any { IngredientName.matches(name, it, words) } }
        return PantryGap(left, onList.size)
    }

    /**
     * Every planned recipe's note in [meals] from [today] on, by [PlannedMeal.id]. A note (no
     * recipe) and a meal in the past have none: the past can't be shopped for.
     */
    fun gaps(
        meals: List<PlannedMeal>,
        pantry: List<PantryItem>,
        today: Long,
        decisions: Decisions = Decisions.NONE,
        groceries: List<GroceryItem> = emptyList()
    ): Map<Long, PantryGap> {
        if (pantry.isEmpty()) return emptyMap()
        return meals.asSequence()
            .filter { it.recipeId != null && it.day >= today }
            .mapNotNull { meal ->
                gap(meal.title.orEmpty(), meal.language, meal.ingredients, pantry, decisions, groceries)?.let { meal.id to it }
            }
            .toMap()
    }

    /**
     * The lines "Add to groceries" puts on the list for [gap]: of [rendered] (the recipe's lines as
     * What I need renders them, at the planned servings, in [language]), each one naming a name
     * still [PantryGap.missing], in order. Headings and lines with no name are never added.
     */
    fun linesToAdd(gap: PantryGap, language: String?, rendered: List<String>): List<String> {
        val words = LanguageWords.forTag(language) ?: return emptyList()
        val keys = gap.missing.map { IngredientName.key(it, words) }.toSet()
        return rendered.filter { line ->
            GrocerySources.buyable(line) && IngredientName.of(line, words)?.let { IngredientName.key(it, words) in keys } == true
        }
    }
}
