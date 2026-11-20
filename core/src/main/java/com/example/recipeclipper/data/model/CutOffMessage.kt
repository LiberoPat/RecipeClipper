package com.example.recipeclipper.data.model

/**
 * The message that asks an AI chat for the rest of an answer that was cut off (#352): copied by
 * the cook from the cut-off screen or the meal-plan review and pasted into the chat. Pure: the
 * sentences are the UI language's ([Words], from string resources, as `AskAiPrompt.Words`), the
 * days are worked out here. The iOS port (`CutOffMessage.swift`) follows it line for line.
 *
 * A cut-off week names the days still missing: the "Plan my week" prompt (#353) asks for
 * [PlanDays.BLOCK_DAYS] days from a start day, which the review only knows by the days the whole
 * recipes carry. The block is counted from the earliest day read; the days of it with no recipe
 * are the missing ones. With no readable day, or nothing missing in the block, it asks for "the
 * remaining days" instead.
 */
object CutOffMessage {

    /** [plan] takes the missing days, written `YYYY-MM-DD` and comma-separated. */
    data class Words(val plan: (days: String) -> String, val planRest: String, val recipe: String)

    /** The message for a cut-off week whose whole recipes are on [days] (null: no readable day). */
    fun forPlan(days: List<Long?>, words: Words): String =
        missingDays(days)?.let { words.plan(it.joinToString(", ") { day -> iso(day) }) } ?: words.planRest

    /** The message for a cut-off single recipe, or a week cut off before its first recipe ended. */
    fun forRecipe(words: Words): String = words.recipe

    /** The days of the week's block that no whole recipe is on, or null when none can be told. */
    fun missingDays(days: List<Long?>): List<Long>? {
        val known = days.filterNotNull()
        if (known.isEmpty()) return null
        val first = known.min()
        val missing = (first until first + PlanDays.BLOCK_DAYS).filter { it !in known }
        return missing.takeIf { it.isNotEmpty() }
    }

    /** [day] (an epoch day) as `YYYY-MM-DD`. */
    fun iso(day: Long): String {
        val (year, month, dayOfMonth) = PlanDays.civil(day)
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", year, month, dayOfMonth)
    }
}
