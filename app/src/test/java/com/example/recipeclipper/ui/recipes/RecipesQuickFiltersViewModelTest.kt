package com.example.recipeclipper.ui.recipes

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryCoverage
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeQuickFilter
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.RecipesLayout
import com.example.recipeclipper.data.model.SavedRecipe
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * The chips under Recipes' search (#378) and the layout toggle (#380), as the ViewModel serves
 * them. The matching itself is `RecipeQuickFilters`, covered in `:core` and pinned for iOS by
 * the differential corpus.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipesQuickFiltersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun summary(id: Long, total: String? = null, cooked: Long? = null, title: String = "Recipe $id") =
        RecipeSummary(id, title, null, total, id, isSaved = false, lastCookedDay = cooked, language = "en")

    private fun item(name: String, inStock: Boolean = true, id: Long = name.hashCode().toLong()) =
        PantryItem(id, name, null, "en", Aisle.OTHER, inStock, alwaysHave = false, purchasedDay = null, expiresDay = null)

    /**
     * Where the pantry is matched: counts each dispatch (one matching of every recipe), and can hold
     * them, to see what the screen shows while the answer is still on its way.
     */
    private class MatchingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0
        var held = false
        private val waiting = mutableListOf<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            if (held) waiting += context to block else inner.dispatch(context, block)
        }

        fun release() {
            held = false
            waiting.forEach { (context, block) -> inner.dispatch(context, block) }
            waiting.clear()
        }
    }

    private val matching = MatchingDispatcher(mainDispatcherRule.dispatcher)

    private fun viewModel(
        repository: FakeRecipeRepository,
        preferences: FakeAppPreferences = FakeAppPreferences(),
        pantry: FakePantryRepository? = null,
        handle: SavedStateHandle? = null
    ) = RecipesViewModel(
        repository, preferences, savedStateHandle = handle, pantry = pantry, matching = matching
    )

    @Test fun `the chips combine with each other and the sort, and turn off again`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            history.value = listOf(
                summary(1, total = "20m", title = "Zucchini Fritters"),
                summary(2, total = "25m", cooked = 20_000, title = "Apple Tart"),
                summary(3, total = "2h", title = "Brisket"),
                summary(4, total = "10m", title = "Miso Soup"),
                summary(5, total = "Overnight", title = "Cured Salmon")
            )
        }
        val vm = viewModel(repository, FakeAppPreferences(recipeSort = RecipeSort.NAME))
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        assertEquals(listOf(2L, 3L, 5L, 4L, 1L), vm.uiState.value.recipes!!.map { it.id })

        vm.onQuickFilterToggle(RecipeQuickFilter.UNDER_30)
        advanceUntilIdle()
        // Under 30 reads the stored time; "Overnight" never matches; the sort still applies.
        assertEquals(listOf("Apple Tart", "Miso Soup", "Zucchini Fritters"), vm.uiState.value.recipes!!.map { it.title })

        vm.onQuickFilterToggle(RecipeQuickFilter.NOT_COOKED)
        advanceUntilIdle()
        assertEquals(listOf("Miso Soup", "Zucchini Fritters"), vm.uiState.value.recipes!!.map { it.title })
        assertEquals(2, vm.uiState.value.listed)

        vm.onQuickFilterToggle(RecipeQuickFilter.UNDER_30)
        vm.onQuickFilterToggle(RecipeQuickFilter.NOT_COOKED)
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.recipes!!.size)
        assertTrue(vm.uiState.value.quick.isEmpty())
    }

    @Test fun `a chip that leaves nothing keeps the line and the chips`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { history.value = listOf(summary(1, total = "2h")) }
        val vm = viewModel(repository)
        collectEagerly(vm.uiState)
        advanceUntilIdle()

        vm.onQuickFilterToggle(RecipeQuickFilter.UNDER_30)
        advanceUntilIdle()
        assertEquals(emptyList<RecipeSummary>(), vm.uiState.value.recipes)
        assertTrue("the count and the toggle stay while a chip is on", vm.uiState.value.showsSortLine)
        assertTrue("so do the chips, to turn it off", vm.uiState.value.showsFilter)
    }

    @Test fun `what the database answers for a search is narrowed by the chip too`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { history.value = listOf(summary(1, total = "20m"), summary(2, total = "3h")) }
        val vm = viewModel(repository)
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        vm.onQuickFilterToggle(RecipeQuickFilter.UNDER_30)

        vm.onQueryChange("soup")
        repository.history.value = listOf(summary(2, total = "3h"), summary(3, total = "15m"))
        advanceUntilIdle()
        assertEquals(listOf(3L), vm.uiState.value.recipes!!.map { it.id })
        assertEquals("soup", repository.historyQueries.last())
    }

    @Test fun `Not cooked yet is offered nowhere inside the Cooked view`() = runTest(mainDispatcherRule.dispatcher) {
        val inCooked = viewModel(FakeRecipeRepository(), handle = SavedStateHandle(mapOf(RecipesViewModel.VIEW_ARG to RecipeFilter.COOKED.name)))
        collectEagerly(inCooked.uiState)
        advanceUntilIdle()
        assertEquals(listOf(RecipeQuickFilter.UNDER_30, RecipeQuickFilter.USES_PANTRY), inCooked.uiState.value.quickFilters)

        val whole = viewModel(FakeRecipeRepository())
        collectEagerly(whole.uiState)
        advanceUntilIdle()
        assertEquals(RecipeQuickFilter.entries, whole.uiState.value.quickFilters)
    }

    // --- Uses what's in my pantry ---

    @Test fun `the pantry chip reads every recipe's lines only while it is on`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            history.value = listOf(summary(1), summary(2), summary(3))
            recipeLines.value = listOf(
                SavedRecipe(1, "Pasta", "en", listOf("200 g pasta", "1 cup milk")),
                SavedRecipe(2, "Cake", "en", listOf("2 cups flour", "1 cup sugar", "3 eggs")),
                SavedRecipe(3, "Soup", "en", listOf("For the broth:", "1 onion", "2 carrots"))
            )
        }
        val pantry = FakePantryRepository(listOf(item("pasta"), item("milk", inStock = false), item("onion")))
        val vm = viewModel(repository, pantry = pantry)
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        assertEquals("nothing is read while the chip is off", 0, repository.recipeLinesCollections)
        assertTrue(vm.uiState.value.coverage.isEmpty())

        vm.onQuickFilterToggle(RecipeQuickFilter.USES_PANTRY)
        advanceUntilIdle()
        assertEquals(1, repository.recipeLinesCollections)
        // Half is enough (the pasta of pasta and milk; the onion of onion and carrots); the cake has none.
        assertEquals(listOf(1L, 3L), vm.uiState.value.recipes!!.map { it.id })
        assertEquals(PantryCoverage(1, 2), vm.uiState.value.coverage[1L])
        assertEquals(PantryCoverage(1, 2), vm.uiState.value.coverage[3L])

        vm.onQuickFilterToggle(RecipeQuickFilter.USES_PANTRY)
        advanceUntilIdle()
        assertEquals("off again, every recipe", 3, vm.uiState.value.recipes!!.size)
        assertTrue("and no 'You have' lines", vm.uiState.value.coverage.isEmpty())
    }

    @Test fun `nothing is shown while the pantry chip's answer is on its way`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { history.value = listOf(summary(1)) }
        val vm = viewModel(repository, pantry = FakePantryRepository())
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.recipes!!.size)

        matching.held = true
        vm.onQuickFilterToggle(RecipeQuickFilter.USES_PANTRY)
        advanceUntilIdle()
        // Neither "no recipes" nor every recipe is claimed until it is matched.
        assertNull(vm.uiState.value.recipes)
        matching.release()
        advanceUntilIdle()
        assertEquals(emptyList<RecipeSummary>(), vm.uiState.value.recipes)
    }

    @Test fun `the pantry is matched once per change, not once per tick or collector`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            history.value = listOf(summary(1))
            recipeLines.value = listOf(SavedRecipe(1, "Pasta", "en", listOf("200 g pasta")))
        }
        val pantry = FakePantryRepository(listOf(item("pasta")))
        val vm = viewModel(repository, pantry = pantry)
        collectEagerly(vm.uiState)
        vm.onQuickFilterToggle(RecipeQuickFilter.USES_PANTRY)
        advanceUntilIdle()
        assertEquals(1, matching.dispatches)

        // A tick or a cook step rewrites a recipe without changing a line: the same lines again.
        repository.recipeLines.value = listOf(SavedRecipe(1, "Pasta", "en", listOf("200 g pasta")))
        advanceUntilIdle()
        assertEquals(1, matching.dispatches)

        pantry.items.value = pantry.items.value + item("rice")
        advanceUntilIdle()
        assertEquals(2, matching.dispatches)
    }

    @Test fun `the pantry follows what is stocked, live`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            history.value = listOf(summary(1))
            recipeLines.value = listOf(SavedRecipe(1, "Pasta", "en", listOf("200 g pasta")))
        }
        val pantry = FakePantryRepository(listOf(item("pasta")))
        val vm = viewModel(repository, pantry = pantry)
        collectEagerly(vm.uiState)
        vm.onQuickFilterToggle(RecipeQuickFilter.USES_PANTRY)
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.uiState.value.recipes!!.map { it.id })

        pantry.items.value = emptyList()
        advanceUntilIdle()
        assertEquals("the pantry emptied, the recipe no longer matches", emptyList<RecipeSummary>(), vm.uiState.value.recipes)
    }

    // --- The layout ---

    @Test fun `the layout is stored at once and follows what is stored`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences()
        val vm = viewModel(FakeRecipeRepository(), preferences)
        collectEagerly(vm.uiState)
        advanceUntilIdle()
        assertEquals(RecipesLayout.ROWS, vm.uiState.value.layout)

        vm.onLayoutChange(RecipesLayout.GRID)
        advanceUntilIdle()
        assertEquals(RecipesLayout.GRID, preferences.recipesLayout)
        assertEquals(RecipesLayout.GRID, vm.uiState.value.layout)

        preferences.recipesLayout = RecipesLayout.ROWS // another screen of the same setting
        advanceUntilIdle()
        assertEquals(RecipesLayout.ROWS, vm.uiState.value.layout)

        val stored = viewModel(FakeRecipeRepository(), FakeAppPreferences(recipesLayout = RecipesLayout.GRID))
        collectEagerly(stored.uiState)
        advanceUntilIdle()
        assertEquals(RecipesLayout.GRID, stored.uiState.value.layout)
        assertFalse(stored.uiState.value.quick.isNotEmpty())
    }

    @Test fun `an unknown stored layout reads as the rows`() {
        assertEquals(RecipesLayout.ROWS, RecipesLayout.fromStoredName("MOSAIC"))
        assertEquals(RecipesLayout.ROWS, RecipesLayout.fromStoredName(null))
        assertEquals(RecipesLayout.GRID, RecipesLayout.fromStoredName("GRID"))
    }
}
