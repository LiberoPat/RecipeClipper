package com.example.recipeclipper.ui.mealtypes

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.BREAKFAST
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.LUNCH
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.SNACK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MealTypesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plan = FakeMealPlanRepository()

    private fun names(vm: MealTypesViewModel) = vm.uiState.value.types.map { it.name }

    @Test
    fun `adds a type last`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = MealTypesViewModel(plan)
        advanceUntilIdle()
        vm.onStartCreating()
        vm.onNewNameChange(" Brunch ")
        vm.onCreate()
        advanceUntilIdle()
        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snack", "Brunch"), names(vm))
        assertEquals(false, vm.uiState.value.creating)
    }

    @Test
    fun `renames any type, a seeded one included`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = MealTypesViewModel(plan)
        advanceUntilIdle()
        vm.onRenameStart(vm.uiState.value.types.single { it.id == DINNER })
        vm.onRenameTextChange("Supper")
        vm.onRenameConfirm()
        advanceUntilIdle()
        assertEquals("Supper", vm.uiState.value.types.single { it.id == DINNER }.name)
        assertNull(vm.uiState.value.renaming)
    }

    @Test
    fun `moves a type up and down, and not past either end`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = MealTypesViewModel(plan)
        advanceUntilIdle()
        val types = vm.uiState.value.types
        vm.onMoveUp(types.single { it.id == SNACK })
        advanceUntilIdle()
        assertEquals(listOf(BREAKFAST, LUNCH, SNACK, DINNER), plan.types.value.map { it.id })

        vm.onMoveUp(vm.uiState.value.types.first())
        vm.onMoveDown(vm.uiState.value.types.last())
        advanceUntilIdle()
        assertEquals(listOf(BREAKFAST, LUNCH, SNACK, DINNER), plan.types.value.map { it.id })
    }

    @Test
    fun `deleting a user type moves its meals to Dinner`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addMealType("Brunch")
        val brunch = plan.types.value.single { it.name == "Brunch" }.id
        plan.addNote("Pancakes", 20_000, brunch)
        val vm = MealTypesViewModel(plan)
        advanceUntilIdle()

        vm.onDeleteStart(vm.uiState.value.types.single { it.id == brunch })
        vm.onDeleteConfirm()
        advanceUntilIdle()

        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snack"), names(vm))
        assertEquals(DINNER, plan.meals.value.single().mealTypeId)
    }

    @Test
    fun `a seeded type offers no delete`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = MealTypesViewModel(plan)
        advanceUntilIdle()
        vm.onDeleteStart(vm.uiState.value.types.single { it.id == LUNCH })
        assertNull(vm.uiState.value.deleting)
    }
}
