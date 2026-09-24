package com.example.recipeclipper.ui.history

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun summary(id: Long, title: String = "Recipe $id") =
        RecipeSummary(id = id, title = title, imageUrl = null, totalTime = null, lastViewedAt = id, isSaved = false)

    private fun entity(id: Long, title: String) = RecipeEntity(
        id = id,
        sourceUrl = "https://example.com/$id",
        title = title,
        imageUrl = null,
        ingredients = listOf("1 egg"),
        instructions = listOf("Cook."),
        prepTime = null,
        cookTime = null,
        totalTime = null,
        servings = null,
        sourceType = "BLOG",
        lastViewedAt = 0L
    )

    @Test fun `rapid query changes are debounced into one repository query`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val vm = HistoryViewModel(repository)
            collectEagerly(vm.uiState)

            vm.onQueryChange("a")
            vm.onQueryChange("ab")
            vm.onQueryChange("abc")
            advanceUntilIdle()

            assertEquals(listOf("abc"), repository.historyQueries)
        }

    @Test fun `recipes stays null until the repository answers`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        repository.history.value = listOf(summary(1))
        val vm = HistoryViewModel(repository)

        // Nothing has run on the Main dispatcher yet: still "loading", not "nothing matched".
        assertNull(vm.uiState.value.recipes)

        collectEagerly(vm.uiState)
        advanceUntilIdle()

        assertEquals(listOf(summary(1)), vm.uiState.value.recipes)
    }

    @Test fun `deleting a recipe captures it and sets pendingDeletes`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val deleted = RecipeRepository.DeletedRecipe(entity(1, "Chicken Adobo"), emptyList())
        repository.deleteResults[1L] = deleted
        val vm = HistoryViewModel(repository)
        collectEagerly(vm.uiState)
        advanceUntilIdle()

        vm.onDelete(summary(1, "Chicken Adobo"))
        advanceUntilIdle()

        assertEquals(listOf("Chicken Adobo"), vm.uiState.value.pendingDeletes)
        assertEquals(listOf(1L), repository.deleteCalls)
    }

    @Test fun `undo restores through the repository and clears pendingDeletes`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val deleted = RecipeRepository.DeletedRecipe(entity(1, "Chicken Adobo"), emptyList())
            repository.deleteResults[1L] = deleted
            val vm = HistoryViewModel(repository)
            collectEagerly(vm.uiState)
            advanceUntilIdle()
            vm.onDelete(summary(1, "Chicken Adobo"))
            advanceUntilIdle()

            vm.onUndoDelete()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), vm.uiState.value.pendingDeletes)
            assertEquals(listOf(deleted), repository.restoreCalls)
        }

    @Test fun `dismissing the snackbar clears pendingDeletes without restoring`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val deleted = RecipeRepository.DeletedRecipe(entity(1, "Chicken Adobo"), emptyList())
            repository.deleteResults[1L] = deleted
            val vm = HistoryViewModel(repository)
            collectEagerly(vm.uiState)
            advanceUntilIdle()
            vm.onDelete(summary(1, "Chicken Adobo"))
            advanceUntilIdle()

            vm.onSnackbarDismissed()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), vm.uiState.value.pendingDeletes)
            assertTrue(repository.restoreCalls.isEmpty())
        }

    @Test fun `two deletes in a row do not clobber each other, and both are restored by one undo`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val deletedA = RecipeRepository.DeletedRecipe(entity(1, "A"), emptyList())
            val deletedB = RecipeRepository.DeletedRecipe(entity(2, "B"), emptyList())
            repository.deleteResults[1L] = deletedA
            repository.deleteResults[2L] = deletedB
            val vm = HistoryViewModel(repository)
            collectEagerly(vm.uiState)
            advanceUntilIdle()

            vm.onDelete(summary(1, "A"))
            advanceUntilIdle()
            vm.onDelete(summary(2, "B"))
            advanceUntilIdle()

            // The second swipe, within the same snackbar window, must not strand the first.
            assertEquals(listOf("A", "B"), vm.uiState.value.pendingDeletes)

            vm.onUndoDelete()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), vm.uiState.value.pendingDeletes)
            assertEquals(setOf(deletedA, deletedB), repository.restoreCalls.toSet())
        }
}
