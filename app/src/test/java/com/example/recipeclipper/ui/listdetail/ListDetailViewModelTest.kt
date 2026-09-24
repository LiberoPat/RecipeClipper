package com.example.recipeclipper.ui.listdetail

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeListRepository
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
class ListDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val favorites = RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0)
    private val weeknights = RecipeList(2, "Weeknights", isBuiltIn = false, isFavorites = false, recipeCount = 0)

    /** Seeded, but not Favorites — so deletable. */
    private val lunch = RecipeList(3, "Lunch", isBuiltIn = true, isFavorites = false, recipeCount = 0)

    private fun summary(id: Long, title: String = "Recipe $id") =
        RecipeSummary(id = id, title = title, imageUrl = null, totalTime = null, lastViewedAt = id, isSaved = true)

    private fun repository() = FakeListRepository().apply {
        lists.value = listOf(favorites, weeknights)
    }

    private fun viewModel(repository: FakeListRepository, listId: Long) =
        ListDetailViewModel(repository, SavedStateHandle(mapOf(ListDetailViewModel.LIST_ID_ARG to listId)))

    @Test
    fun `resolves the list named by the navigation argument`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(repository(), listId = 2L)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            assertEquals("Weeknights", viewModel.uiState.value.list?.name)
            assertTrue(viewModel.uiState.value.loaded)
        }

    @Test
    fun `asks the repository for that list's recipes`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repository()
        repository.recipesIn.value = listOf(summary(7), summary(8))
        val viewModel = viewModel(repository, listId = 2L)
        collectEagerly(viewModel.uiState)
        advanceUntilIdle()

        assertEquals(listOf(2L), repository.recipesInQueries)
        assertEquals(listOf(7L, 8L), viewModel.uiState.value.recipes.map { it.id })
    }

    @Test
    fun `an empty list is loaded, not missing`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(repository(), listId = 2L)
        collectEagerly(viewModel.uiState)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loaded)
        assertTrue(viewModel.uiState.value.recipes.isEmpty())
        assertEquals("Weeknights", viewModel.uiState.value.list?.name)
    }

    @Test
    fun `a list id that matches nothing leaves the list null`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(repository(), listId = 99L)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.list)
        }

    @Test
    fun `renaming seeds the field with the current name`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(repository(), listId = 2L)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onStartRenaming()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.renaming)
            assertEquals("Weeknights", viewModel.uiState.value.renameValue)
        }

    @Test
    fun `renaming writes through, trimmed, and closes the dialog`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repository()
            val viewModel = viewModel(repository, listId = 2L)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onStartRenaming()
            viewModel.onRenameValueChange("  Midweek  ")
            viewModel.onRenameConfirm()
            advanceUntilIdle()

            assertEquals(listOf(2L to "Midweek"), repository.renameCalls)
            assertFalse(viewModel.uiState.value.renaming)
            assertEquals("Midweek", viewModel.uiState.value.list?.name)
        }

    @Test
    fun `a blank rename is ignored and the dialog stays open`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repository()
            val viewModel = viewModel(repository, listId = 2L)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onStartRenaming()
            viewModel.onRenameValueChange("   ")
            viewModel.onRenameConfirm()
            advanceUntilIdle()

            assertTrue(repository.renameCalls.isEmpty())
            assertTrue(viewModel.uiState.value.renaming)
        }

    @Test
    fun `a built-in can be renamed`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repository()
        val viewModel = viewModel(repository, listId = 1L)
        collectEagerly(viewModel.uiState)
        advanceUntilIdle()

        viewModel.onStartRenaming()
        viewModel.onRenameValueChange("Best of")
        viewModel.onRenameConfirm()
        advanceUntilIdle()

        assertEquals(listOf(1L to "Best of"), repository.renameCalls)
    }

    @Test
    fun `cancelling a rename clears the field and changes nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repository()
            val viewModel = viewModel(repository, listId = 2L)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onStartRenaming()
            viewModel.onRenameValueChange("Midweek")
            viewModel.onCancelRenaming()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.renaming)
            assertEquals("", viewModel.uiState.value.renameValue)
            assertTrue(repository.renameCalls.isEmpty())
            assertEquals("Weeknights", viewModel.uiState.value.list?.name)
        }

    @Test
    fun `deleting a user list marks the screen deleted`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repository()
            val viewModel = viewModel(repository, listId = 2L)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDelete()
            advanceUntilIdle()

            assertEquals(listOf(2L), repository.deleteCalls)
            assertTrue(viewModel.uiState.value.deleted)
        }

    @Test
    fun `favorites is never deleted`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = repository()
        val viewModel = viewModel(repository, listId = 1L)
        collectEagerly(viewModel.uiState)
        advanceUntilIdle()

        viewModel.onDelete()
        advanceUntilIdle()

        assertTrue(repository.deleteCalls.isEmpty())
        assertFalse(viewModel.uiState.value.deleted)
    }

    /**
     * Lunch, Dinner, Desserts, Breakfast and Snacks are seeded but are starting suggestions,
     * not fixtures. Only Favorites is protected, so `isBuiltIn` must not gate deleting.
     */
    @Test
    fun `a seeded list that is not favorites can be deleted`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeListRepository().apply {
                lists.value = listOf(favorites, lunch)
            }
            val viewModel = viewModel(repository, listId = lunch.id)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDelete()
            advanceUntilIdle()

            assertEquals(listOf(lunch.id), repository.deleteCalls)
            assertTrue(viewModel.uiState.value.deleted)
        }

    @Test
    fun `deleting is refused while the list hasn't resolved`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repository()
            val viewModel = viewModel(repository, listId = 99L)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDelete()
            advanceUntilIdle()

            assertTrue(repository.deleteCalls.isEmpty())
            assertFalse(viewModel.uiState.value.deleted)
        }

    /**
     * The delete path reads the list from the ViewModel's own collector, not from `uiState`,
     * which is a `WhileSubscribed` flow and sits on its initial value with nothing collecting.
     * Reading it there would refuse a delete that should have gone through — this is that case.
     */
    @Test
    fun `deleting works with nothing collecting uiState`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repository()
            val viewModel = viewModel(repository, listId = 2L)
            advanceUntilIdle()

            viewModel.onDelete()
            advanceUntilIdle()

            assertEquals(listOf(2L), repository.deleteCalls)
        }
}
