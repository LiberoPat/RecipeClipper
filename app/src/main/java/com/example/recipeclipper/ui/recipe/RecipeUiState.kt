package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.ServingsScale
import com.example.recipeclipper.data.model.StepAmounts
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem

/** A countdown on one step. [alerted] is set once the "time's up" sound has played. */
data class StepTimer(
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val running: Boolean,
    val alerted: Boolean = false
) {
    val finished: Boolean get() = remainingSeconds == 0 && !running
}

/**
 * Cook mode is a boolean on the recipe screen ([active]), not a destination. Progress is
 * kept when the user leaves and returns, so it is never lost by a stray tap on Exit.
 * [currentStep] is what is being cooked now; [doneSteps] are struck off. Tapping any step
 * moves [currentStep] without touching [doneSteps], so jumping back loses nothing.
 * Saved as it changes ([com.example.recipeclipper.data.model.CookProgress]), so a closed or
 * killed app picks up at the same step, with its timers still counting.
 *
 * Screen state, not domain: unlike [ServingsScale] this stays in `ui/`, since `active`,
 * `ingredientsExpanded` and `alerted` describe what the screen is doing, not the recipe.
 */
data class CookState(
    val active: Boolean = false,
    val currentStep: Int = 0,
    val doneSteps: Set<Int> = emptySet(),
    val timers: Map<Int, StepTimer> = emptyMap(),
    val ingredientsExpanded: Boolean = false
)

sealed class RecipeContent {
    object Loading : RecipeContent()

    /**
     * [ingredients] and [instructions] are what the screen shows: the recipe's own lists
     * with servings scaling and unit/temperature conversion applied. [servings] is null
     * when the recipe's yield has no usable number, in which case there is nothing to
     * scale from. [stepTimerSeconds] lines up with the steps: the duration each one states,
     * or null. [sourceDomain] is the site credited under the title ("smittenkitchen.com"),
     * or null when the source link has no recognisable host (then no credit is shown).
     * [words] are the recipe's language's (#14), which the view uses for the yield's kind and
     * the timer labels; null for a language the app has no words for. [stepAmounts] lines up
     * with [instructions]: each step with the ingredient amounts inside it (#101), or null
     * when "Amounts in steps" is off.
     */
    data class Success(
        val recipe: Recipe,
        val servings: ServingsScale?,
        val ingredients: List<String>,
        val instructions: List<String>,
        val stepTimerSeconds: List<Int?>,
        val sourceDomain: String?,
        val words: LanguageWords? = LanguageWords.forRecipe(recipe),
        val stepAmounts: List<List<StepAmounts.Part>>? = null
    ) : RecipeContent()

    data class Error(val error: ParseError) : RecipeContent()
}

/**
 * [unitSystem], [convertLiquids] and [temperatureUnit] are the user's global defaults: set
 * once (now from the Settings screen), applied to every recipe, and saved between sessions.
 * Servings, by contrast, belong to one recipe. [convertLiquids] only matters for OUNCES.
 * [temperatureUnit] is independent of [unitSystem] — see [TemperatureUnit]'s doc.
 * [darkWhileCooking] forces the ink scheme in cook mode even in light mode; off by default,
 * so cook mode follows the system theme like every other screen. [amountsInSteps] is the
 * Settings switch (#101), behind the `amountsInSteps` flag, which the screen checks.
 */
data class RecipeUiState(
    val content: RecipeContent = RecipeContent.Loading,
    val checkedIngredients: Set<Int> = emptySet(),
    /** The user's note as typed; empty when there is none. Saved by the ViewModel. */
    val notes: String = "",
    val unitSystem: UnitSystem = UnitSystem.AS_WRITTEN,
    val convertLiquids: Boolean = false,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.AS_WRITTEN,
    val darkWhileCooking: Boolean = false,
    val amountsInSteps: Boolean = false,
    val cook: CookState = CookState(),
    /** Set once the recipe has been deleted, so the screen can navigate back. */
    val deleted: Boolean = false,
    /**
     * The prefilled "Report this site" issue link. Non-null only while a shared link's
     * [ParseError.NoRecipeFound] is on screen: never for a block, offline or a failed fetch,
     * which mean "try again", not "unsupported". The screen opens it; nothing is sent.
     */
    val reportSiteUrl: String? = null,
    /** "Update from source" (#29) is fetching; the recipe stays on screen meanwhile. */
    val updatingFromSource: Boolean = false,
    /** Why the last "Update from source" failed, until the screen has shown it. The recipe
     *  on screen is unchanged. */
    val updateError: ParseError? = null,
    /**
     * The shared link to clip by hand ("Clip it yourself", #37). Set exactly when
     * [reportSiteUrl] is: only a page that loaded with no recipe data can be clipped.
     */
    val clipUrl: String? = null
)
