package com.example.recipeclipper.ui.groceries

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.fake.FakeAppPreferences
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

/**
 * Where groceries meet the pantry (#51, #146): a tick only ticks, "Done shopping" puts things
 * away in one step with one undo, and the add sheet's first ticks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroceriesPantryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groceries = FakeGroceryRepository()
    private val calendar = FakePlanCalendar()

    private fun pantryItem(id: Long, name: String, inStock: Boolean, alwaysHave: Boolean = false) =
        PantryItem(id, name, null, "en", Aisle.OTHER, inStock, alwaysHave, purchasedDay = 1, expiresDay = null)

    private fun GroceriesViewModel.row(text: String) =
        uiState.value.sections.orEmpty().flatMap { it.rows }.single { r -> r.items.any { it.text == text } }

    /** The sheet as name → ticked. */
    private fun GroceriesViewModel.sheet() = uiState.value.putAway!!.let { s -> s.items.map { it.name to (it.key in s.ticked) } }

    private fun GroceriesViewModel.key(name: String) = uiState.value.putAway!!.items.single { it.name == name }.key

    @Test
    fun `a tick only ticks - the pantry is untouched and nothing is offered`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(pantryItem(1, "Butter", inStock = false)))
        groceries.add(listOf(NewGroceryLine("250 g unsalted butter", "en"), NewGroceryLine("2 cups flour", "en")))
        val vm = GroceriesViewModel(groceries, pantry, calendar)
        advanceUntilIdle()

        vm.onToggle(vm.row("250 g unsalted butter"))
        vm.onToggle(vm.row("2 cups flour"))
        advanceUntilIdle()
        assertTrue(groceries.items.value.all { it.checked })
        assertEquals(listOf(pantryItem(1, "Butter", inStock = false)), pantry.items.value)
        assertNull(vm.uiState.value.removed)
        assertNull(vm.uiState.value.putAway)
    }

    @Test
    fun `done shopping restocks and adds what's ticked, clears every ticked line, and one undo reverts it all`() =
        runTest(mainDispatcherRule.dispatcher) {
            val pantry = FakePantryRepository(listOf(pantryItem(1, "Butter", inStock = false)))
            groceries.add(
                listOf("250 g unsalted butter", "2 tbsp butter", "2 cups flour", "salt and pepper", "2 onions")
                    .map { NewGroceryLine(it, "en") }
            )
            val vm = GroceriesViewModel(groceries, pantry, calendar)
            val ticked = setOf("250 g unsalted butter", "2 tbsp butter", "2 cups flour", "salt and pepper")
            groceries.setChecked(groceries.items.value.filter { it.text in ticked }.map { it.id }, true)
            advanceUntilIdle()
            val before = groceries.items.value

            vm.onDoneShopping()
            // One entry per pantry item or ingredient, in the list's order (both butters are the
            // pantry's Butter); what the pantry tracks starts ticked. A line the app can't name
            // ("salt and pepper") isn't listed.
            assertEquals(listOf("Butter" to true, "flour" to false), vm.sheet())
            assertEquals(before, groceries.items.value) // nothing written yet

            vm.onPutAwayToggle(vm.key("flour"))
            vm.onPutAwayConfirm()
            advanceUntilIdle()
            assertNull(vm.uiState.value.putAway)
            assertEquals(listOf("2 onions"), groceries.items.value.map { it.text })
            val (butter, flour) = pantry.items.value
            assertTrue(butter.inStock)
            assertEquals(calendar.today(), butter.purchasedDay)
            assertEquals("flour", flour.name)
            assertEquals(Aisle.BAKING, flour.aisle) // the grocery item's aisle
            assertTrue(flour.inStock)
            assertEquals(calendar.today(), flour.purchasedDay)
            assertTrue(vm.uiState.value.removed!!.putAway)

            vm.onUndoRemove()
            advanceUntilIdle()
            assertEquals(before, groceries.items.value)
            assertEquals(listOf(pantryItem(1, "Butter", inStock = false)), pantry.items.value)
            assertNull(vm.uiState.value.removed)
        }

    @Test
    fun `an item left unticked in the sheet stays out of the pantry but leaves the list`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository()
        groceries.add(listOf(NewGroceryLine("2 cups flour", "en")))
        val vm = GroceriesViewModel(groceries, pantry, calendar)
        advanceUntilIdle()
        vm.onToggle(vm.row("2 cups flour"))
        advanceUntilIdle()

        vm.onDoneShopping()
        assertEquals(listOf("flour" to false), vm.sheet())
        vm.onPutAwayConfirm()
        advanceUntilIdle()
        assertTrue(pantry.items.value.isEmpty())
        assertTrue(groceries.items.value.isEmpty())
        assertFalse(vm.uiState.value.removed!!.putAway) // "Checked items removed"
    }

    @Test
    fun `with nothing the pantry can hold, done shopping clears at once, undoably`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository()
        groceries.add(listOf(NewGroceryLine("salt and pepper", "en"), NewGroceryLine("2 eggs", "en")))
        val vm = GroceriesViewModel(groceries, pantry, calendar)
        advanceUntilIdle()
        vm.onToggle(vm.row("salt and pepper"))
        advanceUntilIdle()

        vm.onDoneShopping()
        advanceUntilIdle()
        assertNull(vm.uiState.value.putAway)
        assertEquals(listOf("2 eggs"), groceries.items.value.map { it.text })
        vm.onUndoRemove()
        advanceUntilIdle()
        assertEquals(listOf("salt and pepper", "2 eggs"), groceries.items.value.map { it.text })
    }

    @Test
    fun `dismissing the sheet changes nothing, and a timed-out snackbar keeps the put-away`() =
        runTest(mainDispatcherRule.dispatcher) {
            val pantry = FakePantryRepository(listOf(pantryItem(1, "milk", inStock = false)))
            groceries.add(listOf(NewGroceryLine("1 cup milk", "en")))
            val vm = GroceriesViewModel(groceries, pantry, calendar)
            advanceUntilIdle()
            vm.onToggle(vm.row("1 cup milk"))
            advanceUntilIdle()

            vm.onDoneShopping()
            vm.onPutAwayDismissed()
            advanceUntilIdle()
            assertNull(vm.uiState.value.putAway)
            assertEquals(1, groceries.items.value.size)
            assertFalse(pantry.items.value.single().inStock)

            vm.onDoneShopping()
            vm.onPutAwayConfirm()
            advanceUntilIdle()
            vm.onSnackbarDismissed()
            vm.onUndoRemove()
            advanceUntilIdle()
            assertTrue(groceries.items.value.isEmpty())
            assertTrue(pantry.items.value.single().inStock)
        }

    @Test
    fun `the sheet starts with what the pantry has unticked, staples included`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(
            listOf(pantryItem(1, "flour", inStock = true), pantryItem(2, "salt", inStock = false, alwaysHave = true),
                pantryItem(3, "milk", inStock = false))
        )
        val vm = AddToGroceriesViewModel(groceries, FakeAppPreferences(), pantry)
        vm.setRecipe(7, "Pancakes", "en", listOf("2 cups flour", "1 tsp salt", "1 cup milk", "2 eggs"))
        advanceUntilIdle()

        assertEquals(setOf(SourceLine("recipe-7", 0), SourceLine("recipe-7", 1)), vm.uiState.value.unticked)
        vm.onAdd()
        advanceUntilIdle()
        assertEquals(listOf("1 cup milk", "2 eggs"), groceries.items.value.map { it.text })
    }
}
