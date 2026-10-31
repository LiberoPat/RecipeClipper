package com.example.recipeclipper.data.model

import java.util.Locale

/**
 * A saved recipe (in at least one list) as "Use it up" (#305) reads it: its ingredient [lines]
 * as stored (not scaled or converted: only the names are read), in [language].
 */
data class SavedRecipe(
    val id: Long,
    val title: String,
    val imageUrl: String?,
    val language: String?,
    val lines: List<String>
)

/** One pantry [item] about to expire, and the saved [recipes] that use it, A–Z. */
data class UseItUpGroup(val item: PantryItem, val recipes: List<SavedRecipe>)

/**
 * "Use it up" (#305): the saved recipes that use a pantry item about to expire. Pure.
 *
 * - **Soon** is the expiry reminder's window ([ExpiryReminders.due]): an item in stock, not a
 *   staple, expiring today or tomorrow. An item already past its date isn't offered.
 * - **Only saved recipes**, never one made up (#165) or one merely viewed: the caller passes
 *   the recipes in at least one list.
 * - A recipe **uses** an item when one of its lines names it as Have/Buy matches
 *   ([PantryMatch.find], by name only: "unsalted butter" is "butter", "rice flour" is never
 *   "flour"), in the same language. Presence only: never "enough of it".
 */
object UseItUp {

    fun soon(item: PantryItem, today: Long): Boolean = ExpiryReminders.due(item, today)

    /** Whether one of [recipe]'s lines (a heading aside) names [item]. */
    fun uses(recipe: SavedRecipe, item: PantryItem): Boolean {
        val words = LanguageWords.forTag(recipe.language) ?: return false
        return recipe.lines.any { line ->
            GrocerySources.buyable(line) &&
                IngredientName.of(line, words)?.let { PantryMatch.find(it, words.language, listOf(item)) } != null
        }
    }

    /** The recipes in [saved] that use [item], by title A–Z. */
    fun recipes(item: PantryItem, saved: List<SavedRecipe>): List<SavedRecipe> =
        saved.filter { uses(it, item) }.sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.title }, { it.id }))

    /**
     * Each item of [pantry] expiring soon with at least one saved recipe that uses it: soonest
     * first, then by name A–Z.
     */
    fun groups(pantry: List<PantryItem>, saved: List<SavedRecipe>, today: Long): List<UseItUpGroup> =
        pantry.filter { soon(it, today) }
            .sortedWith(compareBy({ it.expiresDay }, { it.name.trim().lowercase(Locale.ROOT) }, { it.id }))
            .map { UseItUpGroup(it, recipes(it, saved)) }
            .filter { it.recipes.isNotEmpty() }

    /** The days a recipe for [item] can be planned on with one tap: today, then its use-by day. */
    fun planDays(item: PantryItem, today: Long): List<Long> =
        listOfNotNull(today, item.expiresDay?.takeIf { it > today })
}
