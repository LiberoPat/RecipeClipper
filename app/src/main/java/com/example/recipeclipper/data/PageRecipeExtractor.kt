package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.PageSelection

/**
 * The on-device model reading a page with no recipe data (#103), beside [StepShortener]:
 * [MlKitPageRecipeExtractor] is the real one (ML Kit GenAI's Prompt API on Gemini Nano); tests
 * use `FakePageRecipeExtractor`. Nothing else in the app imports the model's API, and the
 * parsers never call it: the repository does, only after they found nothing.
 */
interface PageRecipeExtractor {

    /**
     * How much page text, in characters, it can read now for a recipe in [language] ("en"), or
     * null when it can't (an unsupported phone or language, or a model not downloaded yet).
     */
    suspend fun windowChars(language: String): Int?

    /**
     * What the model picked out of [text] as the recipe, all but its steps (asked apart, so a
     * long recipe's reply fits: #128), or null when it found none or couldn't answer. Unchecked:
     * [PageRecipeCheck][com.example.recipeclipper.data.model.PageRecipeCheck] keeps only what is
     * on the page.
     */
    suspend fun extract(text: String, language: String): PageSelection?

    /** The steps of the recipe called [name] that the model picked out of [text], or null when it couldn't answer. */
    suspend fun extractSteps(text: String, language: String, name: String): List<String>?

    companion object {
        /** Reads nothing: the default for tests that aren't about extraction. */
        val None: PageRecipeExtractor = object : PageRecipeExtractor {
            override suspend fun windowChars(language: String): Int? = null
            override suspend fun extract(text: String, language: String): PageSelection? = null
            override suspend fun extractSteps(text: String, language: String, name: String): List<String>? = null
        }
    }
}
