package com.example.recipeclipper.ui.recipes

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeLibraryPolicy
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
class RecipesViewModelTest {

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
            val vm = RecipesViewModel(repository, FakeAppPreferences())
            collectEagerly(vm.uiState)

            vm.onQueryChange("a")
            vm.onQueryChange("ab")
            vm.onQueryChange("abc")
            advanceUntilIdle()

            assertEquals(listOf("abc"), repository.historyQueries)
        }

    @Test fun `the free tier shows the count of every recipe against 20 (#107)`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { count.value = 12 }
        val library = FakeLibraryPolicy(LibraryLimit.Free(20))
        val vm = RecipesViewModel(repository, FakeAppPreferences(), library)
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        assertEquals(LibraryCount(12, 20), vm.uiState.value.count)

        repository.count.value = 50 // a library from before the limit
        advanceUntilIdle()
        assertTrue(vm.uiState.value.count!!.over)

        library.limit = LibraryLimit.Unlimited
        advanceUntilIdle()
        assertNull(vm.uiState.value.count)
    }

    @Test fun `with the free tier off there is no count`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = RecipesViewModel(FakeRecipeRepository(), FakeAppPreferences())
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        assertNull(vm.uiState.value.count)
    }

    @Test fun `recipes stays null until the repository answers`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        repository.history.value = listOf(summary(1))
        val vm = RecipesViewModel(repository, FakeAppPreferences())

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
        val vm = RecipesViewModel(repository, FakeAppPreferences())
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
            val vm = RecipesViewModel(repository, FakeAppPreferences())
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
            val vm = RecipesViewModel(repository, FakeAppPreferences())
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
            val vm = RecipesViewModel(repository, FakeAppPreferences())
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

    @Test fun `recently viewed is the default order, as the repository gives it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            repository.history.value = listOf(summary(2, "Bread"), summary(3, "apple pie"), summary(1, "Cake"))
            val vm = RecipesViewModel(repository, FakeAppPreferences())
            collectEagerly(vm.uiState)
            advanceUntilIdle()

            assertEquals(RecipeSort.RECENTLY_VIEWED, vm.uiState.value.sort)
            assertEquals(listOf(2L, 3L, 1L), vm.uiState.value.recipes?.map { it.id })
        }

    @Test fun `name sorts by title ignoring case, and date added is newest id first`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            repository.history.value = listOf(summary(2, "Bread"), summary(3, "apple pie"), summary(1, "Cake"))
            val vm = RecipesViewModel(repository, FakeAppPreferences())
            collectEagerly(vm.uiState)

            vm.onSortChange(RecipeSort.NAME)
            advanceUntilIdle()
            assertEquals(listOf("apple pie", "Bread", "Cake"), vm.uiState.value.recipes?.map { it.title })

            vm.onSortChange(RecipeSort.DATE_ADDED)
            advanceUntilIdle()
            assertEquals(listOf(3L, 2L, 1L), vm.uiState.value.recipes?.map { it.id })
        }

    @Test fun `the stored sort is applied on open, and a new choice is stored`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            repository.history.value = listOf(summary(2, "Bread"), summary(3, "apple pie"), summary(1, "Cake"))
            val preferences = FakeAppPreferences(recipeSort = RecipeSort.NAME)
            val vm = RecipesViewModel(repository, preferences)
            collectEagerly(vm.uiState)
            advanceUntilIdle()

            assertEquals(RecipeSort.NAME, vm.uiState.value.sort)
            assertEquals(listOf("apple pie", "Bread", "Cake"), vm.uiState.value.recipes?.map { it.title })

            vm.onSortChange(RecipeSort.DATE_ADDED)
            advanceUntilIdle()
            assertEquals(RecipeSort.DATE_ADDED, preferences.recipeSort)
            assertEquals(listOf(3L, 2L, 1L), vm.uiState.value.recipes?.map { it.id })
        }

    @Test fun `paste a link opens only a real link, and closes the dialog`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = RecipesViewModel(FakeRecipeRepository(), FakeAppPreferences())
            collectEagerly(vm.uiState)

            vm.onPasteLink()
            advanceUntilIdle()
            assertEquals("", vm.uiState.value.linkInput)

            vm.onLinkChange("not a link")
            advanceUntilIdle()
            assertEquals(false, vm.uiState.value.canOpenLink)
            assertNull(vm.onOpenLink())
            assertEquals("not a link", vm.uiState.value.linkInput) // stays open

            vm.onLinkChange("  seriouseats.com/bread ")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.canOpenLink)
            assertEquals("https://seriouseats.com/bread", vm.onOpenLink())
            advanceUntilIdle()
            assertNull(vm.uiState.value.linkInput)
        }

    @Test fun `dismissing the link dialog closes it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = RecipesViewModel(FakeRecipeRepository(), FakeAppPreferences())
        collectEagerly(vm.uiState)

        vm.onPasteLink()
        vm.onLinkDismiss()
        advanceUntilIdle()

        assertNull(vm.uiState.value.linkInput)
    }
}
