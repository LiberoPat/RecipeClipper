package com.example.recipeclipper.ui.groceries

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.GroceryCombiner
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeDecisionRepository
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class GroceriesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeGroceryRepository()

    private suspend fun add(vararg lines: String) =
        repository.add(lines.map { NewGroceryLine(it, "en") })

    private fun GroceriesViewModel.rows() = uiState.value.sections.orEmpty().flatMap { it.rows }

    @Test
    fun `the list is grouped by aisle and the same ingredient is added up`() = runTest(mainDispatcherRule.dispatcher) {
        add("200 g flour", "2 onions", "100 g flour")
        val vm = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar())
        advanceUntilIdle()

        val sections = vm.uiState.value.sections!!
        assertEquals(listOf(Aisle.PRODUCE, Aisle.BAKING), sections.map { it.aisle })
        assertEquals("300 g flour", (sections[1].rows.single() as GroceryCombiner.Row.Combined).text)
    }

    @Test
    fun `an item in Other is filed where the model decided, once, and a user's move stands`() =
        runTest(mainDispatcherRule.dispatcher) {
            add("2 tbsp furikake", "1 jar gochugaru flakes", "2 onions")
            val furikake = DecisionQuestion.aisle("furikake", "en")
            val decisions = FakeDecisionRepository(mapOf(furikake to "spices"))
            val vm = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar(), decisions)
            advanceUntilIdle()

            val aisles = repository.items.value.associate { it.text to it.aisle }
            assertEquals(Aisle.SPICES, aisles["2 tbsp furikake"])
            assertEquals(Aisle.OTHER, aisles["1 jar gochugaru flakes"]) // no answer: stays Other
            assertEquals(Aisle.PRODUCE, aisles["2 onions"])
            assertFalse(decisions.asked.any { it.input == "onions" })

            // Moved back to Other by the user: the cached answer never files it again.
            val id = repository.items.value.first { it.text == "2 tbsp furikake" }.id
            repository.setAisle(listOf(id), Aisle.OTHER)
            advanceUntilIdle()
            assertEquals(Aisle.OTHER, repository.items.value.first { it.id == id }.aisle)
            assertEquals(1, vm.uiState.value.sections!!.count { it.aisle == Aisle.OTHER })
        }

    @Test
    fun `a typed item goes on the list and clears the field`() = runTest(mainDispatcherRule.dispatcher) {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            val vm = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar())
            vm.onDraftChange("  milk ")
            vm.onAddTyped()
            advanceUntilIdle()

            val item = repository.items.value.single()
            assertEquals("milk", item.text)
            assertEquals("en", item.language)
            assertEquals(Aisle.DAIRY, item.aisle)
            assertEquals("", vm.uiState.value.draft)
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `a typed item is read in the phone's language when the app has words for it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val default = Locale.getDefault()
            Locale.setDefault(Locale.GERMAN)
            try {
                val vm = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar())
                vm.onDraftChange("Milch")
                vm.onAddTyped()
                advanceUntilIdle()
                assertEquals("de", repository.items.value.single().language)
            } finally {
                Locale.setDefault(default)
            }
        }

    @Test
    fun `ticking a combined row ticks every line in it`() = runTest(mainDispatcherRule.dispatcher) {
        add("200 g flour", "100 g flour")
        val vm = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar())
        advanceUntilIdle()

        vm.onToggle(vm.rows().single())
        advanceUntilIdle()
        assertTrue(repository.items.value.all { it.checked })
        assertTrue(vm.uiState.value.hasChecked)

        vm.onToggle(vm.rows().single())
        advanceUntilIdle()
        assertTrue(repository.items.value.none { it.checked })
    }

    @Test
    fun `moving a row to another aisle keeps it there`() = runTest(mainDispatcherRule.dispatcher) {
        add("1 jar pickles")
        val vm = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar())
        advanceUntilIdle()
        assertEquals(Aisle.OTHER, vm.uiState.value.sections!!.single().aisle)

        vm.onMoveStart(vm.rows().single())
        vm.onMoveTo(Aisle.CONDIMENTS)
        advanceUntilIdle()

        assertEquals(Aisle.CONDIMENTS, vm.uiState.value.sections!!.single().aisle)
        assertNull(vm.uiState.value.moving)
    }

    @Test
    fun `a delete can be undone`() = runTest(mainDispatcherRule.dispatcher) {
        add("2 onions", "1 cup milk")
        val vm = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar())
        advanceUntilIdle()

        val onions = vm.rows().first()
        vm.onDelete(onions, "2 onions")
        advanceUntilIdle()
        assertEquals(listOf("1 cup milk"), repository.items.value.map { it.text })
        assertEquals("2 onions", vm.uiState.value.removed?.label)

        vm.onUndoRemove()
        advanceUntilIdle()
        assertEquals(listOf("2 onions", "1 cup milk"), repository.items.value.map { it.text })
        assertNull(vm.uiState.value.removed)
    }

    @Test
    fun `clear checked removes only the ticked items, and can be undone`() = runTest(mainDispatcherRule.dispatcher) {
        add("2 onions", "1 cup milk")
        val vm = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar())
        advanceUntilIdle()
        repository.setChecked(listOf(repository.items.value.first().id), true)
        advanceUntilIdle()

        vm.onClearChecked()
        advanceUntilIdle()
        assertEquals(listOf("1 cup milk"), repository.items.value.map { it.text })
        assertNull(vm.uiState.value.removed!!.label)

        vm.onUndoRemove()
        advanceUntilIdle()
        assertEquals(2, repository.items.value.size)
    }

    @Test
    fun `a dismissed snackbar keeps the delete`() = runTest(mainDispatcherRule.dispatcher) {
        add("2 onions")
        val vm = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar())
        advanceUntilIdle()
        vm.onDelete(vm.rows().single(), "2 onions")
        advanceUntilIdle()
        vm.onSnackbarDismissed()
        vm.onUndoRemove()
        advanceUntilIdle()
        assertTrue(repository.items.value.isEmpty())
        assertTrue(vm.uiState.value.isEmpty)
    }

    @Test
    fun `the shared text is what is left to buy`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar())
        advanceUntilIdle()
        assertNull(vm.shareText("Groceries") { it.key })

        add("2 onions", "1 cup milk")
        repository.setChecked(listOf(repository.items.value.last().id), true)
        advanceUntilIdle()
        assertEquals("Groceries\n\nproduce\n- 2 onions", vm.shareText("Groceries") { it.key })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AddToGroceriesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeGroceryRepository()

    @Test
    fun `a recipe's lines are all ticked, and headings and blanks are left out`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = AddToGroceriesViewModel(repository, FakeAppPreferences(), FakePantryRepository())
        vm.setRecipe(7, "Pancakes", "en", listOf("For the batter:", "2 cups flour", "", "2 eggs"))

        val source = vm.uiState.value.sources!!.single()
        assertEquals(listOf("2 cups flour", "2 eggs"), source.lines)
        assertEquals(2, vm.uiState.value.tickedCount)
    }

    @Test
    fun `only the ticked lines are added, with their recipe`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = AddToGroceriesViewModel(repository, FakeAppPreferences(), FakePantryRepository())
        vm.setRecipe(7, "Pancakes", "en", listOf("2 cups flour", "2 eggs", "1 cup milk"))
        vm.onToggle(SourceLine("recipe-7", 1))
        vm.onAdd()
        advanceUntilIdle()

        assertEquals(listOf("2 cups flour", "1 cup milk"), repository.items.value.map { it.text })
        assertTrue(repository.items.value.all { it.recipeId == 7L && it.language == "en" })
        assertTrue(vm.uiState.value.added)
    }

    @Test
    fun `nothing ticked adds nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = AddToGroceriesViewModel(repository, FakeAppPreferences(), FakePantryRepository())
        vm.setRecipe(7, "Toast", "en", listOf("1 slice bread"))
        vm.onToggle(SourceLine("recipe-7", 0))
        vm.onAdd()
        advanceUntilIdle()
        assertTrue(repository.items.value.isEmpty())
        assertFalse(vm.uiState.value.added)
    }

    @Test
    fun `the week's recipes come at their planned servings, in the user's units`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.planned = listOf(
                PlannedIngredients(1, day = 100, servings = 8, recipeId = 7, title = "Pancakes",
                    ingredients = listOf("2 cups flour", "Garnish:"), yield = "Serves 4", language = "en"),
                PlannedIngredients(2, day = 102, servings = null, recipeId = 9, title = "Soup",
                    ingredients = listOf("1 lb potatoes"), yield = "Serves 2", language = "en"),
                PlannedIngredients(3, day = 110, servings = null, recipeId = 5, title = "Next week",
                    ingredients = listOf("1 onion"), yield = null, language = "en")
            )
            val vm = AddToGroceriesViewModel(repository, FakeAppPreferences(unitSystem = UnitSystem.METRIC), FakePantryRepository())
            vm.loadWeek(100)
            assertNull(vm.uiState.value.sources)
            advanceUntilIdle()

            val sources = vm.uiState.value.sources!!
            assertEquals(listOf("Pancakes", "Soup"), sources.map { it.title })
            assertEquals(listOf("480 g flour"), sources[0].lines)
            assertEquals(listOf("455 g potatoes"), sources[1].lines)

            vm.onAdd()
            advanceUntilIdle()
            assertEquals(listOf(100L, 102L), repository.items.value.map { it.plannedDay })
        }
}
