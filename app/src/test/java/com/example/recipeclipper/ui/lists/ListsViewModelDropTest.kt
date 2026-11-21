package com.example.recipeclipper.ui.lists

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.fake.FakeListRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * After a drop the database publishes the new order a moment after the write returns (#421):
 * the dropped order must stay on show in that gap instead of snapping back to the old one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelDropTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun list(id: Long, name: String) =
        RecipeList(id = id, name = name, isBuiltIn = true, isFavorites = id == 1L, recipeCount = 0)

    /** The database publishes a write only when told to, as Room does a moment after it. */
    private fun heldRepository() = FakeListRepository().apply {
        lists.value = listOf(list(1, "Favorites"), list(2, "Lunch"), list(3, "Dinner"))
        holdReorderPublication = true
    }

    private fun ListsViewModel.order() = uiState.value.lists.map { it.id }

    @Test
    fun `after a drop the dropped order stays on show until the stored order arrives`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = heldRepository()
            val viewModel = ListsViewModel(repository)
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDragStart(3)
            viewModel.onDragOver(1)
            viewModel.onDragEnd()
            advanceTimeBy(100) // the write has returned; the database has not published yet

            assertEquals(listOf(listOf(3L, 1L, 2L)), repository.reorderCalls)
            assertEquals(null, viewModel.uiState.value.draggingId)
            assertEquals(listOf(3L, 1L, 2L), viewModel.order()) // no snap back to 1, 2, 3

            repository.publishHeldReorder()
            advanceTimeBy(100)
            assertEquals(listOf(3L, 1L, 2L), viewModel.order())

            // The pending state is gone: a later change to the stored order shows as it is.
            repository.reorderLists(listOf(2L, 3L, 1L))
            repository.publishHeldReorder()
            advanceTimeBy(100)
            assertEquals(listOf(2L, 3L, 1L), viewModel.order())
        }

    @Test
    fun `a drop the database never publishes reverts to the stored order after a second`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = ListsViewModel(heldRepository())
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDragStart(3)
            viewModel.onDragOver(1)
            viewModel.onDragEnd()
            advanceTimeBy(900)
            assertEquals(listOf(3L, 1L, 2L), viewModel.order())

            advanceTimeBy(200)
            assertEquals(listOf(1L, 2L, 3L), viewModel.order())
        }

    @Test
    fun `a new drag during the wait is not reverted by the old timeout`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = ListsViewModel(heldRepository())
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDragStart(3)
            viewModel.onDragOver(1)
            viewModel.onDragEnd()
            advanceTimeBy(500)

            viewModel.onDragStart(2)
            advanceTimeBy(2_000)

            assertEquals(2L, viewModel.uiState.value.draggingId)
        }

    @Test
    fun `cancelling a drag clears a drop that is still waiting`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = ListsViewModel(heldRepository())
            collectEagerly(viewModel.uiState)
            advanceUntilIdle()

            viewModel.onDragStart(3)
            viewModel.onDragOver(1)
            viewModel.onDragEnd()
            advanceTimeBy(100)
            viewModel.onDragCancel()
            advanceTimeBy(1)

            assertEquals(listOf(1L, 2L, 3L), viewModel.order())
        }
}
