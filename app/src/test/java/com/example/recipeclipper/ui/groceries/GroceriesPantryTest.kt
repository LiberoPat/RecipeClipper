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

/** Where groceries meet the pantry (#51): ticking off into the pantry, and the sheet's first ticks. */
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

    @Test
    fun `ticking off an item the pantry tracks puts it back in stock, undoably`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(pantryItem(1, "Butter", inStock = false)))
        groceries.add(listOf(NewGroceryLine("250 g unsalted butter", "en")))
        val vm = GroceriesViewModel(groceries, pantry, calendar)
        advanceUntilIdle()

        vm.onToggle(vm.row("250 g unsalted butter"))
        advanceUntilIdle()
        val butter = pantry.items.value.single()
        assertTrue(butter.inStock)
        assertEquals(calendar.today(), butter.purchasedDay)
        assertEquals("Butter", (vm.uiState.value.pantryOffer as PantryOffer.Restocked).name)

        vm.onUndoRestock()
        advanceUntilIdle()
        assertFalse(pantry.items.value.single().inStock)
        assertEquals(1L, pantry.items.value.single().purchasedDay)
        assertNull(vm.uiState.value.pantryOffer)
    }

    @Test
    fun `an item the pantry doesn't track is offered, and added only when accepted`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository()
        groceries.add(listOf(NewGroceryLine("2 cups flour", "en")))
        val vm = GroceriesViewModel(groceries, pantry, calendar)
        advanceUntilIdle()

        vm.onToggle(vm.row("2 cups flour"))
        advanceUntilIdle()
        val offer = vm.uiState.value.pantryOffer as PantryOffer.Offer
        assertEquals("flour", offer.name)
        assertTrue(pantry.items.value.isEmpty())

        vm.onAddToPantry()
        advanceUntilIdle()
        val flour = pantry.items.value.single()
        assertEquals("flour", flour.name)
        assertEquals(Aisle.BAKING, flour.aisle) // the grocery item's aisle
        assertTrue(flour.inStock)
        assertEquals(calendar.today(), flour.purchasedDay)
    }

    @Test
    fun `a dismissed offer adds nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository()
        groceries.add(listOf(NewGroceryLine("2 eggs", "en")))
        val vm = GroceriesViewModel(groceries, pantry, calendar)
        advanceUntilIdle()
        vm.onToggle(vm.row("2 eggs"))
        advanceUntilIdle()
        vm.onPantryOfferDismissed()
        advanceUntilIdle()
        assertTrue(pantry.items.value.isEmpty())
    }

    @Test
    fun `nothing is offered when it's already in stock, unticking, or the line has no name`() =
        runTest(mainDispatcherRule.dispatcher) {
            val pantry = FakePantryRepository(listOf(pantryItem(1, "milk", inStock = true)))
            groceries.add(listOf(NewGroceryLine("1 cup milk", "en"), NewGroceryLine("salt and pepper", "en")))
            val vm = GroceriesViewModel(groceries, pantry, calendar)
            advanceUntilIdle()

            vm.onToggle(vm.row("1 cup milk"))
            advanceUntilIdle()
            assertNull(vm.uiState.value.pantryOffer)

            vm.onToggle(vm.row("1 cup milk")) // unticking
            advanceUntilIdle()
            assertNull(vm.uiState.value.pantryOffer)

            vm.onToggle(vm.row("salt and pepper"))
            advanceUntilIdle()
            assertNull(vm.uiState.value.pantryOffer)
            assertEquals(1, pantry.items.value.size)
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
