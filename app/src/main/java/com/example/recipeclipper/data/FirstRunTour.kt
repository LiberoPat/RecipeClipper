package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.TourPreferences
import com.example.recipeclipper.data.model.SampleRecipe
import com.example.recipeclipper.data.model.Tip
import com.example.recipeclipper.data.model.WelcomeState
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first-run tour's rules (#151), platform-free so they are tested over fakes; iOS's
 * `FirstRunTour` is the same.
 *
 * - The welcome shows once, on a plain launch (the launcher icon, not a shared link or a
 *   notification): capture stays frictionless, so a first launch from a share shows the recipe
 *   and the welcome waits for the next plain launch.
 * - Someone who already has recipes when the tour first runs (an older version's user, or a
 *   restored backup) never gets the welcome, nor the recipe and cook mode tips.
 * - The sample recipe is added once, when the welcome first shows. Deleted, it stays deleted;
 *   "Try it" on a later showing of the tour adds it again only if it is gone.
 */
@Singleton
class FirstRunTour @Inject constructor(
    private val preferences: TourPreferences,
    private val recipes: RecipeRepository
) {
    /**
     * Decides, once per app start, whether the welcome shows now. [plain] is false for a
     * launch that opens something (a shared link, a notification). The library is counted
     * without the sample, so an interrupted welcome still shows again.
     */
    suspend fun onLaunch(plain: Boolean): Boolean {
        when (preferences.welcome) {
            WelcomeState.SEEN -> return false
            WelcomeState.PENDING -> return plain
            WelcomeState.UNDECIDED -> Unit
        }
        if (recipes.observeCount().first() > 0) {
            preferences.welcome = WelcomeState.SEEN
            preferences.setTipSeen(Tip.RECIPE, true)
            preferences.setTipSeen(Tip.COOK_MODE, true)
            return false
        }
        preferences.welcome = WelcomeState.PENDING
        return plain
    }

    /** The welcome is showing: the first time ever, the sample recipe is added, in [language]. */
    suspend fun addSampleOnce(language: String?) {
        if (preferences.sampleAdded) return
        if (recipes.sampleId() == null) recipes.addSample(SampleRecipe.forLanguage(language)) ?: return
        preferences.sampleAdded = true
    }

    /** "Try it with a sample recipe": the sample's id, added again only if it is gone. */
    suspend fun sampleToOpen(language: String?): Long? =
        recipes.sampleId() ?: recipes.addSample(SampleRecipe.forLanguage(language))?.also {
            preferences.sampleAdded = true
        }

    /** Skip, Start, Try it or Back: the welcome is done. */
    fun finishWelcome() {
        preferences.welcome = WelcomeState.SEEN
    }

    /** "Show the tour again" (Settings): every tip shows again, once more. */
    fun replay() {
        Tip.entries.forEach { preferences.setTipSeen(it, false) }
    }
}
