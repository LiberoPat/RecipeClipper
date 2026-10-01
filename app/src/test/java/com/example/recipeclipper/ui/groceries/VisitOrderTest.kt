package com.example.recipeclipper.ui.groceries

import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.GroceryItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** [VisitOrder] on its own (#219, #234): ticks stay put within a visit. */
class VisitOrderTest {

    private val order = VisitOrder()

    private fun item(id: Long, text: String, checked: Boolean = false) =
        GroceryItem(id, text, "en", Aisle.PRODUCE, checked, sortOrder = id.toInt())

    private fun VisitOrder.texts(items: List<GroceryItem>) =
        sections(items, Decisions.NONE).flatMap { s -> s.rows.flatMap { r -> r.items.map { it.text to it.checked } } }

    private val onions = item(1, "2 onions")
    private val carrots = item(2, "3 carrots")

    @Test fun `a tick shows at once but keeps its row where it was`() {
        order.texts(listOf(onions, carrots))

        val ticked = order.texts(listOf(onions.copy(checked = true), carrots))

        assertEquals(listOf("2 onions" to true, "3 carrots" to false), ticked)
    }

    @Test fun `after the screen is left ticked rows sink to the bottom of their aisle`() {
        order.texts(listOf(onions, carrots))
        order.texts(listOf(onions.copy(checked = true), carrots))

        order.reset()

        assertEquals(listOf("3 carrots" to false, "2 onions" to true), order.texts(listOf(onions.copy(checked = true), carrots)))
    }

    @Test fun `anything but a tick works the order out afresh`() {
        order.texts(listOf(onions, carrots))
        order.texts(listOf(onions.copy(checked = true), carrots))

        val added = order.texts(listOf(onions.copy(checked = true), carrots, item(3, "1 leek")))

        assertEquals(listOf("3 carrots" to false, "1 leek" to false, "2 onions" to true), added)
    }
}
