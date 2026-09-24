package com.example.recipeclipper.ui.plan

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.SNACK
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddToPlanViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plan = FakeMealPlanRepository()
    private val today = FakePlanCalendar.WEDNESDAY
    private val monday = today - 2

    @Test
    fun `starts on today, Dinner and the recipe's yield, over this week and next`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = AddToPlanViewModel(plan, FakePlanCalendar())
            advanceUntilIdle()
            vm.setRecipe(7, yieldServings = 4)

            val state = vm.uiState.value
            assertEquals((monday until monday + 14).toList(), state.days)
            assertEquals(today, state.selectedDay)
            assertEquals(DINNER, state.selectedMealTypeId)
            assertEquals(4, state.servings)
            assertTrue(state.canAdd)
        }

    @Test
    fun `adds the chosen day, meal type and servings`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = AddToPlanViewModel(plan, FakePlanCalendar())
        advanceUntilIdle()
        vm.setRecipe(7, yieldServings = 4)
        vm.onDaySelected(monday + 8)
        vm.onMealTypeSelected(SNACK)
        vm.onServingsChange(6)
        vm.onAdd()
        advanceUntilIdle()

        val meal = plan.meals.value.single()
        assertEquals(7L, meal.recipeId)
        assertEquals(monday + 8, meal.day)
        assertEquals(SNACK, meal.mealTypeId)
        assertEquals(6, meal.servings)
        assertTrue(vm.uiState.value.added)
        assertFalse(vm.uiState.value.canAdd)
    }

    @Test
    fun `a yield with no number plans no servings and offers no stepper`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = AddToPlanViewModel(plan, FakePlanCalendar())
        advanceUntilIdle()
        vm.setRecipe(7, yieldServings = null)
        vm.onServingsChange(3)
        assertNull(vm.uiState.value.servings)
        vm.onAdd()
        advanceUntilIdle()
        assertNull(plan.meals.value.single().servings)
    }

    @Test
    fun `servings stay between 1 and the maximum`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = AddToPlanViewModel(plan, FakePlanCalendar())
        advanceUntilIdle()
        vm.setRecipe(7, yieldServings = 1)
        vm.onServingsChange(0)
        assertEquals(1, vm.uiState.value.servings)
    }

    @Test
    fun `adding twice writes one meal`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = AddToPlanViewModel(plan, FakePlanCalendar())
        advanceUntilIdle()
        vm.setRecipe(7, yieldServings = 2)
        vm.onAdd()
        vm.onAdd()
        advanceUntilIdle()
        assertEquals(1, plan.meals.value.size)
    }

    @Test
    fun `opening the sheet again starts afresh`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = AddToPlanViewModel(plan, FakePlanCalendar())
        advanceUntilIdle()
        vm.setRecipe(7, yieldServings = 2)
        vm.onMealTypeSelected(SNACK)
        vm.onAdd()
        advanceUntilIdle()

        vm.setRecipe(8, yieldServings = 3)
        assertFalse(vm.uiState.value.added)
        assertEquals(DINNER, vm.uiState.value.selectedMealTypeId)
        assertEquals(3, vm.uiState.value.servings)
    }
}
