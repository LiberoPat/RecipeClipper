package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.TourPreferences
import com.example.recipeclipper.data.model.SampleRecipe
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first-run tour's sample recipe (#151, #190), platform-free so it is tested over fakes;
 * iOS's `FirstRunTour` is the same. There is no welcome any more: the tour is the tooltips
 * (`TooltipsViewModel`), and the sample is added quietly.
 *
 * - The sample is added once, at a new user's first launch (an empty library), whatever the
 *   launch opens: a shared link still opens on its recipe, with the sample under it in Recipes.
 * - Someone who already has recipes then (an older version's user, or a restored backup) never
 *   gets it. Either way it's decided once: deleted, it stays deleted.
 */
@Singleton
class FirstRunTour @Inject constructor(
    private val preferences: TourPreferences,
    private val recipes: RecipeRepository
) {
    /** Every launch, before any intent's route opens. The sample goes in in [language]. */
    suspend fun onLaunch(language: String?) {
        recipes.formatSampleTimes() // #179: a sample saved with raw ISO times; a no-op after
        if (preferences.sampleAdded) return
        // The library is counted without the sample, so one added before an interruption stands.
        if (recipes.observeCount().first() == 0 && recipes.sampleId() == null) {
            recipes.addSample(SampleRecipe.forLanguage(language)) ?: return
        }
        preferences.sampleAdded = true
    }
}
