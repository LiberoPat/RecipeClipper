package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.Recipe

/**
 * A week of recipes shared in from an AI chat (#352): the answer to the "Plan my week" prompt
 * (#353), one JSON array of schema.org Recipes, each with a `"day"` (`YYYY-MM-DD`,
 * `AskAiPrompt.DAY_FIELD`). Shared text that holds one opens a review ([Plan]) instead of the
 * single-recipe editor: the recipes with their days, to keep and plan.
 *
 * A plan is the first JSON array or object in the text (candidates cut out by their matching
 * bracket, as [SharedText]'s JSON route does) that [JsonLdRecipeParser.parseEach] reads at
 * least [MIN_RECIPES] recipes from, at least one of them with a day. One recipe, or recipes
 * with no day at all, are not a plan: they open the editor as before. At most [MAX_RECIPES] are
 * kept. A recipe's `url` and image are never used (an AI may make either up), and the prose
 * around the JSON is dropped, as for a single recipe.
 *
 * Pure: text in, data out. The iOS port (`MealPlanImport.swift`) follows it line for line.
 */
object MealPlanImport {

    /** Fewer than this is a single recipe, for the editor. */
    const val MIN_RECIPES = 2

    /** A month's worth: more is cut off, so a runaway answer can't flood the review. */
    const val MAX_RECIPES = 31

    /** How many places in the text that open a JSON value are tried before giving up. */
    private const val MAX_CANDIDATES = 20

    /**
     * One recipe of the plan. [day] is the epoch day ([PlanDays]) its `"day"` named, null when
     * it named none or something that isn't a real date ("Monday", "2026-02-30").
     */
    data class Item(val recipe: Recipe, val day: Long?)

    data class Plan(val items: List<Item>)

    /** The plan in [text], or null when it holds none. */
    fun read(text: String): Plan? {
        val normalized = SharedText.normalize(text)
        if (normalized.length > SharedText.MAX_LENGTH || !normalized.contains("recipe", ignoreCase = true)) return null
        var tried = 0
        var i = 0
        while (i < normalized.length && tried < MAX_CANDIDATES) {
            if (normalized[i] != '{' && normalized[i] != '[') { i++; continue }
            tried++
            val end = SharedText.jsonEnd(normalized, i)
            if (end < 0) { i++; continue }
            val found = JsonLdRecipeParser.parseEach(normalized.substring(i, end))
            if (found.size >= MIN_RECIPES && found.any { it.second != null }) {
                return Plan(found.take(MAX_RECIPES).map { (recipe, day) -> Item(recipe, parseDay(day)) })
            }
            i = end
        }
        return null
    }

    private val DAY = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ].*)?$""")

    /** `YYYY-MM-DD` (a time after it is ignored) as an epoch day; null for anything else, and for a date the calendar doesn't have. */
    fun parseDay(raw: String?): Long? {
        val m = DAY.matchEntire(raw?.trim() ?: return null) ?: return null
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val epoch = PlanDays.epochDay(year, month, day)
        return epoch.takeIf { PlanDays.civil(epoch) == Triple(year, month, day) }
    }
}
