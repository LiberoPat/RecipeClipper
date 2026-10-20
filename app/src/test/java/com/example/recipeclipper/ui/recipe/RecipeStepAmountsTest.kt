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

    // --- Cook mode's ingredients for this step (#286) ---

    private val stew = recipe.copy(
        ingredients = listOf("2 carrots, grated", "1 cup all-purpose flour", "1 onion, chopped"),
        instructions = listOf("Stir in the flour and the carrots.", "Add the onion.", "Serve.")
    )

    private fun RecipeViewModel.groups() = uiState.value.stepIngredients

    @Test fun `the current step's lines come first, with the switch off and the steps as written`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeAppPreferences(), stew)
        advanceUntilIdle()
        assertNull("Only in cook mode", vm.groups())

        vm.onCookStart()
        assertEquals(StepIngredients(now = listOf(0, 1), later = listOf(2)), vm.groups())
        assertNull("The steps stay as written", vm.step())

        vm.onStepDone()
        assertEquals(StepIngredients(now = listOf(2), later = listOf(0, 1)), vm.groups())

        // A step that names no line: the bar lists every line, as before.
        vm.onStepSelected(2)
        assertNull(vm.groups())

        vm.onCookExit()
        assertNull(vm.groups())
    }

    @Test fun `the grouping is the same with the switch on, and follows the servings and units`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeAppPreferences(amountsInSteps = true), stew)
        advanceUntilIdle()
        vm.onCookStart()
        assertEquals(StepIngredients(now = listOf(0, 1), later = listOf(2)), vm.groups())
        assertEquals("Stir in ⟦1 cup⟧ flour and ⟦2⟧ carrots.", vm.step())

        vm.onServingsChange(8)
        vm.onUnitSystemChange(UnitSystem.METRIC)
        assertEquals(StepIngredients(now = listOf(0, 1), later = listOf(2)), vm.groups())
    }

    @Test fun `a tick in either group is the line's own`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeAppPreferences(), stew)
        advanceUntilIdle()
        vm.onCookStart()

        vm.onIngredientChecked(1, true)
        vm.onIngredientChecked(2, true)
        assertEquals(setOf(1, 2), vm.uiState.value.checkedIngredients)
        assertEquals(StepIngredients(now = listOf(0, 1), later = listOf(2)), vm.groups())
    }

    @Test fun `a link's lines are in this step`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeAppPreferences(amountsInSteps = true), twoButters)
        advanceUntilIdle()
        vm.onCookStart()
        vm.onIngredientLink(0, listOf(0, 1))
        assertEquals(StepIngredients(now = listOf(0, 1), later = listOf(2)), vm.groups())
        assertTrue(vm.uiState.value.cook.ingredientsExpanded)
    }

    // Group headings: one moves with its lines only when every line under it is in the step.
    private val cake = listOf(
        "For the cake:", "2 cups flour", "1 cup sugar", "2 eggs",
        "For the frosting:", "100g butter", "200g icing sugar"
    )

    @Test fun `a heading moves to this step only with all its lines, after the step's other lines`() {
        assertEquals(StepIngredients(listOf(1, 2), listOf(0, 3, 4, 5, 6)), StepIngredients.group(cake, listOf(1, 2)))
        assertEquals(StepIngredients(listOf(4, 5, 6), listOf(0, 1, 2, 3)), StepIngredients.group(cake, listOf(5, 6)))
        assertEquals(StepIngredients(listOf(3, 4, 5, 6), listOf(0, 1, 2)), StepIngredients.group(cake, listOf(5, 3, 6)))
        assertEquals(StepIngredients(listOf(0, 1, 2, 3, 4, 5, 6), emptyList()), StepIngredients.group(cake, listOf(1, 2, 3, 5, 6)))
        // A heading with no lines under it stays where it is.
        assertEquals(StepIngredients(listOf(0, 1), listOf(2)), StepIngredients.group(listOf("For the cake:", "flour", "To decorate:"), listOf(1)))
    }

    @Test fun `no named line, or only a heading, lists every line as before`() {
        assertNull(StepIngredients.group(cake, emptyList()))
        assertNull(StepIngredients.group(cake, listOf(0, 4)))
        assertNull(StepIngredients.group(cake, listOf(9)))
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
