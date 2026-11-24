package com.example.recipeclipper.ui.pantry

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantryStock
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Ask with a switch (#465, `pantryListSwitch`): the edit sheet's "Add to grocery list", and the shortcuts' Undo. */
@OptIn(ExperimentalCoroutinesApi::class)
class PantryListSwitchTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groceries = FakeGroceryRepository()
    private val calendar = FakePlanCalendar()

    private fun item(id: Long, name: String, inStock: Boolean = true, runningLow: Boolean = false) =
        PantryItem(id, name, null, "en", Aisle.OTHER, inStock, alwaysHave = false, purchasedDay = null, expiresDay = null, runningLow = runningLow)

    private fun flags(on: Boolean) =
        FeatureFlags(FakeFeatureFlagStore(mapOf(Flag.PANTRY_LIST_SWITCH.key to on)), FlagRegistry.definitions, isDebug = true)

    private fun TestScope.loaded(pantry: FakePantryRepository, on: Boolean = true): PantryViewModel =
        PantryViewModel(pantry, groceries, calendar, flags(on)).also { advanceUntilIdle() }

    private fun texts() = groceries.items.value.map { it.text }

    @Test
    fun `the flag is on by default in debug and release`() {
        val store = FakeFeatureFlagStore()
        assertTrue(FeatureFlags(store, FlagRegistry.definitions, isDebug = true).isOn(Flag.PANTRY_LIST_SWITCH))
        assertTrue(FeatureFlags(store, FlagRegistry.definitions, isDebug = false).isOn(Flag.PANTRY_LIST_SWITCH))
    }

    @Test
    fun `in stock the switch is hidden, and moving to Running low shows it on and adds the line`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "milk")))
        val vm = loaded(pantry)
        vm.onEdit(pantry.items.value.single())
        assertFalse(vm.uiState.value.editing!!.showsListSwitch)

        vm.onEditStock(PantryStock.RUNNING_LOW)
        advanceUntilIdle()
        val editing = vm.uiState.value.editing!!
        assertTrue(editing.showsListSwitch)
        assertTrue(editing.addToList)
        assertEquals(listOf("milk"), texts())
        assertEquals(PantryStock.RUNNING_LOW, pantry.items.value.single().stock)
        assertNull(vm.uiState.value.message) // the sheet's choice is shown by the switch, not a snackbar
    }

    @Test
    fun `turning the switch off takes the line this sheet added back off, and on puts it back`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "milk")))
        val vm = loaded(pantry)
        vm.onEdit(pantry.items.value.single())
        vm.onEditStock(PantryStock.RUN_OUT)
        vm.onEditAddToList(false) // straight after: the writes are queued in order
        advanceUntilIdle()
        assertFalse(vm.uiState.value.editing!!.addToList)
        assertEquals(emptyList<String>(), texts())
        assertEquals(PantryStock.RUN_OUT, pantry.items.value.single().stock) // the stock stays

        vm.onEditAddToList(true)
        advanceUntilIdle()
        assertEquals(listOf("milk"), texts())

        vm.onEditAddToList(false)
        advanceUntilIdle()
        vm.onEditDismissed()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), texts())
        assertNull(vm.uiState.value.message) // only the sheet's own line went: nothing to offer back
    }

    @Test
    fun `off, then between Running low and Run out, the switch stays off and nothing is added`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "milk")))
        val vm = loaded(pantry)
        vm.onEdit(pantry.items.value.single())
        vm.onEditStock(PantryStock.RUNNING_LOW)
        vm.onEditAddToList(false)
        vm.onEditStock(PantryStock.RUN_OUT)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.editing!!.addToList)
        assertEquals(emptyList<String>(), texts())

        // Back to In stock hides it; out of stock again from there turns it on.
        vm.onEditStock(PantryStock.IN_STOCK)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.editing!!.showsListSwitch)
        vm.onEditStock(PantryStock.RUNNING_LOW)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.editing!!.addToList)
        assertEquals(listOf("milk"), texts())
    }

    @Test
    fun `an item already on the list opens with the switch on, and off offers those lines back`() = runTest(mainDispatcherRule.dispatcher) {
        groceries.add(listOf(NewGroceryLine("2 milk", "en"), NewGroceryLine("bread", "en")))
        val pantry = FakePantryRepository(listOf(item(1, "milk", runningLow = true), item(2, "rice", inStock = false)))
        val vm = loaded(pantry)
        assertEquals(setOf(1L), vm.uiState.value.onList)

        vm.onEdit(pantry.items.value.first { it.id == 1L })
        assertTrue(vm.uiState.value.editing!!.showsListSwitch)
        assertTrue(vm.uiState.value.editing!!.addToList)

        vm.onEditAddToList(false)
        advanceUntilIdle()
        assertEquals(listOf("bread"), texts())
        vm.onEditSave()
        advanceUntilIdle()
        // Lines the sheet didn't add went: the basket tag's Undo offers them back.
        assertEquals(PantryMessage.TakenOffList(vm.uiState.value.message!!.id, 1), vm.uiState.value.message)
        vm.onUndoDelete()
        advanceUntilIdle()
        assertEquals(listOf("2 milk", "bread"), texts())

        // Out but not on the list: the switch opens off.
        vm.onEdit(pantry.items.value.first { it.id == 2L })
        assertTrue(vm.uiState.value.editing!!.showsListSwitch)
        assertFalse(vm.uiState.value.editing!!.addToList)
    }

    @Test
    fun `a shortcut adds with an Undo that takes only that line off`() = runTest(mainDispatcherRule.dispatcher) {
        groceries.add(listOf(NewGroceryLine("bread", "en")))
        val pantry = FakePantryRepository(listOf(item(1, "milk")))
        val vm = loaded(pantry)

        vm.onSetStock(pantry.items.value.single(), PantryStock.RUN_OUT)
        advanceUntilIdle()
        assertEquals(listOf("bread", "milk"), texts())
        val message = vm.uiState.value.message
        assertEquals(PantryMessage.AddedToList(message!!.id, "milk"), message)

        vm.onUndoDelete()
        advanceUntilIdle()
        assertEquals(listOf("bread"), texts())
        assertEquals(PantryStock.RUN_OUT, pantry.items.value.single().stock) // the stock stays as chosen
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `a shortcut whose line is already there shows no snackbar`() = runTest(mainDispatcherRule.dispatcher) {
        groceries.add(listOf(NewGroceryLine("milk", "en")))
        val pantry = FakePantryRepository(listOf(item(1, "milk")))
        val vm = loaded(pantry)

        vm.onSetStock(pantry.items.value.single(), PantryStock.RUNNING_LOW)
        advanceUntilIdle()
        assertEquals(listOf("milk"), texts())
        assertNull(vm.uiState.value.message)

        // Restocking adds nothing either.
        vm.onSetStock(pantry.items.value.single(), PantryStock.IN_STOCK)
        advanceUntilIdle()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `flag off, the sheet has no switch and every way adds silently, as before`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "milk"), item(2, "rice")))
        val vm = loaded(pantry, on = false)
        vm.onEdit(pantry.items.value.first { it.id == 1L })
        vm.onEditStock(PantryStock.RUNNING_LOW)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.editing!!.showsListSwitch)
        vm.onEditAddToList(false) // no switch: nothing happens
        advanceUntilIdle()
        assertEquals(listOf("milk"), texts())
        vm.onEditDismissed()

        vm.onSetStock(pantry.items.value.first { it.id == 2L }, PantryStock.RUN_OUT)
        advanceUntilIdle()
        assertEquals(listOf("milk", "rice"), texts())
        assertNull(vm.uiState.value.message)
    }
}
