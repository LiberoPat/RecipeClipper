package com.example.recipeclipper.ui.week

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.BREAKFAST
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.LUNCH
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
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
class WeekViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plan = FakeMealPlanRepository()
    private val recipes = FakeRecipeRepository()
    private val calendar = FakePlanCalendar()
    private val today = FakePlanCalendar.WEDNESDAY
    private val monday = today - 2

    private fun viewModel() = WeekViewModel(plan, recipes, calendar)

    @Test
    fun `opens on this week, from the locale's first day`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(monday, state.weekStart)
        assertTrue(state.isThisWeek)
        assertEquals((monday..monday + 6).toList(), state.days.map { it.day })
    }

    @Test
    fun `a Sunday-first locale starts the week on Sunday`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.firstDayOfWeek = FakePlanCalendar.SUNDAY_FIRST
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(today - 3, vm.uiState.value.days.first().day)
    }

    @Test
    fun `meals land on their day, in meal type order`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(7, today, DINNER, servings = 4)
        plan.addNote("Leftovers", today, LUNCH)
        plan.addRecipe(8, today + 7, DINNER, servings = null) // next week
        val vm = viewModel()
        advanceUntilIdle()

        val wednesday = vm.uiState.value.days.single { it.day == today }
        assertEquals(listOf("Leftovers", "Recipe 7"), wednesday.meals.map { it.note ?: it.title })
        assertEquals(2, vm.uiState.value.days.sumOf { it.meals.size })
    }

    @Test
    fun `arrows change the week and This week comes back`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(8, today + 7, DINNER, servings = null)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onNextWeek()
        advanceUntilIdle()
        assertEquals(monday + 7, vm.uiState.value.weekStart)
        assertFalse(vm.uiState.value.isThisWeek)
        assertEquals(1, vm.uiState.value.days.sumOf { it.meals.size })

        vm.onPreviousWeek()
        vm.onPreviousWeek()
        advanceUntilIdle()
        assertEquals(monday - 7, vm.uiState.value.weekStart)

        vm.onThisWeek()
        advanceUntilIdle()
        assertEquals(monday, vm.uiState.value.weekStart)
        assertTrue(vm.uiState.value.isThisWeek)
    }

    @Test
    fun `plus on a day adds a recipe from history, as Dinner by default`() = runTest(mainDispatcherRule.dispatcher) {
        recipes.history.value = listOf(RecipeSummary(5, "Soup", null, null, 0, false))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddToDay(today + 1)
        advanceUntilIdle()
        assertEquals(DINNER, vm.uiState.value.adding?.mealTypeId)
        assertEquals(listOf("Soup"), vm.uiState.value.adding?.results?.map { it.title })

        vm.onAddRecipe(5)
        advanceUntilIdle()
        assertNull(vm.uiState.value.adding)
        val meal = plan.meals.value.single()
        assertEquals(5L, meal.recipeId)
        assertEquals(today + 1, meal.day)
        assertEquals(DINNER, meal.mealTypeId)
        assertNull(meal.servings)
    }

    @Test
    fun `what is typed can be planned as a note, in the chosen meal type`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddToDay(today)
        vm.onAddMealTypeSelected(BREAKFAST)
        vm.onAddQueryChange("  Eat out  ")
        vm.onAddNote()
        advanceUntilIdle()

        val meal = plan.meals.value.single()
        assertEquals("Eat out", meal.note)
        assertEquals(BREAKFAST, meal.mealTypeId)
    }

    @Test
    fun `a blank note is not added`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onAddToDay(today)
        vm.onAddQueryChange("   ")
        vm.onAddNote()
        advanceUntilIdle()
        assertTrue(plan.meals.value.isEmpty())
    }

    @Test
    fun `moving offers this week and next and writes the new slot`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(7, today, DINNER, servings = 2)
        val vm = viewModel()
        advanceUntilIdle()
        val meal = vm.uiState.value.days.single { it.day == today }.meals.single()

        vm.onMoveStart(meal)
        val moving = vm.uiState.value.moving!!
        assertEquals((monday until monday + 14).toList(), moving.days)
        assertEquals(today, moving.day)

        vm.onMoveDaySelected(monday + 9)
        vm.onMoveMealTypeSelected(LUNCH)
        vm.onMoveConfirm()
        advanceUntilIdle()

        assertNull(vm.uiState.value.moving)
        val moved = plan.meals.value.single()
        assertEquals(monday + 9, moved.day)
        assertEquals(LUNCH, moved.mealTypeId)
        assertEquals(2, moved.servings)
    }

    @Test
    fun `removing can be undone`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(7, today, DINNER, servings = null)
        val vm = viewModel()
        advanceUntilIdle()
        val meal = vm.uiState.value.days.single { it.day == today }.meals.single()

        vm.onRemove(meal)
        advanceUntilIdle()
        assertTrue(plan.meals.value.isEmpty())
        assertEquals("Recipe 7", vm.uiState.value.removed?.label)

        vm.onUndoRemove()
        advanceUntilIdle()
        assertNull(vm.uiState.value.removed)
        assertEquals(listOf(meal.id), plan.meals.value.map { it.id })
    }

    @Test
    fun `a removal stands once the snackbar goes`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(7, today, DINNER, servings = null)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onRemove(vm.uiState.value.days.single { it.day == today }.meals.single())
        advanceUntilIdle()

        vm.onSnackbarDismissed()
        vm.onUndoRemove()
        advanceUntilIdle()
        assertTrue(plan.meals.value.isEmpty())
    }
}
