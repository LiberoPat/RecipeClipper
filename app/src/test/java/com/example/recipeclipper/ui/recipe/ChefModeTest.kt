package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultShortStepRepository
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeDecisionRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeShortStepDao
import com.example.recipeclipper.fake.FakeStepShortener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ChefMode] on its own (#169): the model's short steps and count brackets, with the model faked. */
class ChefModeTest {

    private val oven = "Preheat the oven to 350°F and butter a 9-inch round cake tin."
    private val bake = "Bake for 25 to 30 minutes, until the top is golden and springy."
    private val shortOven = "Preheat oven to 350°F; butter a 9-inch tin."
    private val apples = "3 large apples, peeled and sliced (about 3 cups)"
    private val model = FakeStepShortener(written = mutableMapOf(oven to shortOven))
    private val flagStore = FakeFeatureFlagStore(mapOf("chefMode" to true))
    private val flags = FeatureFlags(flagStore, FlagRegistry.definitions, isDebug = false)

    private fun recipe(language: String = "en", yield: String? = "4 servings") = Recipe(
        name = "Cake", image = null, ingredients = listOf(apples), instructions = listOf(oven, bake),
        prepTime = null, cookTime = null, totalTime = null, yield = yield, sourceUrl = "https://example.com/cake",
        id = 1, language = language
    )

    // The collectors never end, so they run on a scope of their own, on the test's scheduler.
    private val job = SupervisorJob()

    @After fun cancelCollectors() = job.cancel()

    private var shortStepChanges = 0
    private var decisionChanges = 0

    private fun TestScope.chef(
        kept: Recipe? = recipe(),
        decisions: FakeDecisionRepository? = null
    ) = ChefMode(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job),
        shortStepRepository = DefaultShortStepRepository(FakeShortStepDao(), model, Clock { 0 }) { _, e -> throw e },
        featureFlags = flags,
        decisionRepository = decisions,
        keptRecipe = { kept },
        onShortSteps = { shortStepChanges++ },
        onDecisions = { decisionChanges++ }
    )

    @Test fun `short steps come with both the flag and the setting on, and go with either`() = runTest {
        val chef = chef()
        chef.observeFlag()
        advanceUntilIdle()
        assertEquals("the flag alone writes nothing", emptyList<String>(), model.asked)

        chef.onSetting(true)
        advanceUntilIdle()
        assertEquals(listOf(shortOven, null), chef.shortSteps)
        assertTrue(shortStepChanges > 0)

        flags.set(Flag.CHEF_MODE, false)
        advanceUntilIdle()
        assertEquals(emptyList<String?>(), chef.shortSteps)
    }

    @Test fun `no kept recipe, or a language the model can't write, gets no short steps`() = runTest {
        for (kept in listOf(null, recipe(language = "ja"))) {
            val chef = chef(kept = kept)
            chef.observeFlag()
            chef.onSetting(true)
            advanceUntilIdle()
            assertEquals(emptyList<String?>(), chef.shortSteps)
        }
        assertEquals(emptyList<String>(), model.asked)
    }

    @Test fun `count brackets are asked only with their flag and a stepper, and the answer is passed on`() = runTest {
        val question = DecisionQuestion.countBracket(apples, "en")
        val decisions = FakeDecisionRepository(mapOf(question to "total"))
        val chef = chef(decisions = decisions)
        chef.observeDecisions()
        advanceUntilIdle()
        val content = RecipeRenderer.content(recipe(), RecipeRenderer.Settings())

        chef.askCountBrackets(content)
        advanceUntilIdle()
        assertEquals("off without aiCountBrackets", emptyList<DecisionQuestion>(), decisions.asked)
        assertEquals(0, decisionChanges)

        flags.set(Flag.AI_COUNT_BRACKETS, true)
        chef.askCountBrackets(RecipeRenderer.content(recipe(yield = null), RecipeRenderer.Settings()))
        advanceUntilIdle()
        assertEquals("no stepper, nothing to scale", emptyList<DecisionQuestion>(), decisions.asked)

        chef.askCountBrackets(content)
        advanceUntilIdle()
        assertEquals(listOf(question), decisions.asked)
        assertEquals(Decisions(mapOf(question to "total")), chef.decisions)
        assertEquals(1, decisionChanges)
    }
}
