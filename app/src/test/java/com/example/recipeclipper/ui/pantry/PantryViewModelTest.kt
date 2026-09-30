package com.example.recipeclipper.ui.pantry

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantrySort
import com.example.recipeclipper.data.model.PantryStock
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

    // #149: "Send list" sends what's in stock, as the sort arranges it, whatever the search.
    @Test
    fun `Send list is what's in stock, by the screen's sort, whatever the search`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(
            listOf(
                item(1, "milk", aisle = Aisle.DAIRY, expires = 20_730), item(2, "apples", aisle = Aisle.PRODUCE),
                item(3, "rice", aisle = Aisle.GRAINS, expires = 20_725), item(4, "oats", inStock = false, aisle = Aisle.GRAINS)
            )
        )
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasInStock)

        vm.onQueryChange("milk")
        assertEquals("Pantry\n\nproduce\n- apples\n\ndairy\n- milk\n\ngrains\n- rice", vm.shareText("Pantry") { it.key })
        vm.onSortChange(PantrySort.EXPIRY)
        assertEquals("Pantry\n\n- rice\n- milk\n- apples", vm.shareText("Pantry") { it.key })
    }

    @Test
    fun `with nothing in stock there's nothing to send`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "milk", inStock = false)))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasInStock)
        assertNull(vm.shareText("Pantry") { it.key })

        vm.onSetStock(pantry.items.value.single(), PantryStock.IN_STOCK)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasInStock)
        assertEquals("Pantry\n\nother\n- milk", vm.shareText("Pantry") { it.key })
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

        vm.onSetStock(pantry.items.value.first(), PantryStock.RUN_OUT)
        advanceUntilIdle()
        assertFalse(pantry.items.value.first().inStock)
        assertEquals(listOf("milk"), groceries.items.value.map { it.text })
        assertEquals(Aisle.DAIRY, groceries.items.value.single().aisle)
        assertNull(vm.uiState.value.message) // no snackbar
        assertEquals(setOf(1L), vm.uiState.value.onList)

        // Back in and out again: still one line on the list.
        vm.onSetStock(pantry.items.value.first(), PantryStock.IN_STOCK)
        advanceUntilIdle()
        vm.onSetStock(pantry.items.value.first(), PantryStock.RUN_OUT)
        advanceUntilIdle()
        assertEquals(listOf("milk"), groceries.items.value.map { it.text })
    }

    // #194: running low is still in stock, and goes on the list like running out.
    @Test
    fun `running low keeps it in stock, puts it on the list once, and Ran out then moves it to Run out`() =
        runTest(mainDispatcherRule.dispatcher) {
            val pantry = FakePantryRepository(listOf(item(1, "milk", aisle = Aisle.DAIRY), item(2, "rice", aisle = Aisle.GRAINS)))
            val vm = PantryViewModel(pantry, groceries, calendar)
            advanceUntilIdle()

            vm.onSetStock(pantry.items.value.first(), PantryStock.RUNNING_LOW)
            advanceUntilIdle()
            val milk = pantry.items.value.first()
            assertEquals(PantryStock.RUNNING_LOW, milk.stock)
            assertTrue(milk.inStock)
            assertEquals(listOf("milk"), groceries.items.value.map { it.text })
            assertEquals(setOf(1L), vm.uiState.value.onList)
            assertEquals(listOf(Aisle.DAIRY, Aisle.GRAINS), vm.uiState.value.sections!!.map { it.aisle })
            assertTrue(vm.uiState.value.hasInStock)

            vm.onSetStock(pantry.items.value.first(), PantryStock.RUN_OUT)
            advanceUntilIdle()
            assertEquals(PantryStock.RUN_OUT, pantry.items.value.first().stock)
            assertEquals(listOf("milk"), groceries.items.value.map { it.text })
            val sections = vm.uiState.value.sections!!
            assertEquals(listOf(false, true), sections.map { it.runOut })
            assertEquals(listOf("milk"), sections.last().items.map { it.name })

            // Restock: in stock again, not running low, bought today.
            vm.onSetStock(pantry.items.value.first(), PantryStock.IN_STOCK)
            advanceUntilIdle()
            assertEquals(PantryStock.IN_STOCK, pantry.items.value.first().stock)
            assertEquals(calendar.today(), pantry.items.value.first().purchasedDay)
        }

    @Test
    fun `restocking a running-low item makes it plain in stock`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "milk").copy(runningLow = true)))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        vm.onSetStock(pantry.items.value.single(), PantryStock.IN_STOCK)
        advanceUntilIdle()
        assertEquals(PantryStock.IN_STOCK, pantry.items.value.single().stock)
        assertTrue(groceries.items.value.isEmpty())
    }

    // Owner, 2026-09-29: the tag is any unticked line naming the item, in any stock state, and
    // tapping it removes them all (a recipe's too), with Undo. Running out still checks only for
    // the item's own line.
    @Test
    fun `tapping On list takes every unticked line naming the item off the list, with Undo`() = runTest(mainDispatcherRule.dispatcher) {
        groceries.add(
            listOf(
                NewGroceryLine(" Milk ", "en"), NewGroceryLine("1 cup milk", "en", recipeId = 7), NewGroceryLine("flour", "en"),
                NewGroceryLine("2 tbsp coconut milk", "en")
            )
        )
        groceries.setChecked(listOf(groceries.items.value[2].id), true)
        val pantry = FakePantryRepository(listOf(item(1, "milk"), item(2, "flour", inStock = false), item(3, "rice")))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        // A ticked line is already bought; coconut milk is another ingredient.
        assertEquals(setOf(1L), vm.uiState.value.onList)
        val before = groceries.items.value

        vm.onTakeOffList(pantry.items.value.first())
        advanceUntilIdle()
        assertEquals(listOf("flour", "2 tbsp coconut milk"), groceries.items.value.map { it.text })
        assertEquals(emptySet<Long>(), vm.uiState.value.onList)
        assertEquals(2, (vm.uiState.value.message as PantryMessage.TakenOffList).count)

        vm.onUndoDelete()
        advanceUntilIdle()
        assertEquals(before, groceries.items.value)
        assertEquals(setOf(1L), vm.uiState.value.onList)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `a typed line naming the item shows the tag in stock, and running out still adds its own line`() =
        runTest(mainDispatcherRule.dispatcher) {
            groceries.add(listOf(NewGroceryLine("2 onions", "en"), NewGroceryLine("1 red onion", "en")))
            val pantry = FakePantryRepository(listOf(item(1, "onions", aisle = Aisle.PRODUCE)))
            val vm = PantryViewModel(pantry, groceries, calendar)
            advanceUntilIdle()
            assertEquals(setOf(1L), vm.uiState.value.onList)

            vm.onSetStock(pantry.items.value.single(), PantryStock.RUN_OUT)
            advanceUntilIdle()
            assertEquals(listOf("2 onions", "1 red onion", "onions"), groceries.items.value.map { it.text })
        }

    @Test
    fun `back in stock is bought today, with no offer`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "milk", inStock = false)))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        vm.onSetStock(pantry.items.value.single(), PantryStock.IN_STOCK)
        advanceUntilIdle()
        assertTrue(pantry.items.value.single().inStock)
        assertEquals(calendar.today(), pantry.items.value.single().purchasedDay)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `the edit sheet's stock control applies at once, like the row's button`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "garlic", aisle = Aisle.PRODUCE)))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        vm.onEdit(pantry.items.value.single())
        assertEquals(PantryStock.IN_STOCK, vm.uiState.value.editing?.stock)

        vm.onEditStock(PantryStock.RUNNING_LOW)
        assertEquals(PantryStock.RUNNING_LOW, vm.uiState.value.editing?.stock)
        advanceUntilIdle()
        assertEquals(PantryStock.RUNNING_LOW, pantry.items.value.single().stock)
        assertEquals(listOf("garlic"), groceries.items.value.map { it.text })
        assertTrue(vm.uiState.value.editing != null)

        vm.onEditStock(PantryStock.IN_STOCK)
        advanceUntilIdle()
        assertEquals(PantryStock.IN_STOCK, pantry.items.value.single().stock)
        assertEquals(calendar.today(), pantry.items.value.single().purchasedDay)
        // Dismissing the sheet keeps the stock: it was never waiting for Save.
        vm.onEditDismissed()
        advanceUntilIdle()
        assertEquals(PantryStock.IN_STOCK, pantry.items.value.single().stock)
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

    // --- #194: "Clear run-out items" in the menu.

    @Test
    fun `Clear run-out items is enabled only while something has run out`() = runTest(mainDispatcherRule.dispatcher) {
        val pantry = FakePantryRepository(listOf(item(1, "oil"), item(2, "rice")))
        val vm = PantryViewModel(pantry, groceries, calendar)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasRunOut)
        vm.onClearRunOut()
        assertNull(vm.uiState.value.confirmClearRunOut)

        vm.onSetStock(pantry.items.value.first(), PantryStock.RUNNING_LOW)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasRunOut)
        vm.onSetStock(pantry.items.value.last(), PantryStock.RUN_OUT)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasRunOut)
    }

    @Test
    fun `Clear run-out items asks first, removes only what ran out, leaves the grocery list, and can be undone`() =
        runTest(mainDispatcherRule.dispatcher) {
            val pantry = FakePantryRepository(
                listOf(
                    item(1, "oil"), item(2, "milk", inStock = false, aisle = Aisle.DAIRY),
                    item(3, "rice", inStock = false, aisle = Aisle.GRAINS, expires = 20_800), item(4, "flour")
                )
            )
            groceries.add(listOf(NewGroceryLine("milk", "en")))
            val vm = PantryViewModel(pantry, groceries, calendar)
            // A search that hides one of them: the count is still every run-out item.
            vm.onQueryChange("milk")
            advanceUntilIdle()
            val before = pantry.items.value

            vm.onClearRunOut()
            assertEquals(2, vm.uiState.value.confirmClearRunOut)
            vm.onClearRunOutDismissed()
            advanceUntilIdle()
            assertNull(vm.uiState.value.confirmClearRunOut)
            assertEquals(4, pantry.items.value.size)

            vm.onClearRunOut()
            vm.onClearRunOutConfirm()
            advanceUntilIdle()
            assertNull(vm.uiState.value.confirmClearRunOut)
            assertEquals(listOf("oil", "flour"), pantry.items.value.map { it.name })
            assertFalse(vm.uiState.value.hasRunOut)
            assertTrue(vm.uiState.value.message is PantryMessage.RunOutCleared)
            assertEquals(listOf("milk"), groceries.items.value.map { it.text })

            vm.onUndoDelete()
            advanceUntilIdle()
            assertEquals(before, pantry.items.value)
            assertNull(vm.uiState.value.message)
            assertTrue(vm.uiState.value.hasRunOut)
        }
}
