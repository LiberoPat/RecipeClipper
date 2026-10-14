package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.StepAmounts
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Amounts inside steps (#101) follow the servings stepper, the unit menu and the switch. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeStepAmountsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val recipe = Recipe(
        name = "Carrot Cake", image = null,
        ingredients = listOf("2 carrots, grated", "1 cup all-purpose flour"),
        instructions = listOf("Stir in the flour and the carrots."),
        prepTime = null, cookTime = null, totalTime = null, yield = "4 servings",
        sourceUrl = "https://example.com/cake", id = 1L
    )

    private fun TestScope.viewModel(preferences: FakeAppPreferences, opened: Recipe = recipe) = RecipeViewModel(
        SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to 1L)),
        FakeRecipeRepository().apply { openResult = opened }, preferences, Clock { testScheduler.currentTime },
        FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler()
    )

    private fun RecipeViewModel.step(index: Int = 0): String? =
        (uiState.value.content as RecipeContent.Success).stepAmounts?.get(index)?.let(StepAmounts::marked)

    // Two butter lines with different amounts (#254): the step's "butter" is a link, not a number.
    private val twoButters = recipe.copy(
        ingredients = listOf("200g butter", "100g butter", "1 cup flour"),
        instructions = listOf("Melt the butter.", "Stir in the flour.")
    )

    @Test fun `a link highlights its lines until they fade, then goes back to its step`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeAppPreferences(amountsInSteps = true), twoButters)
        advanceUntilIdle()
        assertEquals("Melt the ⟪butter|0,1⟫.", vm.step(0))

        vm.onIngredientLink(0, listOf(0, 1))
        assertEquals(IngredientJump(0, listOf(0, 1)), vm.uiState.value.ingredientJump)
        vm.onIngredientJumpScrolled()
        assertEquals(IngredientJump(0, listOf(0, 1), scrollTo = null), vm.uiState.value.ingredientJump)

        advanceTimeBy(RecipeViewModel.HIGHLIGHT_MILLIS + 1)
        assertEquals(false, vm.uiState.value.ingredientJump?.highlighted)

        vm.onBackToStep()
        assertEquals(JumpScroll.STEP, vm.uiState.value.ingredientJump?.scrollTo)
        vm.onIngredientJumpScrolled()
        assertNull(vm.uiState.value.ingredientJump)
    }

    @Test fun `in cook mode a link opens the folded ingredients, and back folds them again`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeAppPreferences(amountsInSteps = true), twoButters)
        advanceUntilIdle()
        vm.onCookStart()

        vm.onIngredientLink(0, listOf(0, 1))
        assertTrue(vm.uiState.value.cook.ingredientsExpanded)
        assertEquals(true, vm.uiState.value.ingredientJump?.refold)

        vm.onBackToStep()
        assertFalse(vm.uiState.value.cook.ingredientsExpanded)
        assertNull(vm.uiState.value.ingredientJump)

        // Opened by hand first: back leaves the bar open.
        vm.onIngredientsToggle()
        vm.onIngredientLink(0, listOf(0, 1))
        vm.onBackToStep()
        assertTrue(vm.uiState.value.cook.ingredientsExpanded)
    }

    @Test fun `the amount follows the servings and the units`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences(amountsInSteps = true)
        val vm = viewModel(preferences)
        advanceUntilIdle()
        assertEquals("Stir in ⟦1 cup⟧ flour and ⟦2⟧ carrots.", vm.step())

        vm.onServingsChange(8)
        assertEquals("Stir in ⟦2 cup⟧ flour and ⟦4⟧ carrots.", vm.step())

        vm.onUnitSystemChange(UnitSystem.METRIC)
        assertEquals("Stir in ⟦240 g⟧ flour and ⟦4⟧ carrots.", vm.step())
    }

    @Test fun `off by default, and the switch applies to an open recipe`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences()
        val vm = viewModel(preferences)
        advanceUntilIdle()
        assertNull(vm.step())

        preferences.amountsInSteps = true
        advanceUntilIdle()
        assertEquals("Stir in ⟦1 cup⟧ flour and ⟦2⟧ carrots.", vm.step())
        // The steps as written stay what the screen shares.
        assertEquals(recipe.instructions, (vm.uiState.value.content as RecipeContent.Success).instructions)
    }
}
