package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.UseUpChange
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The end-of-cooking sheet (#147) over fakes: what it lists and preselects, what one confirm
 * writes to the pantry and the grocery list, and the one Undo. The iOS
 * `PantryUseUpViewModelTests` mirror these.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PantryUseUpViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groceries = FakeGroceryRepository()

    private fun item(id: Long, name: String, quantity: String?, inStock: Boolean = true) =
        PantryItem(id, name, quantity, "en", Aisle.OTHER, inStock, alwaysHave = false, purchasedDay = 1, expiresDay = 30)

    private val chicken = item(1, "chicken", "2 lb")
    private val eggs = item(2, "eggs", "6")
    private val flour = item(3, "flour", "half a bag")
    private val milk = item(4, "milk", "1 cup")

    private fun viewModel(vararg items: PantryItem) = FakePantryRepository(items.toList()).let { it to PantryUseUpViewModel(it, groceries) }

    @Test
    fun `finishing opens the sheet - worked-out rows ticked, the rest keep`() = runTest(mainDispatcherRule.dispatcher) {
        val (_, vm) = viewModel(chicken, eggs, flour)
        vm.onCookFinished("en", listOf("1 lb chicken", "2 large eggs", "2 cups flour", "1 tsp salt"))
        advanceUntilIdle()

        val sheet = vm.uiState.value.sheet!!
        assertEquals(listOf("chicken", "eggs", "flour"), sheet.rows.map { it.item.name })
        assertEquals(UseUpChange.Subtract("2 lb", "1 lb"), sheet.rows[0].change)
        assertEquals(UseUpChange.Subtract("6", "4"), sheet.rows[1].change)
        assertEquals(UseUpChange.Ask, sheet.rows[2].change)
        assertEquals(setOf(1L, 2L), sheet.ticked)
        assertEquals(UseUpChoice.KEEP, sheet.choice(3))
    }

    @Test
    fun `nothing in the pantry used, no sheet`() = runTest(mainDispatcherRule.dispatcher) {
        val (_, vm) = viewModel(chicken)
        vm.onCookFinished("en", listOf("2 cups rice"))
        advanceUntilIdle()
        assertNull(vm.uiState.value.sheet)
    }

    @Test
    fun `confirm writes the new quantities, and only the ticked ones`() = runTest(mainDispatcherRule.dispatcher) {
        val (pantry, vm) = viewModel(chicken, eggs)
        vm.onCookFinished("en", listOf("1 lb chicken", "2 eggs"))
        advanceUntilIdle()
        vm.onToggle(2) // the eggs weren't from the pantry after all
        vm.onConfirm()
        advanceUntilIdle()

        assertNull(vm.uiState.value.sheet)
        assertEquals(listOf("1 lb", "6"), pantry.items.value.map { it.quantity })
        assertEquals(listOf(true, true), pantry.items.value.map { it.inStock })
        assertNotNull(vm.uiState.value.updated)
        assertEquals(emptyList<Any>(), groceries.items.value)
    }

    @Test
    fun `used up goes out of stock with no quantity, onto the grocery list`() = runTest(mainDispatcherRule.dispatcher) {
        val (pantry, vm) = viewModel(milk)
        vm.onCookFinished("en", listOf("1 cup milk"))
        advanceUntilIdle()
        assertEquals(UseUpChange.Subtract("1 cup", null), vm.uiState.value.sheet!!.rows.single().change)
        vm.onConfirm()
        advanceUntilIdle()

        val after = pantry.items.value.single()
        assertEquals(false, after.inStock)
        assertNull(after.quantity)
        assertEquals(listOf("milk"), groceries.items.value.map { it.text })
    }

    @Test
    fun `an asked row - running low goes on the list and stays in stock, out goes out too`() =
        runTest(mainDispatcherRule.dispatcher) {
            val sugar = item(5, "sugar", null)
            val (pantry, vm) = viewModel(flour, sugar)
            vm.onCookFinished("en", listOf("2 cups flour", "1 cup sugar"))
            advanceUntilIdle()
            vm.onChoice(3, UseUpChoice.LOW)
            vm.onChoice(5, UseUpChoice.OUT)
            vm.onConfirm()
            advanceUntilIdle()

            assertEquals(listOf(true, false), pantry.items.value.map { it.inStock })
            // Asked rows keep whatever quantity was written: nothing was worked out.
            assertEquals(listOf("half a bag", null), pantry.items.value.map { it.quantity })
            assertEquals(listOf("flour", "sugar"), groceries.items.value.map { it.text })
        }

    @Test
    fun `keep changes nothing, and a confirm with nothing to do raises no snackbar`() = runTest(mainDispatcherRule.dispatcher) {
        val (pantry, vm) = viewModel(flour)
        vm.onCookFinished("en", listOf("2 cups flour"))
        advanceUntilIdle()
        vm.onConfirm()
        advanceUntilIdle()
        assertEquals(listOf(flour), pantry.items.value)
        assertNull(vm.uiState.value.updated)
    }

    @Test
    fun `an item already on the list isn't added twice`() = runTest(mainDispatcherRule.dispatcher) {
        groceries.add(listOf(NewGroceryLine("Milk", "en")))
        val (_, vm) = viewModel(milk)
        vm.onCookFinished("en", listOf("1 cup milk"))
        advanceUntilIdle()
        vm.onConfirm()
        advanceUntilIdle()
        assertEquals(listOf("Milk"), groceries.items.value.map { it.text })
    }

    @Test
    fun `one Undo puts the pantry back and takes the added lines off the list`() = runTest(mainDispatcherRule.dispatcher) {
        groceries.add(listOf(NewGroceryLine("2 lemons", "en")))
        val (pantry, vm) = viewModel(chicken, milk)
        vm.onCookFinished("en", listOf("1 lb chicken", "1 cup milk"))
        advanceUntilIdle()
        vm.onConfirm()
        advanceUntilIdle()
        assertEquals(listOf("2 lemons", "milk"), groceries.items.value.map { it.text })

        vm.onUndo()
        advanceUntilIdle()
        assertEquals(listOf(chicken, milk), pantry.items.value)
        assertEquals(listOf("2 lemons"), groceries.items.value.map { it.text })
        assertNull(vm.uiState.value.updated)
    }

    @Test
    fun `dismissing changes nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val (pantry, vm) = viewModel(chicken)
        vm.onCookFinished("en", listOf("1 lb chicken"))
        advanceUntilIdle()
        vm.onDismissed()
        advanceUntilIdle()
        assertNull(vm.uiState.value.sheet)
        assertEquals(listOf(chicken), pantry.items.value)
    }
}
