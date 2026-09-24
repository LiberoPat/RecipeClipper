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

@OptIn(ExperimentalCoroutinesApi::class)
class SaveToListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun list(id: Long, name: String, builtIn: Boolean = true) =
        RecipeList(id = id, name = name, isBuiltIn = builtIn, isFavorites = id == 1L, recipeCount = 0)

    private fun repositoryWithBuiltIns() = FakeListRepository().apply {
        lists.value = listOf(
            list(1, "Favorites"),
            list(2, "Lunch"),
            list(3, "Dinner")
        )
    }

    @Test
    fun `no recipe set means no lists to show`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repositoryWithBuiltIns()
        val viewModel = SaveToListViewModel(repository)
        collectEagerly(viewModel.uiState)
        advanceUntilIdle()

        // The bookmark icon must not claim "saved" before it knows which recipe it means.
        assertEquals(emptyList<RecipeList>(), viewModel.uiState.value.lists)
        assertFalse(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `setting the recipe shows every list, none ticked`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repositoryWithBuiltIns()
        val viewModel = SaveToListViewModel(repository)
        collectEagerly(viewModel.uiState)
        viewModel.setRecipe(7L)
        advanceUntilIdle()

        assertEquals(listOf("Favorites", "Lunch", "Dinner"), viewModel.uiState.value.lists.map { it.name })
        assertTrue(viewModel.uiState.value.lists.none { it.containsRecipe })
        assertFalse(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `ticking a list writes it through and the tick comes back`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repositoryWithBuiltIns()
            val viewModel = SaveToListViewModel(repository)
            collectEagerly(viewModel.uiState)
            viewModel.setRecipe(7L)
            advanceUntilIdle()

            viewModel.onListToggled(listId = 2L, inList = true)
            advanceUntilIdle()

            assertTrue(setOf(7L to 2L) == repository.membership.value)
            val lunch = viewModel.uiState.value.lists.single { it.id == 2L }
            assertTrue(lunch.containsRecipe)
            // The count is derived from membership, as it is in SQL.
            assertEquals(1, lunch.recipeCount)
        }

    @Test
    fun `unticking removes the membership`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repositoryWithBuiltIns()
        repository.membership.value = setOf(7L to 2L)
        val viewModel = SaveToListViewModel(repository)
        collectEagerly(viewModel.uiState)
        viewModel.setRecipe(7L)
        advanceUntilIdle()

        viewModel.onListToggled(listId = 2L, inList = false)
        advanceUntilIdle()

        assertTrue(repository.membership.value.isEmpty())
        assertFalse(viewModel.uiState.value.lists.single { it.id == 2L }.containsRecipe)
    }

    @Test
    fun `saved is true while the recipe is in any list at all`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repositoryWithBuiltIns()
            val viewModel = SaveToListViewModel(repository)
            collectEagerly(viewModel.uiState)
            viewModel.setRecipe(7L)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSaved)

            viewModel.onListToggled(listId = 3L, inList = true)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSaved)

            viewModel.onListToggled(listId = 3L, inList = false)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSaved)
        }

    @Test
    fun `another recipe's membership does not tick this one`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repositoryWithBuiltIns()
            repository.membership.value = setOf(99L to 2L)
            val viewModel = SaveToListViewModel(repository)
            collectEagerly(viewModel.uiState)
            viewModel.setRecipe(7L)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isSaved)
            // The list still shows the other recipe in its count; it just isn't ticked here.
            assertEquals(1, viewModel.uiState.value.lists.single { it.id == 2L }.recipeCount)
        }

    @Test
    fun `toggling before a recipe is set is ignored rather than crashing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repositoryWithBuiltIns()
            val viewModel = SaveToListViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onListToggled(listId = 2L, inList = true)
            advanceUntilIdle()

            assertTrue(repository.membership.value.isEmpty())
        }

    @Test
    fun `creating a list from the sheet puts the current recipe in it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repositoryWithBuiltIns()
            val viewModel = SaveToListViewModel(repository)
            collectEagerly(viewModel.uiState)
            viewModel.setRecipe(7L)
            advanceUntilIdle()

            viewModel.onStartCreating()
            viewModel.onNewListNameChange("Weeknights")
            viewModel.onCreateList()
            advanceUntilIdle()

            assertEquals(listOf("Weeknights" to 7L), repository.createCalls)
            val created = viewModel.uiState.value.lists.single { it.name == "Weeknights" }
            assertTrue(created.containsRecipe)
            assertTrue(viewModel.uiState.value.isSaved)
        }

    @Test
    fun `creating closes the field and clears what was typed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repositoryWithBuiltIns()
            val viewModel = SaveToListViewModel(repository)
            collectEagerly(viewModel.uiState)
            viewModel.setRecipe(7L)
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
    fun `a blank name creates nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repositoryWithBuiltIns()
        val viewModel = SaveToListViewModel(repository)
        collectEagerly(viewModel.uiState)
        viewModel.setRecipe(7L)
        viewModel.onStartCreating()
        viewModel.onNewListNameChange("   ")
        viewModel.onCreateList()
        advanceUntilIdle()

        assertTrue(repository.createCalls.isEmpty())
        // The field stays open, so what was typed isn't silently thrown away.
        assertTrue(viewModel.uiState.value.creatingList)
    }

    @Test
    fun `the created name is trimmed`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repositoryWithBuiltIns()
        val viewModel = SaveToListViewModel(repository)
        collectEagerly(viewModel.uiState)
        viewModel.setRecipe(7L)
        viewModel.onStartCreating()
        viewModel.onNewListNameChange("  Weeknights  ")
        viewModel.onCreateList()
        advanceUntilIdle()

        assertEquals(listOf("Weeknights" to 7L), repository.createCalls)
    }

    @Test
    fun `cancelling creation clears the field`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repositoryWithBuiltIns()
        val viewModel = SaveToListViewModel(repository)
        collectEagerly(viewModel.uiState)
        viewModel.onStartCreating()
        viewModel.onNewListNameChange("Weeknights")
        advanceUntilIdle()

        viewModel.onCancelCreating()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.creatingList)
        assertEquals("", viewModel.uiState.value.newListName)
        assertTrue(repository.createCalls.isEmpty())
    }
}
