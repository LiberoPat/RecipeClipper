package com.example.recipeclipper.ui.recipes

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.model.CookedChoice
import com.example.recipeclipper.data.model.OriginChoice
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeFilterChoices
import com.example.recipeclipper.data.model.RecipeFilterPill
import com.example.recipeclipper.data.model.RecipeSort
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
 * Recipes' views folded into the filter sheet (#454), as the ViewModel serves them: each control narrows the
 * list (the views through the repository's own view filters, the rest as #378's), they combine with each
 * other and the sort, the badge counts the pills, a pill's × and Clear undo them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipesFiltersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun summary(
        id: Long, title: String, total: String? = null, saved: Boolean = false, cooked: Long? = null,
        url: String = "https://site.com/$id", chat: Boolean = false
    ) = RecipeSummary(
        id, title, null, total, id, isSaved = saved, lastCookedDay = cooked, sourceUrl = url, isFromChat = chat, language = "en"
    )

    // 1 typed, loose, cooked, quick; 2 typed, listed; 3 from a chat, loose; 4 parsed, loose, cooked, slow; 5 parsed, listed, quick.
    private fun repository() = FakeRecipeRepository().apply {
        history.value = listOf(
            summary(5, "Egg", total = "10m", saved = true),
            summary(4, "Dal", total = "2h", cooked = 20_001),
            summary(3, "Curry", url = "manual:chat-3", chat = true),
            summary(2, "Bread", url = "manual:b", saved = true),
            summary(1, "Apple", total = "15m", url = "manual:a", cooked = 20_000)
        )
    }

    private fun ids(vm: RecipesViewModel) = vm.uiState.value.recipes!!.map { it.id }

    @Test fun `nothing chosen lists every recipe with no pill and no badge`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = RecipesViewModel(repository(), FakeAppPreferences())
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), ids(vm))
        assertEquals(emptyList<RecipeFilterPill>(), vm.uiState.value.choices.pills)
        assertFalse(vm.uiState.value.filtersOn)
    }

    @Test fun `each control narrows the list to what its view or filter selects`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repository()
        val vm = RecipesViewModel(repository, FakeAppPreferences())
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        fun check(choices: RecipeFilterChoices, expected: List<Long>) {
            vm.onFilterChoicesChange(choices)
            advanceUntilIdle()
            assertEquals(choices.toString(), expected, ids(vm))
            assertTrue(vm.uiState.value.filtersOn)
            assertEquals(1, vm.uiState.value.choices.pills.size)
        }
        check(RecipeFilterChoices(thirtyOrLess = true), listOf(5L, 1L))
        check(RecipeFilterChoices(cooked = CookedChoice.COOKED), listOf(4L, 1L))
        check(RecipeFilterChoices(cooked = CookedChoice.NOT_YET), listOf(5L, 3L, 2L))
        check(RecipeFilterChoices(notInAList = true), listOf(4L, 3L, 1L))
        check(RecipeFilterChoices(origin = OriginChoice.TYPED), listOf(2L, 1L))
        check(RecipeFilterChoices(origin = OriginChoice.CHAT), listOf(3L))
        // The views went to the repository as the view screens' own filters.
        assertTrue(RecipeFilter.COOKED in repository.historyFilters)
        assertTrue(RecipeFilter.TYPED in repository.historyFilters)
        assertTrue(RecipeFilter.CHAT in repository.historyFilters)
    }

    @Test fun `the controls combine with each other, the search's answer and the sort`() = runTest(mainDispatcherRule.dispatcher) {
        val prefs = FakeAppPreferences()
        val vm = RecipesViewModel(repository(), prefs)
        collectEagerly(vm.uiState)
        advanceUntilIdle()

        vm.onFilterChoicesChange(RecipeFilterChoices(notInAList = true, cooked = CookedChoice.COOKED))
        advanceUntilIdle()
        assertEquals(listOf(4L, 1L), ids(vm))

        vm.onFilterChoicesChange(vm.uiState.value.choices.copy(origin = OriginChoice.TYPED))
        advanceUntilIdle()
        assertEquals(listOf(1L), ids(vm))

        vm.onFilterChoicesChange(RecipeFilterChoices(cooked = CookedChoice.NOT_YET, thirtyOrLess = true))
        advanceUntilIdle()
        assertEquals(listOf(5L), ids(vm))

        vm.onFilterChoicesChange(RecipeFilterChoices(notInAList = true))
        vm.onSortChange(RecipeSort.NAME)
        advanceUntilIdle()
        assertEquals(listOf(1L, 3L, 4L), ids(vm))

        // Two views that nothing satisfies at once: none, and the empty line says the filters did it.
        vm.onFilterChoicesChange(RecipeFilterChoices(cooked = CookedChoice.COOKED, origin = OriginChoice.CHAT))
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), ids(vm))
        assertTrue(vm.uiState.value.showsSortLine)
    }

    @Test fun `Cooked and Came from are one choice each, the last pick replacing the first`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = RecipesViewModel(repository(), FakeAppPreferences())
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        vm.onFilterChoicesChange(RecipeFilterChoices(cooked = CookedChoice.COOKED, origin = OriginChoice.TYPED))
        vm.onFilterChoicesChange(vm.uiState.value.choices.copy(cooked = CookedChoice.NOT_YET, origin = OriginChoice.CHAT))
        advanceUntilIdle()
        assertEquals(listOf(RecipeFilterPill.NOT_COOKED, RecipeFilterPill.CHAT), vm.uiState.value.choices.pills)
        assertEquals(listOf(3L), ids(vm))
    }

    @Test fun `the badge counts the pills, a pill's x undoes its filter, and Clear undoes them all`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = RecipesViewModel(repository(), FakeAppPreferences())
            collectEagerly(vm.uiState)
            advanceUntilIdle()
            vm.onFilterChoicesChange(
                RecipeFilterChoices(thirtyOrLess = true, cooked = CookedChoice.COOKED, notInAList = true, origin = OriginChoice.TYPED)
            )
            advanceUntilIdle()
            assertEquals(
                listOf(RecipeFilterPill.THIRTY_OR_LESS, RecipeFilterPill.COOKED, RecipeFilterPill.NOT_IN_A_LIST, RecipeFilterPill.TYPED),
                vm.uiState.value.choices.pills
            )
            assertEquals(listOf(1L), ids(vm))

            vm.onRemoveFilter(RecipeFilterPill.COOKED)
            advanceUntilIdle()
            assertEquals(CookedChoice.ANY, vm.uiState.value.choices.cooked)
            assertEquals(3, vm.uiState.value.choices.pills.size)
            assertEquals(listOf(1L), ids(vm))

            vm.onRemoveFilter(RecipeFilterPill.THIRTY_OR_LESS)
            advanceUntilIdle()
            assertEquals(listOf(1L), ids(vm)) // typed and loose: only Apple

            vm.onClearQuickFilters()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.choices.isEmpty)
            assertFalse(vm.uiState.value.filtersOn)
            assertEquals(5, ids(vm).size)
        }

    @Test fun `the sheet's pantry switch turns on the You have lines as the chip did`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = RecipesViewModel(repository(), FakeAppPreferences())
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.pantryOn)
        vm.onFilterChoicesChange(RecipeFilterChoices(usesPantry = true))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pantryOn)
        // No pantry repository: nothing is covered, so nothing matches, never a guess.
        assertEquals(emptyList<Long>(), ids(vm))
        assertEquals(listOf(RecipeFilterPill.USES_PANTRY), vm.uiState.value.choices.pills)
    }
}
