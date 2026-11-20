package com.example.recipeclipper.ui.recipes

import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.RecipeShareText
import com.example.recipeclipper.ui.recipe.RecipeRenderer
import kotlinx.coroutines.flow.first

/** What a row's Share sends (#376): the recipe's [title] and its plain [text]. */
data class RowShare(val title: String, val text: String)

/**
 * What a recipe's row menu needs from the recipe itself (#376), shared by Recipes and a list's
 * detail: the text Share sends and the yield Add to plan starts from. Neither opens the recipe
 * (that would count as a view and move it to the top); both read it by id.
 */
class RecipeRowSupport(
    private val repository: RecipeRepository,
    private val preferences: AppPreferences,
    private val decisions: DecisionRepository? = null
) {
    /**
     * The recipe as the reading view would send it (#149): the cook's saved servings, their units
     * and temperatures, the same renderer, no source link. Null when the recipe is gone.
     */
    suspend fun share(id: Long, labels: RecipeShareText.Labels): RowShare? {
        val recipe = repository.byIds(listOf(id)).firstOrNull() ?: return null
        val settings = preferences.settings.first()
        val content = RecipeRenderer.content(
            recipe,
            RecipeRenderer.Settings(
                unitSystem = settings.unitSystem,
                convertLiquids = settings.convertLiquids,
                temperatureUnit = settings.temperatureUnit,
                amountsInSteps = settings.amountsInSteps,
                decisions = decisions?.observe()?.first() ?: Decisions.NONE
            )
        )
        val text = RecipeShareText.format(
            recipe = recipe,
            servings = content.servings,
            ingredients = content.ingredients,
            instructions = content.instructions,
            labels = labels
        )
        return RowShare(recipe.name, text)
    }

    /** The recipe's own yield, which "Add to plan" starts on (as the recipe screen passes it); null when it has no number. */
    suspend fun yieldServings(id: Long): Int? {
        val recipe = repository.byIds(listOf(id)).firstOrNull() ?: return null
        return RecipeRenderer.servings(recipe, LanguageWords.forRecipe(recipe))?.base
    }
}
