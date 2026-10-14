package com.example.recipeclipper.ui.pantry

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantryStock
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Selecting several pantry items (#258): entering, toggling, dragging a range, and the bulk actions with one undo. */
@OptIn(ExperimentalCoroutinesApi::class)
class PantrySelectionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groceries = FakeGroceryRepository()
    private val calendar = FakePlanCalendar()

    private fun item(id: Long, name: String, inStock: Boolean = true, aisle: Aisle = Aisle.OTHER) =
        PantryItem(id, name, "1 bag", "en", aisle, inStock, alwaysHave = id == 2L, purchasedDay = 20_000, expiresDay = 20_100 + id)

    private val pantry = FakePantryRepository((1L..8L).map { item(it, "item$it") })

    private fun TestScope.loaded(): PantryViewModel = PantryViewModel(pantry, groceries, calendar).also { advanceUntilIdle() }

    /** The rows' ids, top to bottom, as the screen shows them. */
    private fun PantryViewModel.order() = uiState.value.sections.orEmpty().flatMap { s -> s.items.map { it.id } }

    private fun PantryViewModel.selected() = uiState.value.selection?.ids

    @Test
    fun `a touch and hold starts selecting with that row checked, the menu with none`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = loaded()
        assertNull(vm.uiState.value.selection)
        vm.onLongPress(3)
        vm.onDragEnd()
        assertEquals(setOf(3L), vm.selected())

        vm.onExitSelection()
        assertNull(vm.uiState.value.selection)
        vm.onStartSelecting()
        assertEquals(emptySet<Long>(), vm.selected())
    }

    @Test
    fun `a tap toggles a row`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = loaded()
        vm.onStartSelecting()
        vm.onToggle(2)
        vm.onToggle(5)
        assertEquals(setOf(2L, 5L), vm.selected())
        vm.onToggle(2)
        assertEquals(setOf(5L), vm.selected())
    }

    @Test
    fun `a drag checks the range from its anchor, and dragging back gives rows their state back`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = loaded()
        val order = vm.order()
        vm.onStartSelecting()
        vm.onToggle(order[0])

        vm.onDragStart(order[2])
        vm.onDragTo(order[6])
        assertEquals(setOf(order[0]) + order.subList(2, 7), vm.selected())
        // Back past the anchor: what lay beyond it is unchecked again, and the rows above it join.
        vm.onDragTo(order[1])
        assertEquals(setOf(order[0], order[1], order[2]), vm.selected())
        vm.onDragTo(order[4])
        vm.onDragEnd()
        assertEquals(setOf(order[0]) + order.subList(2, 5), vm.selected())
        assertNull(vm.uiState.value.selection?.anchor)

        // Starting on a checked row unchecks the range instead.
        vm.onDragStart(order[3])
        vm.onDragTo(order[0])
        vm.onDragEnd()
        assertEquals(setOf(order[4]), vm.selected())
    }

    @Test
    fun `a touch and hold then a drag checks every row it passes`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = loaded()
        val order = vm.order()
        vm.onLongPress(order[5])
        vm.onDragTo(order[7])
        vm.onDragTo(order[3])
        vm.onDragEnd()
        assertEquals(order.subList(3, 6).toSet(), vm.selected())
    }

    @Test
    fun `Select all checks every row shown, and a section's only its own`() = runTest(mainDispatcherRule.dispatcher) {
        pantry.items.value = listOf(item(1, "milk", aisle = Aisle.DAIRY), item(2, "rice", aisle = Aisle.GRAINS), item(3, "oats", aisle = Aisle.GRAINS))
        val vm = loaded()
        vm.onStartSelecting()
        val grains = vm.uiState.value.sections!!.first { it.aisle == Aisle.GRAINS }
        vm.onSelectSection(grains)
        assertEquals(setOf(2L, 3L), vm.selected())
        vm.onSelectAll()
        assertEquals(setOf(1L, 2L, 3L), vm.selected())
        // A search hides some: they leave the selection.
        vm.onQueryChange("rice")
        assertEquals(setOf(2L), vm.selected())
    }

    @Test
    fun `Remove deletes every checked item at once, and one Undo puts them all back whole`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = loaded()
        val before = pantry.items.value
        vm.onLongPress(1)
        vm.onDragEnd()
        listOf(2L, 4L, 7L).forEach(vm::onToggle)
        val chosen = vm.selected()!!
        assertEquals(setOf(1L, 2L, 4L, 7L), chosen)
        vm.onRemoveSelected()
        advanceUntilIdle()

        assertNull(vm.uiState.value.selection)
        assertEquals(before.filterNot { it.id in chosen }, pantry.items.value)
        assertEquals(PantryMessage.Removed(1, chosen.size), vm.uiState.value.message)

        vm.onUndoDelete()
        advanceUntilIdle()
        assertEquals(before, pantry.items.value)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `Mark run out runs every checked item out, lists their names, and one Undo puts their stock back`() =
        runTest(mainDispatcherRule.dispatcher) {
            pantry.items.value = listOf(item(1, "milk"), item(2, "rice"), item(3, "oats", inStock = false), item(4, "flour"))
            groceries.add(listOf(NewGroceryLine("rice", "en")))
            val vm = loaded()
            val before = pantry.items.value
            vm.onStartSelecting()
            listOf(1L, 2L, 3L).forEach(vm::onToggle)
            vm.onMarkRunOutSelected()
            advanceUntilIdle()

            assertNull(vm.uiState.value.selection)
            assertEquals(listOf(PantryStock.RUN_OUT, PantryStock.RUN_OUT, PantryStock.RUN_OUT, PantryStock.IN_STOCK), pantry.items.value.map { it.stock })
            // Oats had run out already, so two changed; rice was on the list already.
            assertEquals(PantryMessage.MarkedRunOut(1, 2), vm.uiState.value.message)
            assertEquals(listOf("rice", "milk"), groceries.items.value.map { it.text })

            vm.onUndoDelete()
            advanceUntilIdle()
            assertEquals(before, pantry.items.value)
            assertTrue(vm.uiState.value.hasInStock)
        }
}
