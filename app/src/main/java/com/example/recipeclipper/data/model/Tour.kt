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
        return Recipe(
            name = json.getString("name"),
            image = null,
            ingredients = SharedTables.strings(json.getJSONArray("ingredients")),
            instructions = SharedTables.strings(json.getJSONArray("instructions")),
            prepTime = json.optString("prepTime").ifEmpty { null },
            cookTime = json.optString("cookTime").ifEmpty { null },
            totalTime = json.optString("totalTime").ifEmpty { null },
            yield = json.optString("yield").ifEmpty { null },
            sourceUrl = SOURCE_URL,
            language = chosen,
            origin = ContentOrigin.MANUAL
        )
    }
}
