package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.ServingsScale
import com.example.recipeclipper.data.model.UnitSystem

/**
 * How the loaded recipe is shown (#24, #234): the settings it renders under (units, liquids,
 * temperatures, amounts in steps, and the model's decisions), its chosen servings, and rendering
 * it again when one of those or Chef mode's short steps change. Pure reducers over
 * [RecipeUiState], which the ViewModel writes; [shortSteps] and [decisions] are Chef mode's
 * current inputs ([ChefMode.shortSteps], [ChefMode.decisions]).
 */
class RecipeDisplay(
    private val shortSteps: () -> List<String?>,
    private val decisions: () -> Decisions
) {

    /** What [RecipeRenderer] renders under while [state] is on screen. */
    fun settings(state: RecipeUiState) = RecipeRenderer.Settings(
        unitSystem = state.unitSystem,
        convertLiquids = state.convertLiquids,
        temperatureUnit = state.temperatureUnit,
        amountsInSteps = state.amountsInSteps,
        decisions = decisions()
    )

    /** [recipe] as first shown, under [state]'s settings; Chef mode's short steps follow later. */
    fun content(recipe: Recipe, state: RecipeUiState): RecipeContent.Success =
        RecipeRenderer.content(recipe, settings(state))

    /**
     * A change to the global defaults, from Settings or from this screen's own dropdown,
     * arriving while the recipe is open. Only a change that affects the text re-renders it:
     * [RecipeUiState.darkWhileCooking] is a display choice and leaves the recipe alone, and
     * scaled servings, ticks and cook progress are kept either way.
     */
    fun withSettings(state: RecipeUiState, settings: AppSettings): RecipeUiState {
        val next = state.copy(
            unitSystem = settings.unitSystem,
            convertLiquids = settings.convertLiquids,
            temperatureUnit = settings.temperatureUnit,
            darkWhileCooking = settings.darkWhileCooking,
            amountsInSteps = settings.amountsInSteps
        )
        val rendersDifferently = next.unitSystem != state.unitSystem ||
            next.convertLiquids != state.convertLiquids ||
            next.temperatureUnit != state.temperatureUnit ||
            next.amountsInSteps != state.amountsInSteps
        return if (rendersDifferently) rerender(next) else next
    }

    /** The units dropdown on the screen: [system], at once, rather than waiting for the echo. */
    fun withUnitSystem(state: RecipeUiState, system: UnitSystem): RecipeUiState =
        rerender(state.copy(unitSystem = system))

    /** The servings stepper: the recipe scaled to [target]; unchanged when it has no yield to scale. */
    fun withServings(state: RecipeUiState, target: Int): RecipeUiState {
        val content = state.content as? RecipeContent.Success ?: return state
        val scaled = RecipeRenderer.withServings(content, target, settings(state)) ?: return state
        return state.copy(content = scaled)
    }

    /** The choice to save for [scale]: the recipe's own yield is saved as no choice at all. */
    fun savedServings(scale: ServingsScale): Int? = scale.target.takeIf { it != scale.base }

    /** Re-renders the loaded recipe under [state]'s settings, keeping its chosen servings. */
    fun rerender(state: RecipeUiState): RecipeUiState {
        val content = state.content as? RecipeContent.Success ?: return state
        return state.copy(content = RecipeRenderer.rerender(content, settings(state), shortSteps()))
    }

    /** Chef mode's short steps as they now stand, rendered like the steps they stand for. */
    fun withShortSteps(state: RecipeUiState): RecipeUiState {
        val content = state.content as? RecipeContent.Success ?: return state
        return state.copy(content = RecipeRenderer.withShortSteps(content, shortSteps(), settings(state)))
    }
}
