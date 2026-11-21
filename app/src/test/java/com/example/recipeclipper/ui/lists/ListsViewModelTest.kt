package com.example.recipeclipper.ui.lists

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.coverPhoto
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
    fun `each list's cover photos arrive with it, four at most`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = repositoryWithBuiltIns()
            repository.coverPhotos.value = mapOf(2L to (1..5).map { coverPhoto("https://img/$it.jpg") })
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            val covers = viewModel.uiState.value.coverPhotos
            assertEquals((1..4).map { "https://img/$it.jpg" }, covers[2L]?.map { it.url })
            assertEquals(null, covers[1L]) // no photo: a plain tile
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

    // --- Reordering (#381) ---

    private fun threeLists() = FakeListRepository().apply {
        lists.value = listOf(list(1, "Favorites"), list(2, "Lunch"), list(3, "Dinner"))
    }

    private fun ListsViewModel.order() = uiState.value.lists.map { it.id }

    @Test
    fun `dragging shows the new order at once but stores nothing until the drop`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = threeLists()
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDragStart(3)
            viewModel.onDragOver(1)
            advanceUntilIdle()

            assertEquals(3L, viewModel.uiState.value.draggingId)
            assertEquals(listOf(3L, 1L, 2L), viewModel.order())
            assertTrue(repository.reorderCalls.isEmpty())

            viewModel.onDragEnd()
            advanceUntilIdle()

            assertEquals(listOf(listOf(3L, 1L, 2L)), repository.reorderCalls)
            assertEquals(null, viewModel.uiState.value.draggingId)
            assertEquals(listOf(3L, 1L, 2L), viewModel.order())
        }

    @Test
    fun `a cover dragged over several in turn takes each place, once stored`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = threeLists()
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDragStart(1)
            viewModel.onDragOver(2)
            viewModel.onDragOver(3)
            viewModel.onDragEnd()
            advanceUntilIdle()

            assertEquals(listOf(listOf(2L, 3L, 1L)), repository.reorderCalls)
        }

    @Test
    fun `cancelling a drag puts the covers back and stores nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = threeLists()
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDragStart(2)
            viewModel.onDragOver(3)
            viewModel.onDragCancel()
            advanceUntilIdle()

            assertEquals(listOf(1L, 2L, 3L), viewModel.order())
            assertEquals(null, viewModel.uiState.value.draggingId)
            assertTrue(repository.reorderCalls.isEmpty())
        }

    @Test
    fun `dropping where it started stores nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = threeLists()
        val viewModel = ListsViewModel(repository)
        collectEagerly(viewModel.uiState)
        advanceUntilIdle()

        viewModel.onDragStart(2)
        viewModel.onDragOver(3)
        viewModel.onDragOver(3) // back over the cover it swapped with: where it started
        viewModel.onDragEnd()
        advanceUntilIdle()

        assertTrue(repository.reorderCalls.isEmpty())
        assertEquals(null, viewModel.uiState.value.draggingId)
    }

    @Test
    fun `a drag of nothing, or over nothing, changes nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = threeLists()
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDragOver(2)
            viewModel.onDragStart(99)
            viewModel.onDragEnd()
            viewModel.onDragStart(1)
            viewModel.onDragOver(99)
            advanceUntilIdle()

            assertEquals(listOf(1L, 2L, 3L), viewModel.order())
            assertTrue(repository.reorderCalls.isEmpty())
        }

    @Test
    fun `move earlier and move later store the move at once`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = threeLists()
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onMoveLater(1)
            advanceUntilIdle()
            assertEquals(listOf(2L, 1L, 3L), viewModel.order())

            viewModel.onMoveEarlier(3)
            advanceUntilIdle()
            assertEquals(listOf(2L, 3L, 1L), viewModel.order())
            assertEquals(listOf(listOf(2L, 1L, 3L), listOf(2L, 3L, 1L)), repository.reorderCalls)
        }

    @Test
    fun `the first cannot move earlier nor the last later`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = threeLists()
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onMoveEarlier(1)
            viewModel.onMoveLater(3)
            viewModel.onMoveLater(99)
            advanceUntilIdle()

            assertTrue(repository.reorderCalls.isEmpty())
        }

    @Test
    fun `a list created during a drag goes last and the drag's order still holds`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = threeLists()
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDragStart(3)
            viewModel.onDragOver(1)
            repository.createList("New", null)
            advanceUntilIdle()

            assertEquals(listOf(3L, 1L, 2L, 100L), viewModel.order())
        }
}
