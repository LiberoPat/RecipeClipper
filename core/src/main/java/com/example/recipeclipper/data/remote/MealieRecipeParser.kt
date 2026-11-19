package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.UrlCleaner
import com.example.recipeclipper.data.model.WebImageUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * One recipe of a Mealie export (#391), as far as the app reads it. Every text is Mealie's, trimmed;
 * nothing is converted or guessed. [slug] is Mealie's own name for the recipe (the identity of a recipe
 * in an export, which a re-import uses) and [id] its database id; [sourceUrl] is `orgURL` when it is a
 * real web link (cleaned), else null. [ingredients] are lines of text, with a heading line ending in a
 * colon before the first line of each titled group; [instructions] are the steps, a step's title in
 * front of its text. [categories] are the recipe's categories and tags together, by name; the cook may
 * have them become lists (#387). [photoFile] names the recipe's stored picture, set by the archive
 * reader once the photo store took it (#386), else null.
 */
data class MealieRecipe(
    val slug: String,
    val id: String?,
    val name: String,
    val ingredients: List<String>,
    val instructions: List<String>,
    val notes: String?,
    val servings: String?,
    val prepTime: String?,
    val cookTime: String?,
    val totalTime: String?,
    val sourceUrl: String?,
    val categories: List<String>,
    val photoFile: String? = null
)

/** What one JSON file of a Mealie export said. */
sealed interface MealieEntry {
    data class Recipe(val recipe: MealieRecipe) : MealieEntry

    /** Shaped like a Mealie recipe, with no name, or neither ingredients nor steps: skipped and counted. */
    data object NotARecipe : MealieEntry

    /**
     * A JSON object that isn't a Mealie recipe: no slug, no `referenceId` on its ingredients, or a
     * schema.org file (`@context` / `@type`, which a recipe page's link import already reads).
     */
    data object NotMealie : MealieEntry

    /** Not JSON, not an object, or too deeply nested to read. */
    data object Unreadable : MealieEntry
}

/**
 * Reads the JSON of one Mealie recipe (#391): the bulk export's `recipes/<slug>/<slug>.json`, the
 * per-recipe download's `.json`, or a bare file. Mealie writes its keys in snake_case or camelCase
 * depending on where the file came from, so both are read. A missing, null or unknown field is simply
 * absent. A recipe needs a name plus ingredients or steps, the parsers' rule. Pure: text in, data out.
 *
 * - **Detected, not assumed:** a non-blank `slug` and `recipeIngredient` entries that are objects with
 *   a `referenceId`, and no `@context` / `@type`. Anything else is [MealieEntry.NotMealie].
 * - **Ingredients are never rebuilt from `quantity` and `unit`.** Before Mealie 3.1 a line that was
 *   never parsed was stored with `quantity` 1 and its text in `note`, so rebuilding would show a
 *   confident wrong number. The line is `originalText`, else `display`, else the food's name and the
 *   note, as written. An entry with a `title` puts a heading line ("For the sauce:") before it.
 * - **Steps** are their `text`; a `title` goes in front of it ("Sauce: Stir..."), and a step that is only
 *   a title goes in front of the next step's text (the app has no step headings).
 * - **Yield:** `recipeYield` as text, else `recipeServings` as a number (whole when it is whole).
 * - **Times:** `prepTime`, `performTime` (Mealie's cook time, also read as `cookTime`) and `totalTime`
 *   as text, for the time rules to read; a `…Seconds` number is turned into an ISO duration.
 * - **Notes:** each `{title, text}` becomes a paragraph of the recipe's note.
 * - **Not kept:** `description` (the app has no place for it), ratings, nutrition, tools, comments, assets.
 */
object MealieRecipeParser {

    fun parse(text: String): MealieEntry {
        val root = try {
            JSONTokener(text.removePrefix("﻿")).nextValue()
        } catch (e: Exception) {
            return MealieEntry.Unreadable
        } catch (e: StackOverflowError) {
            // A hostile entry nested thousands deep: org.json recurses.
            return MealieEntry.Unreadable
        }
        val json = root as? JSONObject ?: return MealieEntry.Unreadable
        if (json.has("@context") || json.has("@type")) return MealieEntry.NotMealie

        val slug = json.text("slug") ?: return MealieEntry.NotMealie
        val rawIngredients = json.pick("recipeIngredient", "recipe_ingredient") as? JSONArray ?: return MealieEntry.NotMealie
        val entries = (0 until rawIngredients.length()).map { rawIngredients.opt(it) }
        if (entries.any { it !is JSONObject }) return MealieEntry.NotMealie
        val ingredientObjects = entries.map { it as JSONObject }
        val steps = json.pick("recipeInstructions", "recipe_instructions") as? JSONArray
        val hasReference = ingredientObjects.any { it.has("referenceId") || it.has("reference_id") }
        // An empty ingredient list can't show a referenceId: a recipe of steps alone is told by having steps' key.
        if (if (ingredientObjects.isEmpty()) steps == null else !hasReference) return MealieEntry.NotMealie

        val name = json.text("name").orEmpty()
        val ingredients = ingredientLines(ingredientObjects)
        val instructions = stepLines(steps)
        if (name.isEmpty() || (ingredients.none { !it.endsWith(":") } && instructions.isEmpty())) return MealieEntry.NotARecipe

        val servings = json.text("recipeYield", "recipe_yield") ?: json.number("recipeServings", "recipe_servings")
        return MealieEntry.Recipe(
            MealieRecipe(
                slug = slug,
                id = json.text("id"),
                name = name,
                ingredients = ingredients,
                instructions = instructions,
                notes = notes(json.pick("notes")),
                servings = servings,
                prepTime = time(json, "prep"),
                cookTime = time(json, "perform", "cook"),
                totalTime = time(json, "total"),
                sourceUrl = json.text("orgURL", "orgUrl", "org_url")?.let(::webLink),
                categories = names(json.pick("recipeCategory", "recipe_category")) + names(json.pick("tags"))
            )
        )
    }

    /** A real web link, cleaned as every saved link is; anything else ("", "n/a", a name) is none. */
    private fun webLink(text: String): String? = WebImageUrl.of(text)?.let { UrlCleaner.clean(it) }

    private fun ingredientLines(items: List<JSONObject>): List<String> {
        val lines = ArrayList<String>()
        for (item in items) {
            item.text("title")?.let { title -> lines += if (title.endsWith(":")) title else "$title:" }
            val line = item.text("originalText", "original_text") ?: derivedLine(item)
            if (line != null) lines += oneLine(line)
        }
        return lines
    }

    /**
     * The line when `originalText` is missing. A line with no food and no unit is Mealie's unparsed
     * line (before 3.1 stored with `quantity` 1): its `note` is the whole text, or it has none, and its
     * `display` may be "1 " in front of it, so the note is the line. Otherwise Mealie's own `display`, else the food's name and
     * the note. Never rebuilt from `quantity` and `unit`.
     */
    private fun derivedLine(item: JSONObject): String? {
        val food = when (val value = item.opt("food")) {
            is JSONObject -> value.text("name")
            is String -> value.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
        val unit = when (val value = item.opt("unit")) {
            is JSONObject -> value.text("name") ?: value.text("abbreviation")
            is String -> value.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
        val note = item.text("note")
        if (food == null && unit == null) return note
        return item.text("display") ?: listOfNotNull(food, note).joinToString(", ").takeIf { it.isNotEmpty() }
    }

    private fun stepLines(steps: JSONArray?): List<String> {
        val lines = ArrayList<String>()
        var pending: String? = null
        for (i in 0 until (steps?.length() ?: 0)) {
            val step = steps?.opt(i)
            val title: String?
            val body: String?
            when (step) {
                is JSONObject -> {
                    title = step.text("title")
                    body = step.text("text")
                }
                is String -> {
                    title = null
                    body = step.trim().takeIf { it.isNotEmpty() }
                }
                else -> continue
            }
            if (body == null) {
                // Only a title: the heading of what follows.
                if (title != null) pending = listOfNotNull(pending, title).joinToString(" ")
                continue
            }
            val head = listOfNotNull(pending, title).joinToString(" ").takeIf { it.isNotEmpty() }
            pending = null
            lines += if (head == null) body else if (head.endsWith(":")) "$head $body" else "$head: $body"
        }
        return lines
    }

    private fun notes(value: Any?): String? {
        val array = value as? JSONArray ?: return null
        val paragraphs = (0 until array.length()).mapNotNull { i ->
            when (val note = array.opt(i)) {
                is JSONObject -> listOfNotNull(note.text("title"), note.text("text")).joinToString("\n")
                is String -> note.trim()
                else -> null
            }.takeIf { !it.isNullOrEmpty() }
        }
        return paragraphs.joinToString("\n\n").takeIf { it.isNotEmpty() }
    }

    /** Names of categories or tags: `{name}` objects, or bare strings. */
    private fun names(value: Any?): List<String> {
        val array = value as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            when (val item = array.opt(i)) {
                is JSONObject -> item.text("name")
                is String -> item.trim().takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }

    /** A time as text in any of Mealie's spellings, else a `…Seconds` number as an ISO duration. */
    private fun time(json: JSONObject, vararg stems: String): String? {
        for (stem in stems) {
            json.text("${stem}Time", "${stem}_time")?.let { return it }
        }
        for (stem in stems) {
            val seconds = when (val value = json.pick("${stem}TimeSeconds", "${stem}_time_seconds")) {
                is Number -> value.toDouble()
                else -> null
            }
            if (seconds != null && seconds > 0 && seconds < 1e9) return "PT${seconds.toLong()}S"
        }
        return null
    }

    /** A line is one line: Mealie's own line breaks inside a field become spaces. */
    private fun oneLine(text: String): String = text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** The first of [keys] the object has with a value that isn't JSON null. */
    private fun JSONObject.pick(vararg keys: String): Any? {
        for (key in keys) {
            if (has(key) && !isNull(key)) return opt(key)
        }
        return null
    }

    /**
     * A text field, trimmed; null if absent, JSON null, blank or not text. `optString` would turn a
     * JSON null into "null" on Android, so the value is read as it is. A number keeps its digits.
     */
    private fun JSONObject.text(vararg keys: String): String? {
        for (key in keys) {
            val value = when (val v = pick(key)) {
                is String -> v
                is Number -> v.toDouble().let { d ->
                    if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else v.toString()
                }
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
            if (value != null) return value
        }
        return null
    }

    /** A positive number as text: whole when it is whole ("4"), else as written ("2.5"); null for 0 or less. */
    private fun JSONObject.number(vararg keys: String): String? {
        val value = pick(*keys) as? Number ?: return null
        val d = value.toDouble()
        if (!(d > 0) || d >= 1e9) return null
        return if (d == Math.floor(d)) d.toLong().toString() else value.toString()
    }
}
