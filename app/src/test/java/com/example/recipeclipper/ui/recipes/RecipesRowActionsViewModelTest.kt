package com.example.recipeclipper.ui.recipes

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeShareText
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The long-press menu and selecting several recipes (#376, #377), as the Recipes ViewModel serves them. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipesRowActionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun summary(id: Long, title: String = "Recipe $id") =
        RecipeSummary(id = id, title = title, imageUrl = null, totalTime = null, lastViewedAt = id, isSaved = false)

    private fun entity(id: Long, title: String) = RecipeEntity(
        id = id, sourceUrl = "https://example.com/$id", title = title, imageUrl = null,
        ingredients = listOf("1 egg"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, servings = null, sourceType = "BLOG", lastViewedAt = 0L
    )

    private val a = summary(1, "Adobo")
    private val b = summary(2, "Bread")
    private val c = summary(3, "Carbonara")

    private data class Setup(val repository: FakeRecipeRepository, val vm: RecipesViewModel)

    private fun TestScope.setup(vararg rows: RecipeSummary, preferences: FakeAppPreferences = FakeAppPreferences()): Setup {
        val repository = FakeRecipeRepository()
        repository.history.value = rows.toList()
        rows.forEach { repository.deleteResults[it.id] = RecipeRepository.DeletedRecipe(entity(it.id, it.title), emptyList()) }
        val vm = RecipesViewModel(repository, preferences)
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        return Setup(repository, vm)
    }

    // --- Selecting (#377) ---

    @Test fun `a row's Select starts selecting with that row ticked, the top bar's with none`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (_, vm) = setup(a, b)
            assertFalse(vm.uiState.value.selecting)

            vm.onStartSelecting(b)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.selecting)
            assertEquals(setOf(2L), vm.uiState.value.selectedIds)

            vm.onExitSelection()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.selecting)
            assertEquals(emptySet<Long>(), vm.uiState.value.selectedIds)

            vm.onStartSelecting()
            advanceUntilIdle()
            assertTrue("selecting with nothing ticked is a state", vm.uiState.value.selecting)
            assertEquals(emptySet<Long>(), vm.uiState.value.selectedIds)
        }

    @Test fun `a tap ticks and unticks, and does nothing while not selecting`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (_, vm) = setup(a, b, c)
            vm.onToggleSelected(1)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.selecting)

            vm.onStartSelecting()
            vm.onToggleSelected(1)
            vm.onToggleSelected(3)
            advanceUntilIdle()
            assertEquals(setOf(1L, 3L), vm.uiState.value.selectedIds)
            assertEquals(listOf(a, c), vm.uiState.value.selected)

            vm.onToggleSelected(1)
            advanceUntilIdle()
            assertEquals(setOf(3L), vm.uiState.value.selectedIds)
        }

    @Test fun `what a search hides is not selected, and comes back with the rows`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (repository, vm) = setup(a, b, c)
            vm.onStartSelecting(a)
            vm.onToggleSelected(2)
            advanceUntilIdle()
            assertEquals(setOf(1L, 2L), vm.uiState.value.selectedIds)

            // A search narrows the rows to Bread: Adobo is ticked but out of sight, so nothing acts on it.
            repository.history.value = listOf(b)
            advanceUntilIdle()
            assertEquals(setOf(2L), vm.uiState.value.selectedIds)
            vm.onRequestDeleteSelected()
            advanceUntilIdle()
            assertEquals(listOf(b), vm.uiState.value.confirmingDelete)
            vm.onCancelDelete()

            repository.history.value = listOf(a, b, c)
            advanceUntilIdle()
            assertEquals(setOf(1L, 2L), vm.uiState.value.selectedIds)
        }

    // --- Deleting: one confirmation, one transaction, one undo ---

    @Test fun `a row's Delete asks first, and Cancel deletes nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (repository, vm) = setup(a, b)

            vm.onRequestDelete(listOf(a))
            advanceUntilIdle()
            assertEquals(listOf(a), vm.uiState.value.confirmingDelete)
            assertTrue(repository.deleteAllCalls.isEmpty())

            vm.onCancelDelete()
            advanceUntilIdle()
            assertEquals(emptyList<RecipeSummary>(), vm.uiState.value.confirmingDelete)
            assertTrue(repository.deleteAllCalls.isEmpty())
            assertNull(vm.uiState.value.deleteBatch)
        }

    @Test fun `confirming a row's Delete deletes it and offers one Undo naming it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (repository, vm) = setup(a, b)

            vm.onRequestDelete(listOf(a))
            vm.onConfirmDelete()
            advanceUntilIdle()

            assertEquals(listOf(listOf(1L)), repository.deleteAllCalls)
            assertEquals(listOf("Adobo"), vm.uiState.value.pendingDeletes)
            assertNotNull(vm.uiState.value.deleteBatch)
            assertEquals(emptyList<RecipeSummary>(), vm.uiState.value.confirmingDelete)
        }

    @Test fun `a selection's Delete is one confirmation, one transaction and one Undo`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (repository, vm) = setup(a, b, c)
            vm.onStartSelecting(a)
            vm.onToggleSelected(3)
            advanceUntilIdle()

            vm.onRequestDeleteSelected()
            advanceUntilIdle()
            assertEquals("one confirmation for both", listOf(a, c), vm.uiState.value.confirmingDelete)

            vm.onConfirmDelete()
            advanceUntilIdle()

            assertEquals("one transaction", listOf(listOf(1L, 3L)), repository.deleteAllCalls)
            assertEquals(listOf("Adobo", "Carbonara"), vm.uiState.value.pendingDeletes)
            val batch = vm.uiState.value.deleteBatch
            assertNotNull(batch)
            assertFalse("selecting is over", vm.uiState.value.selecting)

            vm.onUndoDelete()
            advanceUntilIdle()

            assertEquals(1, repository.restoreAllCalls.size)
            assertEquals(listOf(1L, 3L), repository.restoreAllCalls.single().map { it.entity.id })
            assertEquals(emptyList<String>(), vm.uiState.value.pendingDeletes)
            assertNull(vm.uiState.value.deleteBatch)
            assertFalse(vm.uiState.value.restoreRefused)
        }

    @Test fun `a selection's undo the full free library refuses restores none and says so (#345)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (repository, vm) = setup(a, b)
            repository.restoreOutcome = RecipeRepository.RestoreOutcome.LIBRARY_FULL
            vm.onStartSelecting(a)
            vm.onToggleSelected(2)
            advanceUntilIdle()
            vm.onRequestDeleteSelected()
            vm.onConfirmDelete()
            advanceUntilIdle()

            vm.onUndoDelete()
            advanceUntilIdle()

            assertEquals("asked for as one, refused as one", 1, repository.restoreAllCalls.size)
            assertTrue(repository.restoreCalls.isEmpty())
            assertTrue(vm.uiState.value.restoreRefused)
            // The deletes stand, so what only an undo needed (the photo files) goes.
            assertEquals(2, repository.forgetCalls.size)
        }

    @Test fun `a swipe and a selection's Delete in one burst share one Undo`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (repository, vm) = setup(a, b, c)
            vm.onDelete(c)
            advanceUntilIdle()
            val batch = vm.uiState.value.deleteBatch

            vm.onStartSelecting(a)
            vm.onToggleSelected(2)
            vm.onRequestDeleteSelected()
            vm.onConfirmDelete()
            advanceUntilIdle()

            assertEquals(batch, vm.uiState.value.deleteBatch)
            assertEquals(listOf("Carbonara", "Adobo", "Bread"), vm.uiState.value.pendingDeletes)
            vm.onUndoDelete()
            advanceUntilIdle()
            assertEquals(listOf(3L, 1L, 2L), repository.restoreAllCalls.single().map { it.entity.id })
        }

    @Test fun `deleting with nothing ticked asks nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (_, vm) = setup(a, b)
            vm.onStartSelecting()
            vm.onRequestDeleteSelected()
            advanceUntilIdle()
            assertEquals(emptyList<RecipeSummary>(), vm.uiState.value.confirmingDelete)
        }

    // --- Share and Add to plan: from the recipe, not opening it ---

    private fun pancakes(id: Long) = Recipe(
        name = "Pancakes", image = null, ingredients = listOf("1 cup milk", "2 eggs"),
        instructions = listOf("Mix.", "Fry."), prepTime = null, cookTime = null, totalTime = null,
        yield = "4 servings", sourceUrl = "https://example.com/pancakes", id = id, servingsTarget = 8
    )

    @Test fun `Share sends the recipe as the reading view would, at the saved servings and the user's units`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (repository, vm) = setup(a, preferences = FakeAppPreferences(unitSystem = UnitSystem.METRIC))
            repository.recipes.value = mapOf(1L to pancakes(1))
            var shared: RowShare? = null

            vm.onShare(1, RecipeShareText.Labels.ENGLISH) { shared = it }
            advanceUntilIdle()

            val share = shared!!
            assertEquals("Pancakes", share.title)
            val expected = """
                Pancakes

                Serves 8 (originally 4)

                INGREDIENTS
                480 ml milk
                4 eggs

                INSTRUCTIONS
                1. Mix.
                2. Fry.
            """.trimIndent()
            assertEquals(expected, share.text)
            assertFalse("no link back to the source", share.text.contains("example.com"))
            assertEquals("read by id, never opened (that would count as a view)", listOf(listOf(1L)), repository.byIdsQueries)
        }

    @Test fun `Share of a recipe that is gone does nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (_, vm) = setup(a)
            var shared: RowShare? = null
            vm.onShare(99, RecipeShareText.Labels.ENGLISH) { shared = it }
            advanceUntilIdle()
            assertNull(shared)
        }

    @Test fun `Add to plan starts on the recipe's own yield, null when it has no number`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (repository, vm) = setup(a, b)
            repository.recipes.value = mapOf(
                1L to pancakes(1),
                2L to pancakes(2).copy(yield = "a loaf")
            )
            var first: Int? = -1
            var second: Int? = -1

            vm.onAddToPlan(1) { first = it }
            vm.onAddToPlan(2) { second = it }
            advanceUntilIdle()

            assertEquals("the yield, not the saved 8", 4, first)
            assertNull(second)
        }
}
