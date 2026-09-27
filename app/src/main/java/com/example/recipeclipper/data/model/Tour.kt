package com.example.recipeclipper.data.model

import org.json.JSONObject

/**
 * Where the first-run welcome stands (#151), stored by name under `tour_welcome`. An unknown
 * name reads as [UNDECIDED], which the next launch decides from the library.
 */
enum class WelcomeState {
    /** Never decided: a fresh install, or an app from before the tour. */
    UNDECIDED,

    /** A new user who hasn't finished the welcome: it shows at the next plain launch. */
    PENDING,

    /** Finished, skipped, or never needed (someone who already had recipes). */
    SEEN;

    companion object {
        fun fromStoredName(name: String?): WelcomeState = entries.firstOrNull { it.name == name } ?: UNDECIDED
    }
}

/**
 * The one-time tips (#151): one small callout the first time a screen is reached, dismissed by
 * a tap. [key] is its `unit_preferences` / UserDefaults key, true once dismissed; the same on iOS.
 * [mealPlan] tips belong to the tabs behind the `mealPlan` flag and hide while it is off.
 */
enum class Tip(val key: String, val mealPlan: Boolean = false) {
    /** The first recipe opened: the Serves and units row, and the bookmark. */
    RECIPE("tour_tip_recipe"),
    COOK_MODE("tour_tip_cook_mode"),
    WEEK("tour_tip_week", mealPlan = true),
    GROCERIES("tour_tip_groceries", mealPlan = true),
    PANTRY("tour_tip_pantry", mealPlan = true)
}

/**
 * The tour's sample recipe (#151), from `shared/sample/recipe.json` (one copy, both apps). It is
 * saved like a typed-in recipe (MANUAL, never fetched, no "Update from source") under the fixed
 * link [SOURCE_URL], which is how the library leaves it out of every count (#107): it never
 * takes one of the free tier's places. Deleting it works like any other recipe.
 */
object SampleRecipe {
    /** A `manual:` link, so everything that treats a typed-in recipe's link applies. */
    const val SOURCE_URL = ManualRecipe.SCHEME + "sample"

    const val RESOURCE = "/sample/recipe.json"

    fun isSample(sourceUrl: String): Boolean = sourceUrl == SOURCE_URL

    private val entries: JSONObject by lazy {
        val stream = SampleRecipe::class.java.getResourceAsStream(RESOURCE) ?: error("$RESOURCE is missing")
        JSONObject(stream.bufferedReader().use { it.readText() }).getJSONObject("recipes")
    }

    /** The languages the sample is written in. */
    val languages: List<String> get() = entries.keys().asSequence().toList()

    /** The sample in [language] (a tag like "de" or "pt-BR"), or in English if it isn't written in it. */
    fun forLanguage(language: String?): Recipe {
        val code = language?.substringBefore('-')?.substringBefore('_')?.lowercase()
        val chosen = if (code != null && entries.has(code)) code else "en"
        val json = entries.getJSONObject(chosen)
        fun time(key: String) = formatTime(json.optString(key).ifEmpty { null }, chosen)
        return Recipe(
            name = json.getString("name"),
            image = null,
            ingredients = SharedTables.strings(json.getJSONArray("ingredients")),
            instructions = SharedTables.strings(json.getJSONArray("instructions")),
            prepTime = time("prepTime"),
            cookTime = time("cookTime"),
            totalTime = time("totalTime"),
            yield = json.optString("yield").ifEmpty { null },
            sourceUrl = SOURCE_URL,
            language = chosen,
            origin = ContentOrigin.MANUAL
        )
    }

    /**
     * A time as the sample shows it (#179): the file's ISO duration ("PT10M") formatted the way
     * a parsed recipe's is, in [language]'s words ("10m", "10min"). Formatting one already
     * formatted changes nothing, which is what lets a sample saved before #179 be fixed at
     * every launch (`RecipeRepository.formatSampleTimes`).
     */
    fun formatTime(time: String?, language: String?): String? =
        time?.let { Durations.format(it, LanguageWords.forTag(language)) }
}
