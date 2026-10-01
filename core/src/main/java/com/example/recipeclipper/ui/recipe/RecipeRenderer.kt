package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.IngredientRendering
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.Servings
import com.example.recipeclipper.data.model.ServingsScale
import com.example.recipeclipper.data.model.SourceDomain
import com.example.recipeclipper.data.model.StepAmounts
import com.example.recipeclipper.data.model.StepTimers
import com.example.recipeclipper.data.model.TemperatureConverter
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem

/**
 * Turns a recipe into what the reading view and cook mode show (#169): the ingredients without
 * the junk the model decided (#174), scaled and converted, the steps with their oven
 * temperatures converted, each step's timer, the amounts inside steps (#101) and Chef mode's
 * short steps (#100). Pure: the same inputs always render the same, with no state or
 * coroutines, so the ViewModel only decides when to render.
 * iOS's `RecipeRenderer` is pinned to it by the `Render` rows of the differential corpus.
 */
object RecipeRenderer {

    /**
     * What a recipe renders under: the user's global defaults (see [RecipeUiState]) and the
     * on-device model's decisions (#104: count brackets, and junk after an ingredient, #174),
     * [Decisions.NONE] until one lands.
     */
    data class Settings(
        val unitSystem: UnitSystem = UnitSystem.AS_WRITTEN,
        val convertLiquids: Boolean = false,
        val temperatureUnit: TemperatureUnit = TemperatureUnit.AS_WRITTEN,
        val amountsInSteps: Boolean = false,
        val decisions: Decisions = Decisions.NONE
    )

    /**
     * [recipe] as the screen shows it, at its chosen servings (else its own yield), with
     * [shortSteps] (Chef mode's, as saved: one per step, null for none) rendered like the steps.
     */
    fun content(recipe: Recipe, settings: Settings, shortSteps: List<String?> = emptyList()): RecipeContent.Success {
        // The recipe's language picks the words, never the phone's (#14).
        val words = LanguageWords.forRecipe(recipe)
        val servings = servings(recipe, words)
        val content = RecipeContent.Success(
            recipe = recipe,
            servings = servings,
            ingredients = ingredients(recipe, words, servings, settings),
            instructions = instructions(recipe, words, settings.temperatureUnit),
            stepTimerSeconds = recipe.instructions.map { StepTimers.parse(it, words) },
            sourceDomain = SourceDomain.of(recipe.sourceUrl),
            words = words
        )
        return withShortSteps(content, shortSteps, settings)
    }

    /** The yield's servings stepper at the chosen servings; null when the yield has no number. */
    fun servings(recipe: Recipe, words: LanguageWords?): ServingsScale? {
        val base = Servings.parse(recipe.yield, words) ?: return null
        return ServingsScale(base = base, target = recipe.servingsTarget?.coerceIn(1, Servings.MAX) ?: base)
    }

    /**
     * [content] at [target] servings, kept within the stepper's range; null when it has no
     * stepper. Only the ingredients (and the amounts inside steps) change.
     */
    fun withServings(content: RecipeContent.Success, target: Int, settings: Settings): RecipeContent.Success? {
        val servings = content.servings ?: return null
        val scale = servings.copy(target = target.coerceIn(1, Servings.MAX))
        return withStepAmounts(
            content.copy(servings = scale, ingredients = ingredients(content.recipe, content.words, scale, settings)),
            settings.amountsInSteps
        )
    }

    /** [content] again under new [settings], keeping its chosen servings. */
    fun rerender(content: RecipeContent.Success, settings: Settings, shortSteps: List<String?>): RecipeContent.Success =
        withShortSteps(
            content.copy(
                ingredients = ingredients(content.recipe, content.words, content.servings, settings),
                instructions = instructions(content.recipe, content.words, settings.temperatureUnit)
            ),
            shortSteps,
            settings
        )

    /**
     * Chef mode's saved short steps, rendered like the steps they stand for. They show only
     * when there is one entry per step; otherwise (none yet, or steps since changed) none do.
     */
    fun withShortSteps(content: RecipeContent.Success, shortSteps: List<String?>, settings: Settings): RecipeContent.Success {
        val shorts = shortSteps.takeIf { it.size == content.recipe.instructions.size }.orEmpty()
        val rendered = shorts.map { short -> short?.let { step(it, content.words, settings.temperatureUnit) } }
        return withStepAmounts(content.copy(shortInstructions = rendered), settings.amountsInSteps)
    }

    // Junk hidden first (#174), then scaled, then converted, so a converted amount always matches
    // the chosen servings. Every line stays at its index, so ticks never shift.
    fun ingredients(recipe: Recipe, words: LanguageWords?, servings: ServingsScale?, settings: Settings): List<String> {
        val factor = servings?.let { it.target.toDouble() / it.base } ?: 1.0
        return IngredientRendering.render(
            recipe.ingredients, factor, settings.unitSystem, settings.convertLiquids, words, settings.decisions
        )
    }

    // Instructions aren't scaled (a step can mention any number), but oven temperatures
    // follow the chosen temperature unit — independent of the ingredient unit system.
    fun instructions(recipe: Recipe, words: LanguageWords?, temperatureUnit: TemperatureUnit): List<String> =
        recipe.instructions.map { step(it, words, temperatureUnit) }

    /** One step as shown, as written or Chef mode's short version (#100): the same rendering. */
    fun step(step: String, words: LanguageWords?, temperatureUnit: TemperatureUnit): String =
        TemperatureConverter.convert(step, temperatureUnit, words)

    /**
     * Amounts inside steps (#101), from the lines as rendered, so they follow servings and units;
     * Chef mode's short steps (#100) get theirs the same way. Null when [on] is false.
     */
    fun withStepAmounts(content: RecipeContent.Success, on: Boolean): RecipeContent.Success = content.copy(
        stepAmounts = if (on) StepAmounts.annotate(content.instructions, content.ingredients, content.words) else null,
        shortStepAmounts = if (on && content.shortInstructions.any { it != null }) {
            StepAmounts.annotate(content.shortInstructions.map { it.orEmpty() }, content.ingredients, content.words)
        } else {
            null
        }
    )
}
