package com.example.recipeclipper.ui.pantry

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantrySort
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class PantryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val groceries = FakeGroceryRepository()
    private val calendar = FakePlanCalendar()
    private val locale = Locale.getDefault()

    @Before fun english() = Locale.setDefault(Locale.ENGLISH)

    @After fun restore() = Locale.setDefault(locale)

    private fun item(id: Long, name: String, inStock: Boolean = true, aisle: Aisle = Aisle.OTHER, expires: Long? = null) =
        PantryItem(id, name, null, "en", aisle, inStock, alwaysHave = false, purchasedDay = null, expiresDay = expires)

    private fun PantryViewModel.names() = uiState.value.sections.orEmpty().flatMap { s -> s.items.map { it.name } }

    @Test
    fun `a typed item is added in stock, today, in its aisle`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository()
        val vm = PantryViewModel(pantry, groceries, calendar)
        vm.onDraftChange(" rice ")
        vm.onAddTyped()
        advanceUntilIdle()

        val rice = pantry.items.value.single()
        assertEquals("rice", rice.name)
        assertEquals("en", rice.language)
        assertEquals(Aisle.GRAINS, rice.aisle)
        assertTrue(rice.inStock)
        assertEquals(calendar.today(), rice.purchasedDay)
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun `typing a name already here puts it back in stock instead of adding it twice`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "Rice", inStock = false)))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        vm.onDraftChange("rice")
        vm.onAddTyped()
        advanceUntilIdle()
        assertEquals(1, pantry.items.value.size)
        assertTrue(pantry.items.value.single().inStock)
    }

    @Test
    fun `search and sort rearrange what's shown`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(
            listOf(item(1, "milk", aisle = Aisle.DAIRY, expires = 20_725), item(2, "apples", aisle = Aisle.PRODUCE, expires = 20_730),
                item(3, "rice", aisle = Aisle.GRAINS))
        )
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        assertEquals(listOf("apples", "milk", "rice"), vm.names())

        vm.onSortChange(PantrySort.EXPIRY)
        assertEquals(listOf("milk", "apples", "rice"), vm.names())

        vm.onQueryChange("MI")
        assertEquals(listOf("milk"), vm.names())
        vm.onQueryChange("zzz")
        assertTrue(vm.uiState.value.sections!!.isEmpty())
        assertTrue(vm.uiState.value.hasItems)
    }

    @Test
    fun `running out puts the item on groceries silently, once, and marks it On list`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "milk"), item(2, "rice")))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        assertEquals(emptySet<Long>(), vm.uiState.value.onList)

        vm.onToggleStock(pantry.items.value.first())
        advanceUntilIdle()
        assertFalse(pantry.items.value.first().inStock)
        assertEquals(listOf("milk"), groceries.items.value.map { it.text })
        assertEquals(Aisle.DAIRY, groceries.items.value.single().aisle)
        assertNull(vm.uiState.value.message) // no snackbar
        assertEquals(setOf(1L), vm.uiState.value.onList)

        // Back in and out again: still one line on the list.
        vm.onToggleStock(pantry.items.value.first())
        advanceUntilIdle()
        vm.onToggleStock(pantry.items.value.first())
        advanceUntilIdle()
        assertEquals(listOf("milk"), groceries.items.value.map { it.text })
    }

    @Test
    fun `tapping On list takes only the item's own unticked line off the list`() = runTest(mainDispatcherRule.dispatcher) {
        groceries.add(listOf(NewGroceryLine(" Milk ", "en"), NewGroceryLine("1 cup milk", "en"), NewGroceryLine("flour", "en")))
        groceries.setChecked(listOf(groceries.items.value.last().id), true)
        val pantry = FakePantryRepository(listOf(item(1, "milk"), item(2, "flour", inStock = false), item(3, "rice")))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        // A recipe's "1 cup milk" isn't the item's own line, and a ticked line is already bought.
        assertEquals(setOf(1L), vm.uiState.value.onList)

        vm.onTakeOffList(pantry.items.value.first())
        advanceUntilIdle()
        assertEquals(listOf("1 cup milk", "flour"), groceries.items.value.map { it.text })
        assertEquals(emptySet<Long>(), vm.uiState.value.onList)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `back in stock is bought today, with no offer`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "milk", inStock = false)))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        vm.onToggleStock(pantry.items.value.single())
        advanceUntilIdle()
        assertTrue(pantry.items.value.single().inStock)
        assertEquals(calendar.today(), pantry.items.value.single().purchasedDay)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `the edit sheet saves quantity, staple and use-by date, and a blank name isn't saved`() =
        runTest(mainDispatcherRule.dispatcher) {
            val pantry = FakePantryRepository(listOf(item(1, "oil")))
            val vm = PantryViewModel(pantry, groceries, calendar)
            advanceUntilIdle()

            vm.onEdit(pantry.items.value.single())
            vm.onEditName("")
            vm.onEditSave()
            assertTrue(vm.uiState.value.editing != null) // still open

            vm.onEditName("olive oil")
            vm.onEditQuantity(" half a bottle ")
            vm.onEditAlwaysHave(true)
            vm.onEditExpiry(20_800)
            vm.onEditSave()
            advanceUntilIdle()

            val oil = pantry.items.value.single()
            assertEquals("olive oil", oil.name)
            assertEquals("half a bottle", oil.quantity)
            assertTrue(oil.alwaysHave)
            assertEquals(20_800L, oil.expiresDay)
            assertNull(vm.uiState.value.editing)
        }

    @Test
    fun `deleting from the edit sheet can be undone`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "oil"), item(2, "rice")))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()

        vm.onEdit(pantry.items.value.first())
        vm.onEditDelete()
        advanceUntilIdle()
        assertEquals(listOf("rice"), pantry.items.value.map { it.name })
        assertEquals("oil", (vm.uiState.value.message as PantryMessage.Deleted).name)

        vm.onUndoDelete()
        advanceUntilIdle()
        assertEquals(listOf("oil", "rice"), pantry.items.value.map { it.name })
    }
}
