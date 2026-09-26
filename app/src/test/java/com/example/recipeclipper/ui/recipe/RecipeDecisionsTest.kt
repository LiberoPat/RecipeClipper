package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeDecisionRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The reading view applies a count-bracket decision once it lands (#104), and asks only that. */
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

    private fun TestScope.viewModel(decisions: FakeDecisionRepository?) = RecipeViewModel(
        SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to 1L)),
        FakeRecipeRepository().apply { openResult = recipe }, FakeAppPreferences(), Clock { testScheduler.currentTime },
        FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler(), decisionRepository = decisions
    )

    private fun RecipeViewModel.ingredients() = (uiState.value.content as RecipeContent.Success).ingredients

    @Test fun `a total scales the bracket with the servings`() = runTest(mainDispatcherRule.dispatcher) {
        val decisions = FakeDecisionRepository(mapOf(question to "total"))
        val vm = viewModel(decisions)
        advanceUntilIdle()
        assertEquals(listOf(question), decisions.asked)
        vm.onServingsChange(8)
        assertEquals(listOf("6 large apples, peeled and sliced (about 6 cups)", "2 cup sugar"), vm.ingredients())
    }

    @Test fun `unsure, or no model, keeps today's line`() = runTest(mainDispatcherRule.dispatcher) {
        for (decisions in listOf(FakeDecisionRepository(mapOf(question to "unsure")), null)) {
            val vm = viewModel(decisions)
            advanceUntilIdle()
            vm.onServingsChange(8)
            assertEquals(listOf(apples, "2 cup sugar"), vm.ingredients())
        }
    }
}
