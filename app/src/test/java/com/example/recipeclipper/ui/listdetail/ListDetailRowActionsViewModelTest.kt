package com.example.recipeclipper.ui.listdetail

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A list's detail: the long-press menu and selecting several recipes (#376, #377). */
@OptIn(ExperimentalCoroutinesApi::class)
class ListDetailRowActionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val weeknights = RecipeList(2, "Weeknights", isBuiltIn = false, isFavorites = false, recipeCount = 3)

    private fun summary(id: Long, title: String = "Recipe $id") =
        RecipeSummary(id = id, title = title, imageUrl = null, totalTime = null, lastViewedAt = id, isSaved = true)

    private fun entity(id: Long, title: String) = RecipeEntity(
        id = id, sourceUrl = "https://example.com/$id", title = title, imageUrl = null,
        ingredients = listOf("1 egg"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, servings = null, sourceType = "BLOG", lastViewedAt = 0L
    )

    private val a = summary(1, "Adobo")
    private val b = summary(2, "Bread")
    private val c = summary(3, "Carbonara")

    private class Setup(val lists: FakeListRepository, val recipes: FakeRecipeRepository, val vm: ListDetailViewModel)

    private fun TestScope.setup(): Setup {
        val lists = FakeListRepository().apply {
            this.lists.value = listOf(weeknights)
            membership.value = setOf(1L to 2L, 2L to 2L, 3L to 2L, 4L to 2L)
            recipesIn.value = listOf(a, b, c)
        }
        val recipes = FakeRecipeRepository()
        listOf(a, b, c).forEach { recipes.deleteResults[it.id] = RecipeRepository.DeletedRecipe(entity(it.id, it.title), emptyList()) }
        val vm = ListDetailViewModel(
            lists, SavedStateHandle(mapOf(ListDetailViewModel.LIST_ID_ARG to 2L)), recipes, FakeAppPreferences()
        )
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        return Setup(lists, recipes, vm)
    }

    @Test fun `selecting works as on Recipes, from a row or from the menu`() = runTest(mainDispatcherRule.dispatcher) {
        val s = setup()
        s.vm.onStartSelecting(b)
        advanceUntilIdle()
        assertTrue(s.vm.uiState.value.selecting)
        assertEquals(setOf(2L), s.vm.uiState.value.selectedIds)

        s.vm.onToggleSelected(3)
        advanceUntilIdle()
        assertEquals(listOf(b, c), s.vm.uiState.value.selected)

        s.vm.onExitSelection()
        advanceUntilIdle()
        assertFalse(s.vm.uiState.value.selecting)
    }

    @Test fun `Remove from this list takes the ticked recipes off in one write, and deletes none`() =
        runTest(mainDispatcherRule.dispatcher) {
            val s = setup()
            s.vm.onStartSelecting(a)
            s.vm.onToggleSelected(3)
            advanceUntilIdle()

            s.vm.onRemoveSelectedFromList()
            advanceUntilIdle()

            assertEquals(listOf(Triple(listOf(1L, 3L), 2L, false)), s.lists.bulkCalls)
            assertEquals(setOf(2L to 2L, 4L to 2L), s.lists.membership.value)
            assertTrue("leaving a list is not a deletion", s.recipes.deleteAllCalls.isEmpty())
            assertFalse("selecting is over", s.vm.uiState.value.selecting)
            assertNull(s.vm.uiState.value.deleteBatch)
        }

    @Test fun `a row's Remove from this list takes that recipe off, with no confirmation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val s = setup()

            s.vm.onRemoveFromList(listOf(2L))
            advanceUntilIdle()

            assertEquals(listOf(Triple(listOf(2L), 2L, false)), s.lists.bulkCalls)
            assertTrue(s.vm.uiState.value.confirmingDelete.isEmpty())
        }

    @Test fun `Delete asks once for everything ticked, deletes in one transaction, and one Undo restores all`() =
        runTest(mainDispatcherRule.dispatcher) {
            val s = setup()
            s.vm.onStartSelecting(a)
            s.vm.onToggleSelected(2)
            advanceUntilIdle()

            s.vm.onRequestDeleteSelected()
            advanceUntilIdle()
            assertEquals(listOf(a, b), s.vm.uiState.value.confirmingDelete)

            s.vm.onConfirmDelete()
            advanceUntilIdle()
            assertEquals(listOf(listOf(1L, 2L)), s.recipes.deleteAllCalls)
            assertEquals(listOf("Adobo", "Bread"), s.vm.uiState.value.pendingDeletes)
            assertNotNull(s.vm.uiState.value.deleteBatch)
            assertFalse(s.vm.uiState.value.selecting)

            s.vm.onUndoDelete()
            advanceUntilIdle()
            assertEquals(listOf(listOf(1L, 2L)), s.recipes.restoreAllCalls.map { burst -> burst.map { it.entity.id } })
        }

    @Test fun `the free tier's refusal of an undo restores none and says so (#345)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val s = setup()
            s.recipes.restoreOutcome = RecipeRepository.RestoreOutcome.LIBRARY_FULL
            s.vm.onRequestDelete(listOf(a, b))
            s.vm.onConfirmDelete()
            advanceUntilIdle()

            s.vm.onUndoDelete()
            advanceUntilIdle()

            assertTrue(s.recipes.restoreCalls.isEmpty())
            assertTrue(s.vm.uiState.value.restoreRefused)
            s.vm.onRestoreRefusedShown()
            advanceUntilIdle()
            assertFalse(s.vm.uiState.value.restoreRefused)
        }

    @Test fun `Cancel leaves the recipes alone`() = runTest(mainDispatcherRule.dispatcher) {
        val s = setup()
        s.vm.onRequestDelete(listOf(c))
        advanceUntilIdle()
        assertEquals(listOf(c), s.vm.uiState.value.confirmingDelete)

        s.vm.onCancelDelete()
        advanceUntilIdle()
        assertTrue(s.vm.uiState.value.confirmingDelete.isEmpty())
        assertTrue(s.recipes.deleteAllCalls.isEmpty())
    }

    private fun assertNull(value: Any?) = org.junit.Assert.assertNull(value)
}
