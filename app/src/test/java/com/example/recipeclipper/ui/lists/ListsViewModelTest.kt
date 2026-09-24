package com.example.recipeclipper.ui.lists

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

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun list(id: Long, name: String) =
        RecipeList(id = id, name = name, isBuiltIn = true, isFavorites = id == 1L, recipeCount = 0)

    private fun repositoryWithBuiltIns() = FakeListRepository().apply {
        lists.value = listOf(list(1, "Favorites"), list(2, "Lunch"))
    }

    @Test
    fun `starts unloaded so the screen doesn't flash an empty list`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = ListsViewModel(repositoryWithBuiltIns())
            assertFalse(viewModel.uiState.value.loaded)
        }

    @Test
    fun `lists arrive with their counts`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repositoryWithBuiltIns()
        repository.membership.value = setOf(7L to 2L, 8L to 2L)
        val viewModel = ListsViewModel(repository)
        collectEagerly(viewModel.uiState)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loaded)
        assertEquals(listOf("Favorites", "Lunch"), viewModel.uiState.value.lists.map { it.name })
        assertEquals(0, viewModel.uiState.value.lists.single { it.id == 1L }.recipeCount)
        assertEquals(2, viewModel.uiState.value.lists.single { it.id == 2L }.recipeCount)
    }

    @Test
    fun `nothing here is ticked, since no recipe is in hand`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repositoryWithBuiltIns()
            repository.membership.value = setOf(7L to 2L)
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.lists.none { it.containsRecipe })
        }

    @Test
    fun `creating a list from here leaves it empty`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repositoryWithBuiltIns()
        val viewModel = ListsViewModel(repository)
        collectEagerly(viewModel.uiState)
        viewModel.onStartCreating()
        viewModel.onNewListNameChange("Weeknights")
        viewModel.onCreateList()
        advanceUntilIdle()

        assertEquals(listOf("Weeknights" to null), repository.createCalls)
        assertEquals(0, viewModel.uiState.value.lists.single { it.name == "Weeknights" }.recipeCount)
    }

    @Test
    fun `creating closes the field and clears what was typed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = ListsViewModel(repositoryWithBuiltIns())
            collectEagerly(viewModel.uiState)
            viewModel.onStartCreating()
            viewModel.onNewListNameChange("Weeknights")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.creatingList)

            viewModel.onCreateList()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.creatingList)
            assertEquals("", viewModel.uiState.value.newListName)
        }

    @Test
    fun `a blank name creates nothing and keeps the field open`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repositoryWithBuiltIns()
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            viewModel.onStartCreating()
            viewModel.onNewListNameChange(" ")
            viewModel.onCreateList()
            advanceUntilIdle()

            assertTrue(repository.createCalls.isEmpty())
            assertTrue(viewModel.uiState.value.creatingList)
        }

    @Test
    fun `cancelling clears the field`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repositoryWithBuiltIns()
        val viewModel = ListsViewModel(repository)
        collectEagerly(viewModel.uiState)
        viewModel.onStartCreating()
        viewModel.onNewListNameChange("Weeknights")
        viewModel.onCancelCreating()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.creatingList)
        assertEquals("", viewModel.uiState.value.newListName)
        assertTrue(repository.createCalls.isEmpty())
    }
}
