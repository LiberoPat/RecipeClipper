package com.example.recipeclipper.ui.mealplan

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.MealPlanImport
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeLibraryPolicy
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.edit.SharedTextInbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fresh food's note in the meal-plan review (#396): a perishable recipe planned late, one swap, never by itself. iOS: `MealPlanReviewTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class MealPlanReviewFreshTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val calendar = FakePlanCalendar()
    private val today = calendar.today
    private val inbox = SharedTextInbox()
    private val recipes = FakeRecipeRepository()
    private val week = FakeMealPlanRepository()
    private val policy = FakeLibraryPolicy(LibraryLimit.Unlimited)

    private fun recipe(name: String, vararg lines: String, language: String = "en") = Recipe(
        id = 0, name = name, image = null, ingredients = lines.toList(), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "", language = language
    )

    private fun viewModel(on: Boolean = true, vararg items: Pair<Recipe, Long?>): MealPlanReviewViewModel {
        val key = inbox.offerPlan("text", MealPlanImport.Plan(items.map { (r, d) -> MealPlanImport.Item(r, d) }))
        val flags = FeatureFlags(FakeFeatureFlagStore(mapOf("freshFood" to on)), FlagRegistry.definitions, isDebug = true)
        return MealPlanReviewViewModel(
            SavedStateHandle(mapOf(MealPlanReviewViewModel.PLAN_ARG to key)),
            recipes, week, calendar, policy, FakeEntitlements(), inbox, mainDispatcherRule.dispatcher, flags
        )
    }

    private val omelette = recipe("Omelette", "4 eggs", "1 tbsp butter")
    private val salmon = recipe("Salmon bowls", "2 salmon fillets", "1 cup rice")

    @Test fun `a perishable recipe planned later than its group keeps from the plan's first day gets one swap`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(true, omelette to today, salmon to today + 5)
            advanceUntilIdle()

            val nudge = vm.uiState.value.nudges.getValue(1)
            assertEquals("Salmon fillets", nudge.name)
            assertEquals("1-2", nudge.keeps)
            assertEquals(0L, nudge.swapKey)
            assertEquals(today, nudge.swapDay)
            assertEquals(setOf(1), vm.uiState.value.nudges.keys)
            // Shown, not done.
            assertEquals(listOf(today, today + 5), vm.uiState.value.items.map { it.day })
        }

    @Test fun `the swap trades the two days, and the note goes`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(true, omelette to today, salmon to today + 5)
        advanceUntilIdle()

        vm.onSwap(1)
        assertEquals(listOf(today + 5, today), vm.uiState.value.items.map { it.day })
        assertTrue(vm.uiState.value.nudges.isEmpty())
        assertTrue(week.meals.value.isEmpty()) // nothing is saved before Save
    }

    @Test fun `swapping where there is no note does nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(true, omelette to today, salmon to today + 1)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.nudges.isEmpty())
        vm.onSwap(1)
        assertEquals(listOf(today, today + 1), vm.uiState.value.items.map { it.day })
    }

    @Test fun `the note follows the cook's changes, a day picked or a recipe unticked`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(true, omelette to today, salmon to today + 1)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.nudges.isEmpty())

        vm.onDay(1, today + 4)
        assertNotNull(vm.uiState.value.nudges[1])
        vm.onDay(1, null) // not planned: nothing to be late
        assertTrue(vm.uiState.value.nudges.isEmpty())
        vm.onDay(1, today + 4)
        vm.onTick(0) // the first meal is out, so the plan starts on the salmon's day
        assertTrue(vm.uiState.value.nudges.isEmpty())
    }

    @Test fun `a recipe with nothing perishable, or a passed day, or the flag off, has no note`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(true, omelette to today, recipe("Rice", "2 cups rice") to today + 9, salmon to today - 3)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.nudges.isEmpty())

            val off = viewModel(false, omelette to today, salmon to today + 5)
            advanceUntilIdle()
            assertTrue(off.uiState.value.nudges.isEmpty())
            off.onSwap(1)
            assertEquals(listOf(today, today + 5), off.uiState.value.items.map { it.day })
        }

    @Test fun `the recipe's own language reads its ingredients`() = runTest(mainDispatcherRule.dispatcher) {
        val lachs = recipe("Lachs mit Reis", "2 Lachsfilets", "200 g Reis", language = "de")
        val vm = viewModel(true, omelette to today, lachs to today + 5)
        advanceUntilIdle()
        assertEquals("Lachsfilets", vm.uiState.value.nudges.getValue(1).name)
    }

    @Test fun `Save plans the days as swapped`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(true, omelette to today, salmon to today + 5)
        advanceUntilIdle()
        recipes.addManualQueue.add(recipe("Omelette").copy(id = 1))
        recipes.addManualQueue.add(recipe("Salmon bowls").copy(id = 2))
        vm.onSwap(1)
        vm.onSave()
        advanceUntilIdle()
        assertEquals(listOf(1L to today + 5, 2L to today), week.meals.value.map { it.recipeId!! to it.day })
    }
}
