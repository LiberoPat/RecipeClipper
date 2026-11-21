package com.example.recipeclipper.ui.savetolist

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.fake.FakeListRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The save-to-list sheet for several recipes at once (#377). */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveToListSeveralViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun list(id: Long, name: String) =
        RecipeList(id = id, name = name, isBuiltIn = true, isFavorites = id == 1L, recipeCount = 0)

    private fun repository() = FakeListRepository().apply {
        lists.value = listOf(list(1, "Favorites"), list(2, "Lunch"), list(3, "Dinner"))
        // Recipe 10 is in Favorites and Lunch, 11 in Favorites, 12 in nothing.
        membership.value = setOf(10L to 1L, 11L to 1L, 10L to 2L)
    }

    private fun SaveToListViewModel.lists() = uiState.value.lists.associate { it.name to it.containsRecipe }

    @Test
    fun `a list holding all of them is ticked, some indeterminate, none unticked`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = SaveToListViewModel(repository())
            collectEagerly(viewModel.uiState)
            viewModel.setRecipes(listOf(10L, 11L))
            advanceUntilIdle()

            // Favorites holds both, Lunch only 10, Dinner neither.
            assertEquals(mapOf("Favorites" to true, "Lunch" to false, "Dinner" to false), viewModel.lists())
            assertEquals(setOf(2L), viewModel.uiState.value.partialListIds)
            assertEquals(2, viewModel.uiState.value.recipeCount)
        }

    @Test
    fun `a tick adds every recipe, an untick takes every one off, in one write each`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repository()
            val viewModel = SaveToListViewModel(repository)
            collectEagerly(viewModel.uiState)
            viewModel.setRecipes(listOf(10L, 11L, 12L))
            advanceUntilIdle()

            viewModel.onListToggled(3L, true) // Dinner: none had it
            advanceUntilIdle()
            assertTrue(repository.membership.value.containsAll(listOf(10L to 3L, 11L to 3L, 12L to 3L)))
            assertTrue(viewModel.lists().getValue("Dinner"))

            viewModel.onListToggled(2L, true) // Lunch was indeterminate: a tap fills it
            advanceUntilIdle()
            assertTrue(viewModel.lists().getValue("Lunch"))
            assertTrue(viewModel.uiState.value.partialListIds.isEmpty() || 2L !in viewModel.uiState.value.partialListIds)

            viewModel.onListToggled(3L, false) // Dinner now holds all: an untick removes all
            advanceUntilIdle()
            assertTrue(repository.membership.value.none { it.second == 3L })

            assertEquals(
                "one transaction per tick",
                listOf(Triple(listOf(10L, 11L, 12L), 3L, true), Triple(listOf(10L, 11L, 12L), 2L, true), Triple(listOf(10L, 11L, 12L), 3L, false)),
                repository.bulkCalls
            )
        }

    @Test
    fun `an untick of a list that held only some takes those off, leaving the rest alone`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repository()
            val viewModel = SaveToListViewModel(repository)
            collectEagerly(viewModel.uiState)
            viewModel.setRecipes(listOf(10L, 11L))
            advanceUntilIdle()

            viewModel.onListToggled(1L, false) // Favorites, held by both
            advanceUntilIdle()

            assertEquals(setOf(10L to 2L), repository.membership.value)
        }

    @Test
    fun `a new list takes every recipe, in one write`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repository()
        val viewModel = SaveToListViewModel(repository)
        collectEagerly(viewModel.uiState)
        viewModel.setRecipes(listOf(10L, 12L))
        advanceUntilIdle()

        viewModel.onStartCreating()
        viewModel.onNewListNameChange("Weeknights")
        viewModel.onCreateList()
        advanceUntilIdle()

        assertEquals(listOf("Weeknights" to listOf(10L, 12L)), repository.createWithCalls)
        assertTrue(repository.createCalls.isEmpty())
        val created = repository.lists.value.single { it.name == "Weeknights" }
        assertTrue(repository.membership.value.containsAll(listOf(10L to created.id, 12L to created.id)))
        assertFalse(viewModel.uiState.value.creatingList)
    }

    @Test
    fun `one recipe still works as before, with no partial lists`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repository()
        val viewModel = SaveToListViewModel(repository)
        collectEagerly(viewModel.uiState)
        viewModel.setRecipes(listOf(10L, 11L))
        advanceUntilIdle()
        viewModel.setRecipe(10L, "Pancakes")
        advanceUntilIdle()

        assertEquals(mapOf("Favorites" to true, "Lunch" to true, "Dinner" to false), viewModel.lists())
        assertTrue(viewModel.uiState.value.partialListIds.isEmpty())
        assertEquals(1, viewModel.uiState.value.recipeCount)
        assertEquals("Pancakes", viewModel.uiState.value.recipeTitle)

        viewModel.onListToggled(3L, true)
        advanceUntilIdle()
        assertTrue(repository.bulkCalls.isEmpty())
        assertTrue((10L to 3L) in repository.membership.value)
    }
}
