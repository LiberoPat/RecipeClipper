package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.Recipe

/**
 * A week of recipes shared in from an AI chat (#352): the answer to the "Plan my week" prompt
 * (#353), one JSON array of schema.org Recipes, each with a `"day"` (`YYYY-MM-DD`,
 * `AskAiPrompt.DAY_FIELD`). Shared text that holds one opens a review ([Plan]) instead of the
 * single-recipe editor: the recipes with their days, to keep and plan.
 *
 * A plan is every Recipe read, in order, from the whole JSON values in the text (each cut out by
 * its matching bracket, as [SharedText]'s JSON route does): one array, or several separate
 * values (a chat's code blocks, one recipe each), or a wrapper object around them (`mealPlan`,
 * `days`: [JsonLdRecipeParser.parseEach] with `deep`), a recipe's `date` standing for its `day`.
 * It needs at least [MIN_RECIPES], and either a day on at least one, or (no day anywhere) all of
 * them in one value, whole or cut off, and none naming a recipe it changed (`isBasedOn`, #354).
 * One recipe is never a plan: it opens the editor as before. A **cut-off answer** (the chat
 * stopped mid-recipe, so the outer bracket never closes) gives every whole recipe before the
 * unfinished one, and [Plan.cutOff] says so. At most [MAX_RECIPES] are kept. A recipe's `url`
 * and image are never used (an AI may make either up), and the prose around the JSON is dropped,
 * as for a single recipe.
 *
 * Pure: text in, data out. The iOS port (`MealPlanImport.swift`) follows it line for line.
 */
object MealPlanImport {

    /** Fewer than this is a single recipe, for the editor. */
    const val MIN_RECIPES = 2

    /** A month's worth: more is cut off, so a runaway answer can't flood the review. */
    const val MAX_RECIPES = 31

    /**
     * How many places in the text that open a JSON value are tried before giving up: room for
     * [MAX_RECIPES] separate blocks, the prose braces between them, and the pieces of a cut-off one.
     */
    private const val MAX_CANDIDATES = 100

    /**
     * One recipe of the plan. [day] is the epoch day ([PlanDays]) its `"day"` named, null when
     * it named none or something that isn't a real date ("Monday", "2026-02-30").
     */
    data class Item(val recipe: Recipe, val day: Long?)

    /** [cutOff]: the text ended inside an unfinished JSON value, so recipes after the last whole one are missing. */
    data class Plan(val items: List<Item>, val cutOff: Boolean = false)

    /** The plan in [text], or null when it holds none. */
    fun read(text: String): Plan? {
        val normalized = SharedText.normalize(text)
        if (normalized.length > SharedText.MAX_LENGTH || !normalized.contains("recipe", ignoreCase = true)) return null
        val gathered = mutableListOf<Pair<Recipe, String?>>()
        var oneValue = false // a single whole value held at least MIN_RECIPES recipes
        var basedOn = false // a value names the recipe it changed (#354): a changed recipe, not a week
        var cutAt = -1 // where the first unfinished JSON-looking value began
        var afterCut = 0 // recipes read from whole values that began inside it
        var tried = 0
        var i = 0
        while (i < normalized.length && tried < MAX_CANDIDATES && gathered.size < MAX_RECIPES) {
            if (normalized[i] != '{' && normalized[i] != '[') { i++; continue }
            tried++
            val end = SharedText.jsonEnd(normalized, i)
            if (end < 0) {
                // No closing bracket: a cut-off answer (or prose with a stray bracket). Step inside, so
                // each whole recipe in it is still found; the unfinished one is not a value at all.
                if (cutAt < 0 && SharedText.looksLikeJson(normalized, i)) cutAt = i
                i++
                continue
            }
            val value = normalized.substring(i, end)
            val found = JsonLdRecipeParser.parseEach(value, deep = true)
            if (found.isNotEmpty()) {
                gathered += found
                if (found.size >= MIN_RECIPES) oneValue = true
                if (JsonLdRecipeParser.isBasedOn(listOf(value)) != null) basedOn = true
                if (cutAt >= 0) afterCut += found.size
            }
            i = end
        }
        if (gathered.size < MIN_RECIPES) return null
        // With no day anywhere, only one value holding the recipes (an array, a wrapper, or one
        // that was cut off) is a plan, and never a changed recipe: separate blocks are not.
        if (gathered.none { it.second != null } && (basedOn || !(oneValue || afterCut >= MIN_RECIPES))) return null
        return Plan(gathered.take(MAX_RECIPES).map { (recipe, day) -> Item(recipe, parseDay(day)) }, cutAt >= 0)
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
