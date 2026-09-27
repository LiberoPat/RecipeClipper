package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.UnitSystem
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
 * applied once it lands only with `aiCountBrackets` on too. And junk after an ingredient (#174):
 * asked as Groceries asks it, hidden once decided.
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

    // Junk after an ingredient (#174): the questions Groceries asks, and the model's answers.
    private val junkLines = listOf("2 eggs (dfsafs -", "2 onions dfsafs", "2 eggs, beaten", "For the sauce:")
    private val eggsJunk = DecisionQuestion.trailingText("(dfsafs -", "en")
    private val onionsName = DecisionQuestion.ingredientName("2 onions dfsafs", "en")
    private val onionsJunk = DecisionQuestion.trailingText("dfsafs", "en")
    private val beatenNote = DecisionQuestion.trailingText(", beaten", "en")
    private val junkAnswers = mapOf(eggsJunk to "junk", onionsName to "onions", onionsJunk to "junk", beatenNote to "note")

    private fun TestScope.junkViewModel(decisions: FakeDecisionRepository, repository: FakeRecipeRepository = junkRepository()) =
        RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to 1L)), repository, FakeAppPreferences(),
            Clock { testScheduler.currentTime }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler(),
            featureFlags = flags(Flag.AI_DECISIONS), decisionRepository = decisions
        )

    private fun junkRepository() = FakeRecipeRepository().apply { openResult = recipe.copy(ingredients = junkLines) }

    @Test fun `the recipe asks about junk once per visit and hides it once decided`() = runTest(mainDispatcherRule.dispatcher) {
        val decisions = FakeDecisionRepository(junkAnswers)
        val vm = junkViewModel(decisions)
        advanceUntilIdle()

        // The name lands first; its trailing text is asked then. The heading is never asked about.
        assertEquals(listOf(onionsName, eggsJunk, beatenNote, onionsJunk), decisions.asked)
        assertEquals(listOf("2 eggs", "2 onions", "2 eggs, beaten", "For the sauce:"), vm.ingredients())

        vm.onServingsChange(8)
        vm.onUnitSystemChange(UnitSystem.METRIC)
        advanceUntilIdle()
        assertEquals("nothing is asked twice in a visit", 4, decisions.asked.size)
        assertEquals(listOf("4 eggs", "4 onions", "4 eggs, beaten", "For the sauce:"), vm.ingredients())
    }

    @Test fun `an answer already cached, from Groceries or an earlier visit, is not asked again`() =
        runTest(mainDispatcherRule.dispatcher) {
            val decisions = FakeDecisionRepository(junkAnswers)
            decisions.decide(listOf(eggsJunk, onionsName, onionsJunk, beatenNote))
            decisions.asked.clear()

            val vm = junkViewModel(decisions)
            advanceUntilIdle()
            assertEquals(emptyList<DecisionQuestion>(), decisions.asked)
            assertEquals(listOf("2 eggs", "2 onions", "2 eggs, beaten", "For the sauce:"), vm.ingredients())
        }

    @Test fun `the lines show as written until an answer lands, then render again, ticks in place`() =
        runTest(mainDispatcherRule.dispatcher) {
            val decisions = FakeDecisionRepository() // the model can't answer yet
            val repository = junkRepository()
            val vm = junkViewModel(decisions, repository)
            advanceUntilIdle()
            vm.onIngredientChecked(1, true)
            assertEquals(junkLines, vm.ingredients())

            decisions.answer(eggsJunk to "junk", onionsName to "onions", onionsJunk to "junk")
            advanceUntilIdle()
            assertEquals(listOf("2 eggs", "2 onions", "2 eggs, beaten", "For the sauce:"), vm.ingredients())
            assertEquals(setOf(1), vm.uiState.value.checkedIngredients)
            assertEquals("the stored lines are never rewritten", junkLines, (vm.uiState.value.content as RecipeContent.Success).recipe.ingredients)
            assertTrue(vm.shareText()!!.contains("INGREDIENTS\n2 eggs\n2 onions\n2 eggs, beaten\nFor the sauce:\n"))

            // The pantry's use-up sheet (#147) gets the ticked line as shown.
            vm.onCookStart()
            vm.onStepDone()
            assertEquals(FinishedCook("en", listOf("2 onions")), vm.uiState.value.cookFinished)
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
