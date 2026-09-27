package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The end of cooking (#147): finishing cook mode's last step hands the ticked lines, as shown,
 * to the pantry's use-up sheet, once. Leaving cook mode any other way doesn't.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeCookFinishedTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun TestScope.open(checked: Set<Int>, servings: Int? = null): RecipeViewModel {
        val repository = FakeRecipeRepository()
        repository.openResult = Recipe(
            name = "Omelette",
            image = null,
            ingredients = listOf("For the eggs:", "3 eggs", "1 cup milk", "salt"),
            instructions = listOf("Whisk.", "Cook."),
            prepTime = null,
            cookTime = null,
            totalTime = null,
            yield = "2 servings",
            sourceUrl = "https://example.com/omelette",
            id = 1,
            checkedIngredients = checked,
            servingsTarget = servings
        )
        return RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to 1L)), repository, FakeAppPreferences(),
            Clock { testScheduler.currentTime }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler()
        )
    }

    private fun RecipeViewModel.cookToTheEnd() {
        onCookStart()
        onStepDone()
        onStepDone()
    }

    @Test fun `finishing hands over the ticked lines as shown, scaled, in order`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = open(checked = setOf(2, 1), servings = 4)
        advanceUntilIdle()
        vm.cookToTheEnd()

        assertEquals(FinishedCook("en", listOf("6 eggs", "2 cup milk")), vm.uiState.value.cookFinished)
        vm.onCookFinishedHandled()
        assertNull(vm.uiState.value.cookFinished)
    }

    @Test fun `nothing ticked, nothing handed over`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = open(checked = emptySet())
        advanceUntilIdle()
        vm.cookToTheEnd()
        assertNull(vm.uiState.value.cookFinished)
    }

    @Test fun `leaving cook mode before the end hands nothing over`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = open(checked = setOf(1))
        advanceUntilIdle()
        vm.onCookStart()
        vm.onStepDone()
        vm.onCookExit()
        assertNull(vm.uiState.value.cookFinished)
    }
}
