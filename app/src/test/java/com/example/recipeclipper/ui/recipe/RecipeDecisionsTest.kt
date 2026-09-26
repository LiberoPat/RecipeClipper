package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeDecisionRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The reading view's count-bracket decision (#104): off under `aiDecisions` alone (#127), and
 * applied once it lands only with `aiCountBrackets` on too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDecisionsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val apples = "3 large apples, peeled and sliced (about 3 cups)"
    private val recipe = Recipe(
        name = "Apple Crumble", image = null, ingredients = listOf(apples, "1 cup sugar"),
        instructions = listOf("Bake."), prepTime = null, cookTime = null, totalTime = null,
        yield = "4 servings", sourceUrl = "https://example.com/crumble", id = 1L, language = "en"
    )
    private val question = DecisionQuestion.countBracket(apples, "en")

    private fun flags(vararg on: Flag) =
        FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true).apply { on.forEach { set(it, true) } }

    private fun TestScope.viewModel(decisions: FakeDecisionRepository?, flags: FeatureFlags? = null) = RecipeViewModel(
        SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to 1L)),
        FakeRecipeRepository().apply { openResult = recipe }, FakeAppPreferences(), Clock { testScheduler.currentTime },
        FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler(), featureFlags = flags, decisionRepository = decisions
    )

    private fun RecipeViewModel.ingredients() = (uiState.value.content as RecipeContent.Success).ingredients

    @Test fun `a total scales the bracket with the servings, with count brackets on`() = runTest(mainDispatcherRule.dispatcher) {
        val decisions = FakeDecisionRepository(mapOf(question to "total"))
        val vm = viewModel(decisions, flags(Flag.AI_DECISIONS, Flag.AI_COUNT_BRACKETS))
        advanceUntilIdle()
        assertEquals(listOf(question), decisions.asked)
        vm.onServingsChange(8)
        assertEquals(listOf("6 large apples, peeled and sliced (about 6 cups)", "2 cup sugar"), vm.ingredients())
    }

    @Test fun `the reading view never asks about grocery text and keeps the line as written`() =
        runTest(mainDispatcherRule.dispatcher) {
            val lines = listOf("2 eggs (dfsafs -", "2 onions dfsafs")
            val decisions = FakeDecisionRepository(
                mapOf(
                    DecisionQuestion.trailingText("(dfsafs -", "en") to "junk",
                    DecisionQuestion.ingredientName("2 onions dfsafs", "en") to "onions",
                    DecisionQuestion.trailingText("dfsafs", "en") to "junk"
                )
            )
            val vm = RecipeViewModel(
                SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to 1L)),
                FakeRecipeRepository().apply { openResult = recipe.copy(ingredients = lines) },
                FakeAppPreferences(), Clock { testScheduler.currentTime },
                FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler(),
                featureFlags = flags(Flag.AI_DECISIONS, Flag.AI_COUNT_BRACKETS), decisionRepository = decisions
            )
            advanceUntilIdle()
            assertEquals(emptyList<DecisionQuestion>(), decisions.asked)
            assertEquals(lines, vm.ingredients())
        }

    @Test fun `unsure, or no model, keeps today's line`() = runTest(mainDispatcherRule.dispatcher) {
        for (decisions in listOf(FakeDecisionRepository(mapOf(question to "unsure")), null)) {
            val vm = viewModel(decisions, flags(Flag.AI_DECISIONS, Flag.AI_COUNT_BRACKETS))
            advanceUntilIdle()
            vm.onServingsChange(8)
            assertEquals(listOf(apples, "2 cup sugar"), vm.ingredients())
        }
    }

    @Test fun `aiDecisions alone asks nothing and scales a count bracket as with the flag off`() =
        runTest(mainDispatcherRule.dispatcher) {
            val off = viewModel(null)
            advanceUntilIdle()
            off.onServingsChange(8)

            val decisions = FakeDecisionRepository(mapOf(question to "each"))
            val vm = viewModel(decisions, flags(Flag.AI_DECISIONS))
            advanceUntilIdle()
            vm.onServingsChange(8)
            advanceUntilIdle()
            assertTrue(decisions.asked.isEmpty())
            assertEquals(off.ingredients(), vm.ingredients())
            assertEquals(listOf(apples, "2 cup sugar"), vm.ingredients())
        }
}
