package com.example.recipeclipper.ui.groceries

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** "Add this list" (#149): a list shared in or pasted, added to Groceries or the Pantry. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groceries = FakeGroceryRepository()
    private val inbox = ReceivedListInbox()
    private val phoneLocale = Locale.getDefault()

    // The lines are read in the phone's language unless their words clearly say another.
    @Before fun englishPhone() = Locale.setDefault(Locale.ENGLISH)

    @After fun restoreLocale() = Locale.setDefault(phoneLocale)
    private val sent = "Groceries\n\nMeat\n- 2 lb chicken thighs (Sheet-pan chicken)\n\nProduce\n- 2 onions\n- 1 lime"

    private fun pantryItem(id: Long, name: String, inStock: Boolean) =
        PantryItem(id, name, null, "en", Aisle.PRODUCE, inStock, alwaysHave = false, purchasedDay = null, expiresDay = null)

    @Test
    fun `a list shared in opens the sheet once, every line ticked`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ReceiveListViewModel(groceries, FakePantryRepository(), FakePlanCalendar(), inbox)
        inbox.offer(sent)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("2 lb chicken thighs (Sheet-pan chicken)", "2 onions", "1 lime"), state.lines)
        assertEquals(3, state.tickedCount)
        assertNull(inbox.pending.value)
    }

    @Test
    fun `the ticked lines go on the grocery list as written`() = runTest(mainDispatcherRule.dispatcher) {
        groceries.add(listOf(NewGroceryLine("1 lb chicken thighs", "en")))
        val vm = ReceiveListViewModel(groceries, FakePantryRepository(), FakePlanCalendar(), inbox)
        vm.open(sent)
        vm.onToggle(2)
        vm.onAddToGroceries()
        advanceUntilIdle()

        assertEquals(
            listOf("1 lb chicken thighs", "2 lb chicken thighs (Sheet-pan chicken)", "2 onions"),
            groceries.items.value.map { it.text }
        )
        assertEquals(listOf("en", "en", "en"), groceries.items.value.map { it.language })
        assertEquals(ReceiveTarget.GROCERIES, vm.uiState.value.added)
        // A second tap adds nothing more.
        vm.onAddToGroceries()
        advanceUntilIdle()
        assertEquals(3, groceries.items.value.size)
    }

    @Test
    fun `in the pantry each line is its ingredient, and one already tracked is restocked`() =
        runTest(mainDispatcherRule.dispatcher) {
            val pantry = FakePantryRepository(listOf(pantryItem(1, "onions", inStock = false), pantryItem(2, "lime", inStock = true)))
            val calendar = FakePlanCalendar()
            val vm = ReceiveListViewModel(groceries, pantry, calendar, inbox)
            vm.open("$sent\n- 3 onions\n- salt and pepper")
            vm.onAddToPantry()
            advanceUntilIdle()

            val items = pantry.items.value.associateBy { it.name }
            assertEquals(setOf("onions", "lime", "chicken thighs", "salt and pepper"), items.keys)
            assertEquals(true, items.getValue("onions").inStock)
            assertEquals(calendar.today, items.getValue("chicken thighs").purchasedDay)
            assertEquals(ReceiveTarget.PANTRY, vm.uiState.value.added)
            assertEquals(0, groceries.items.value.size)
        }

    @Test
    fun `a clipboard with no list shows the sheet empty, and nothing can be added`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = ReceiveListViewModel(groceries, FakePantryRepository(), FakePlanCalendar(), inbox)
        vm.open(null)
        assertEquals(emptyList<String>(), vm.uiState.value.lines)
        vm.onAddToGroceries()
        advanceUntilIdle()
        assertNull(vm.uiState.value.added)

        vm.onDismiss()
        assertNull(vm.uiState.value.lines)
    }
}
