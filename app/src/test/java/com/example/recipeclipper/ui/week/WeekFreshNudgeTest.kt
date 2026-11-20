package com.example.recipeclipper.ui.week

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.LUNCH
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fresh food's notes on the Week's meals (#396): which meals, the swap, and the flag. iOS: `WeekFreshNudgeTests`. */
@OptIn(ExperimentalCoroutinesApi::class)
class WeekFreshNudgeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = FakePlanCalendar.WEDNESDAY
    private val calendar = FakePlanCalendar()
    private val plan = FakeMealPlanRepository()
    private val groceries = FakeGroceryRepository()

    private fun flags(on: Boolean) =
        FeatureFlags(FakeFeatureFlagStore(mapOf("freshFood" to on)), FlagRegistry.definitions, isDebug = true)

    private fun viewModel(on: Boolean = true) = WeekViewModel(plan, FakeRecipeRepository(), calendar, groceries, flags(on))

    private suspend fun planned(recipeId: Long, day: Long, type: Long, vararg lines: String) {
        plan.addRecipe(recipeId, day, type, null)
        val meal = plan.meals.value.last()
        groceries.planned = groceries.planned + PlannedIngredients(meal.id, day, null, recipeId, "Recipe $recipeId", lines.toList(), null, "en")
    }

    @Test
    fun `the swap trades two meals' days and each keeps its meal type`() = runTest(mainDispatcherRule.dispatcher) {
        planned(7, today, LUNCH, "1 lb spaghetti")
        planned(9, today + 6, DINNER, "2 salmon fillets")
        val (pasta, salmon) = plan.meals.value
        val vm = viewModel()
        advanceUntilIdle()

        val nudge = vm.uiState.value.freshNudges.getValue(salmon.id)
        assertEquals(pasta.id, nudge.swapKey)
        assertEquals(today, nudge.swapDay)
        assertEquals("1-2", nudge.keeps)
        assertEquals("Salmon fillets", nudge.name)
        assertEquals(listOf(today, today + 6), plan.meals.value.map { it.day }) // not moved by the note

        vm.onFreshSwap(salmon.id)
        advanceUntilIdle()
        val after = plan.meals.value.associateBy { it.id }
        assertEquals(today to DINNER, after.getValue(salmon.id).let { it.day to it.mealTypeId })
        assertEquals(today + 6 to LUNCH, after.getValue(pasta.id).let { it.day to it.mealTypeId })
        assertTrue(vm.uiState.value.freshNudges.isEmpty()) // swapped, so nothing is late
    }

    @Test
    fun `a note is counted from the plan's first day and follows the plan`() = runTest(mainDispatcherRule.dispatcher) {
        planned(9, today + 6, DINNER, "2 salmon fillets")
        plan.addNote("Eat out", today + 1, DINNER)
        val salmon = plan.meals.value.first()
        val vm = viewModel()
        advanceUntilIdle()
        // From the plan's first day (tomorrow's note): 5 days on, past the 1-2.
        assertEquals(setOf(salmon.id), vm.uiState.value.freshNudges.keys)

        // Planned for the day after, nothing is late.
        plan.move(salmon.id, today + 2, DINNER)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.freshNudges.isEmpty())
    }

    @Test
    fun `a perishable meal with nothing to swap with has no note, and a non-perishable one never has`() =
        runTest(mainDispatcherRule.dispatcher) {
            planned(9, today + 6, DINNER, "2 salmon fillets")
            planned(7, today + 8, DINNER, "1 lb spaghetti")
            val vm = viewModel()
            advanceUntilIdle()
            // Salmon is the plan's first meal: nothing is late counting from it.
            assertTrue(vm.uiState.value.freshNudges.isEmpty())
        }

    @Test
    fun `without the flag or the repository there are no notes, and a swap does nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            planned(7, today, DINNER, "1 lb spaghetti")
            planned(9, today + 5, DINNER, "2 salmon fillets")
            val off = viewModel(on = false)
            val without = WeekViewModel(plan, FakeRecipeRepository(), calendar)
            advanceUntilIdle()
            assertTrue(off.uiState.value.freshNudges.isEmpty())
            assertTrue(without.uiState.value.freshNudges.isEmpty())
            off.onFreshSwap(plan.meals.value[1].id)
            advanceUntilIdle()
            assertEquals(listOf(today, today + 5), plan.meals.value.map { it.day })
        }
}
