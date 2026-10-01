package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.model.IngredientHeading
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.ServingsScale
import com.example.recipeclipper.data.model.StepAmounts

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
        val stepAmounts: List<List<StepAmounts.Part>>? = null,
        /**
         * Chef mode (#100): each step's short version, rendered like [instructions], lined up
         * with them; null (or a short list) where a step has none yet, or none passed the check.
         * [shortStepAmounts] are their amounts inside steps, as [stepAmounts] are the steps'.
         */
        val shortInstructions: List<String?> = emptyList(),
        val shortStepAmounts: List<List<StepAmounts.Part>>? = null
    ) : RecipeContent() {
        private fun showsShort(index: Int, asWritten: Set<Int>) =
            shortInstructions.getOrNull(index) != null && index !in asWritten

        /** Step [index]'s short version, unless the cook asked to see it as written. */
        fun shownStep(index: Int, asWritten: Set<Int>): String =
            if (showsShort(index, asWritten)) shortInstructions[index]!! else instructions[index]

        /** The amounts inside the step as [shownStep] shows it; null when amounts are off. */
        fun shownStepAmounts(index: Int, asWritten: Set<Int>): List<StepAmounts.Part>? = when {
            stepAmounts == null -> null
            showsShort(index, asWritten) -> shortStepAmounts?.getOrNull(index)
            else -> stepAmounts.getOrNull(index)
        }

        fun hasShortStep(index: Int): Boolean = shortInstructions.getOrNull(index) != null

        /**
         * Ingredient line [index] is a group heading ("For the sauce:"): drawn as a subheading
         * with no box, never ticked. Ticks stay keyed by line index, so every line keeps its own.
         */
        fun isHeading(index: Int): Boolean = ingredients.getOrNull(index)?.let(IngredientHeading::isHeading) == true

        /** The ingredient lines that aren't headings: what cook mode's bar counts. */
        val ingredientCount: Int get() = ingredients.count { !IngredientHeading.isHeading(it) }
    }

    data class Error(val error: ParseError) : RecipeContent()
}
