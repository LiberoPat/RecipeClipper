package com.example.recipeclipper.ui.recipes

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeQuickFilter
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The top of Recipes, design B (#449), as the ViewModel serves it: the filters on (the sheet's
 * switches, the pills and the button's badge), Clear, and the selection's Select all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipesTopBViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun summary(id: Long, total: String? = null, cooked: Long? = null) =
        RecipeSummary(id, "Recipe $id", null, total, id, isSaved = false, lastCookedDay = cooked, language = "en")

    private fun repository() = FakeRecipeRepository().apply {
        history.value = listOf(summary(1, total = "20m"), summary(2, total = "2h"), summary(3, total = "10m", cooked = 20_000))
    }

    @Test fun `the filters on are counted in the offered order, whatever order they were turned on in`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = RecipesViewModel(repository(), FakeAppPreferences())
            collectEagerly(vm.uiState)
            advanceUntilIdle()
            assertEquals(emptyList<RecipeQuickFilter>(), vm.uiState.value.activeQuickFilters)

            vm.onQuickFilterToggle(RecipeQuickFilter.NOT_COOKED)
            vm.onQuickFilterToggle(RecipeQuickFilter.THIRTY_OR_LESS)
            advanceUntilIdle()
            assertEquals(
                listOf(RecipeQuickFilter.THIRTY_OR_LESS, RecipeQuickFilter.NOT_COOKED),
                vm.uiState.value.activeQuickFilters
            )
            assertEquals(listOf(1L), vm.uiState.value.recipes!!.map { it.id })

            // A pill's ×: the same toggle.
            vm.onQuickFilterToggle(RecipeQuickFilter.NOT_COOKED)
            advanceUntilIdle()
            assertEquals(listOf(RecipeQuickFilter.THIRTY_OR_LESS), vm.uiState.value.activeQuickFilters)
            assertEquals(listOf(1L, 3L), vm.uiState.value.recipes!!.map { it.id })
        }

    @Test fun `Clear turns every filter off at once and the list comes back`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = RecipesViewModel(repository(), FakeAppPreferences())
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        vm.onQuickFilterToggle(RecipeQuickFilter.THIRTY_OR_LESS)
        vm.onQuickFilterToggle(RecipeQuickFilter.NOT_COOKED)
        advanceUntilIdle()

        vm.onClearQuickFilters()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.quick.isEmpty())
        assertTrue(vm.uiState.value.activeQuickFilters.isEmpty())
        assertEquals(3, vm.uiState.value.recipes!!.size)
    }

    @Test fun `inside the Cooked view Not cooked yet is never counted`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = RecipesViewModel(
            repository(), FakeAppPreferences(),
            savedStateHandle = SavedStateHandle(mapOf(RecipesViewModel.VIEW_ARG to RecipeFilter.COOKED.name))
        )
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        vm.onQuickFilterToggle(RecipeQuickFilter.NOT_COOKED)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.activeQuickFilters.isEmpty())
    }

    @Test fun `Select all ticks every row in sight and never one a filter hides`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = RecipesViewModel(repository(), FakeAppPreferences())
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        vm.onQuickFilterToggle(RecipeQuickFilter.THIRTY_OR_LESS)
        vm.onStartSelecting()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.allSelected)

        vm.onSelectAll()
        advanceUntilIdle()

        assertEquals(setOf(1L, 3L), vm.uiState.value.selectedIds)
        assertTrue(vm.uiState.value.allSelected)
        // The filter off again: the hidden row was never ticked.
        vm.onQuickFilterToggle(RecipeQuickFilter.THIRTY_OR_LESS)
        advanceUntilIdle()
        assertEquals(setOf(1L, 3L), vm.uiState.value.selectedIds)
        assertFalse(vm.uiState.value.allSelected)
    }

    @Test fun `Select all does nothing when not selecting`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = RecipesViewModel(repository(), FakeAppPreferences())
        collectEagerly(vm.uiState)
        advanceUntilIdle()

        vm.onSelectAll()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.selecting)
        assertTrue(vm.uiState.value.selectedIds.isEmpty())
    }
}
