package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.model.IngredientHeading
import com.example.recipeclipper.data.model.StepAmounts

/**
 * Cook mode's ingredients bar, opened (#286): the lines the current step names first, under
 * "In this step" and washed in paprika ([now]), then the rest under "Later" ([later]). Each is
 * line indexes into the shown ingredients, in the order they show; every line, headings
 * included, is in exactly one of them, so ticks stay keyed by line index.
 *
 * Which lines a step names is the amounts-in-steps matching ([StepAmounts.named], #254), read
 * whether "Amounts in steps" is on or off; the steps show the same either way.
 *
 * A group heading ("For the sauce:") moves to [now] only when every line under it does, and
 * its lines sit under it there, after the step's other lines; otherwise it stays in [later]
 * over the lines still to come, and the step's lines from its group show without it.
 */
data class StepIngredients(val now: List<Int>, val later: List<Int>) {

    companion object {
        /**
         * The bar for cook mode's current step in [state], or null to list every line as
         * written: not cooking, no recipe, or a step that names no line.
         */
        fun of(state: RecipeUiState): StepIngredients? {
            val content = state.content as? RecipeContent.Success ?: return null
            val step = state.cook.currentStep
            if (!state.cook.active || step !in content.instructions.indices) return null
            // The step as cook mode shows it: Chef mode's short version, unless asked for as written.
            val shown = content.shownStep(step, state.asWrittenSteps)
            return group(content.ingredients, StepAmounts.named(shown, content.ingredients, content.words))
        }

        /** [lines] split by the [named] ones, as above; null when no line that isn't a heading is named. */
        fun group(lines: List<String>, named: List<Int>): StepIngredients? {
            val heading = lines.map(IngredientHeading::isHeading)
            val used = named.filter { it in lines.indices && !heading[it] }.toSet()
            if (used.isEmpty()) return null
            // Each line's group heading, or null before the first one.
            var current: Int? = null
            val groupOf = lines.indices.map { i -> if (heading[i]) { current = i; null } else current }
            val members = lines.indices.filter { !heading[it] }.groupBy { groupOf[it] }
            // A heading whose every line is in the step moves with them.
            val whole = lines.indices.filter { h ->
                heading[h] && members[h].orEmpty().let { it.isNotEmpty() && it.all(used::contains) }
            }
            val loose = used.sorted().filter { line -> groupOf[line].let { it == null || it !in whole } }
            val now = loose + whole.flatMap { listOf(it) + members[it].orEmpty() }
            val placed = now.toSet()
            return StepIngredients(now, lines.indices.filter { it !in placed })
        }
    }
}
