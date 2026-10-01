package com.example.recipeclipper.data.model

import org.json.JSONObject

/**
 * The sample recipe (#151), added quietly on a new user's first launch (#190), from
 * `shared/sample/recipe.json` (one copy, both apps). It is saved like a typed-in recipe (MANUAL,
 * never fetched, no "Update from source") under the fixed link [SOURCE_URL], which is how the
 * library leaves it out of every count (#107): it never takes one of the free tier's places.
 * Deleting it works like any other recipe.
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
