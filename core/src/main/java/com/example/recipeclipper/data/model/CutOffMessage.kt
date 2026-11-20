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
 *
 * With meals (#392: each recipe carries the `"meal"` the prompt listed), the missing things are
 * day and meal pairs: for each day of the block and each meal seen in the answer, the pairs no
 * whole recipe is on. A meal the answer never reached is not known to the review, so it can't be named.
 */
object CutOffMessage {

    /** [plan] takes the missing days, written `YYYY-MM-DD` and comma-separated. */
    data class Words(
        val plan: (days: String) -> String,
        val planRest: String,
        val recipe: String,
        /** [planMeals]: the missing pairs, each `YYYY-MM-DD (Meal)`, comma-separated (#392). */
        val planMeals: (pairs: String) -> String = plan
    )

    /** The message for a cut-off week whose whole recipes are on [days] (null: no readable day). */
    fun forPlan(days: List<Long?>, words: Words): String =
        missingDays(days)?.let { words.plan(it.joinToString(", ") { day -> iso(day) }) } ?: words.planRest

    /**
     * The message for a cut-off week whose whole recipes are on [entries] (day, meal, as the answer
     * wrote them; null where it had none): with no meal read anywhere it is [forPlan]; otherwise
     * the missing day and meal pairs ([missingPairs]), or "the remaining days" when none can be told.
     */
    fun forPlanMeals(entries: List<Pair<Long?, String?>>, words: Words): String {
        if (entries.none { it.second != null }) return forPlan(entries.map { it.first }, words)
        return missingPairs(entries)
            ?.let { words.planMeals(it.joinToString(", ") { (day, meal) -> iso(day) + " (" + meal + ")" }) }
            ?: words.planRest
    }

    /**
     * The (day, meal) pairs of the week's block that no whole recipe is on: the block counted from
     * the earliest day read, the meals those the answer named (case and spaces ignored, written as
     * first seen), in the order first seen. Null when no day is readable or nothing is missing.
     */
    fun missingPairs(entries: List<Pair<Long?, String?>>): List<Pair<Long, String>>? {
        val first = entries.mapNotNull { it.first }.minOrNull() ?: return null
        val meals = mutableListOf<String>()
        for ((_, meal) in entries) {
            val name = meal?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (meals.none { it.equals(name, ignoreCase = true) }) meals += name
        }
        if (meals.isEmpty()) return null
        val have = mutableSetOf<Pair<Long, String>>()
        for ((day, meal) in entries) {
            if (day != null && meal != null) have += day to meal.trim().lowercase(java.util.Locale.ROOT)
        }
        val missing = (first until first + PlanDays.BLOCK_DAYS).flatMap { day ->
            meals.filter { (day to it.lowercase(java.util.Locale.ROOT)) !in have }.map { day to it }
        }
        return missing.takeIf { it.isNotEmpty() }
    }

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
