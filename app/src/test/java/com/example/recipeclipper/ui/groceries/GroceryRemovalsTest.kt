package com.example.recipeclipper.ui.groceries

import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GroceryRemovals] on its own (#146, #219, #234): removals and their one undo. */
class GroceryRemovalsTest {

    private val groceries = FakeGroceryRepository()
    private val butter = PantryItem(1, "Butter", null, "en", Aisle.DAIRY, inStock = false, alwaysHave = false, purchasedDay = 1, expiresDay = null)
    private val pantry = FakePantryRepository(listOf(butter))
    private val removals = GroceryRemovals(groceries, pantry)

    @Test fun `each removal is numbered and a second one settles the first`() = runTest {
        groceries.add(listOf(NewGroceryLine("2 onions", "en"), NewGroceryLine("1 leek", "en")))
        val (onions, leek) = groceries.items.value

        val first = removals.removed(groceries.delete(listOf(onions.id))!!, "2 onions")
        val second = removals.removed(groceries.delete(listOf(leek.id))!!, null, all = true)

        assertEquals(RemovedGroceries(1, "2 onions"), first)
        assertEquals(RemovedGroceries(2, null, all = true), second)
        removals.restore(removals.takeUndo()!!)
        assertEquals(listOf("1 leek"), groceries.items.value.map { it.text })
        assertNull(removals.takeUndo())
    }

    @Test fun `done shopping restocks, adds and clears, and one undo puts it all back`() = runTest {
        groceries.add(listOf(NewGroceryLine("250 g butter", "en"), NewGroceryLine("2 cups flour", "en")))
        groceries.setChecked(groceries.items.value.map { it.id }, true)
        val before = groceries.items.value
        val items = listOf(
            PutAwayItem("pantry-1", "Butter", "en", Aisle.DAIRY, trackedId = 1),
            PutAwayItem("new-en-flour", "flour", "en", Aisle.BAKING, trackedId = null)
        )

        val removed = removals.putAway(items, today = 5)

        assertEquals(RemovedGroceries(1, null, putAway = true), removed)
        assertTrue(groceries.items.value.isEmpty())
        assertEquals(listOf("Butter" to true, "flour" to true), pantry.items.value.map { it.name to it.inStock })

        removals.restore(removals.takeUndo()!!)
        assertEquals(before.map { it.text }, groceries.items.value.map { it.text })
        assertEquals(listOf(butter), pantry.items.value)
    }

    @Test fun `putting nothing away with nothing ticked changes nothing and keeps the last undo`() = runTest {
        groceries.add(listOf(NewGroceryLine("2 onions", "en")))
        removals.removed(groceries.delete(groceries.items.value.map { it.id })!!, "2 onions")

        assertNull(removals.putAway(emptyList(), today = 5))
        assertNotNull(removals.takeUndo())
    }

    @Test fun `a removal that stands has nothing to undo`() = runTest {
        groceries.add(listOf(NewGroceryLine("2 onions", "en")))
        removals.removed(groceries.delete(groceries.items.value.map { it.id })!!, "2 onions")

        removals.settle()

        assertNull(removals.takeUndo())
        assertFalse(groceries.items.value.any())
    }
}
