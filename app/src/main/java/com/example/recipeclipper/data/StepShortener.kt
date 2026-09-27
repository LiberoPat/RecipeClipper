package com.example.recipeclipper.data

/**
 * Chef mode's seam onto the on-device model (#100). [MlKitStepShortener] is the real one (ML Kit
 * GenAI Rewriting on Gemini Nano); tests use `FakeStepShortener`. Nothing else in the app
 * imports the model's API.
 */
interface StepShortener {

    /** Whether this phone can write short steps, and for recipes in which languages. */
    suspend fun support(): ChefSupport

    /**
     * The model's short version of [step], written in [language] (the recipe's, e.g. "en"), or
     * null when it can't write one right now (busy, in the background, still downloading).
     * Unchecked: [ShortStepCheck][com.example.recipeclipper.data.model.ShortStepCheck] decides
     * whether it may show.
     */
    suspend fun shorten(step: String, language: String): String?
}

/** What Settings says about Chef mode on this phone. */
sealed interface ChefSupport {
    /** It can write short steps for recipes in [languages] (codes such as "en"). */
    data class Available(val languages: Set<String>) : ChefSupport {
        fun covers(language: String?) = language != null && language in languages
    }

    /** Apple Intelligence is turned off (iOS only). */
    data object NotEnabled : ChefSupport

    /** The model is still being set up (downloading). */
    data object NotReady : ChefSupport

    /** This phone can't run the on-device model. */
    data object Unsupported : ChefSupport
}
