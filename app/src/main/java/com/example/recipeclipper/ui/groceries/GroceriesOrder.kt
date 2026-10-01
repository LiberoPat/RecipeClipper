package com.example.recipeclipper.ui.groceries

import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.GroceryCombiner
import com.example.recipeclipper.data.model.GroceryItem

/**
 * The visit's order (#219): [GroceryCombiner.sections] laid out as if each item were ticked as in
 * [ticks] (an item not in it, as it is), then shown with every item's tick as it is now. A row
 * ticks all its lines, so its lines share one tick and it stays one row.
 */
internal object GroceriesOrder {
    fun sections(items: List<GroceryItem>, decisions: Decisions, ticks: Map<Long, Boolean>): List<GroceryCombiner.Section> {
        val now = items.associate { it.id to it.checked }
        val asWas = items.map { item -> ticks[item.id]?.let { if (it == item.checked) item else item.copy(checked = it) } ?: item }
        fun GroceryItem.current() = now[id]?.let { if (it == checked) this else copy(checked = it) } ?: this
        return GroceryCombiner.sections(asWas, decisions).map { section ->
            section.copy(
                rows = section.rows.map { row ->
                    when (row) {
                        is GroceryCombiner.Row.Single -> GroceryCombiner.Row.Single(row.item.current())
                        is GroceryCombiner.Row.Combined -> row.copy(items = row.items.map { it.current() })
                        is GroceryCombiner.Row.Together -> row.copy(items = row.items.map { it.current() })
                    }
                }
            )
        }
    }
}

/**
 * **Ticks stay put** (#219, #234): within a visit, ticking or unticking never re-sorts the list.
 * The order is worked out with each item's tick as it was when the order was last worked out,
 * and shown with its tick now ([GroceriesOrder]). It's worked out afresh when anything but a tick
 * changes (an item added, removed, cleared, or moved to another aisle, here or elsewhere) and
 * after [reset], when the screen is left, so ticked rows sink to the bottom of their aisle next time.
 */
internal class VisitOrder {

    // Each item's tick when the order was last worked out, and the list then with its ticks left
    // out; null until worked out, and after the screen is left.
    private var ticks: Map<Long, Boolean>? = null
    private var shape: List<GroceryItem>? = null

    /** [items] laid out in the visit's order, working it out afresh when more than a tick changed. */
    fun sections(items: List<GroceryItem>, decisions: Decisions): List<GroceryCombiner.Section> {
        val shape = items.map { if (it.checked) it.copy(checked = false) else it }
        val ticks = this.ticks?.takeIf { shape == this.shape } ?: items.associate { it.id to it.checked }.also {
            this.ticks = it
            this.shape = shape
        }
        return GroceriesOrder.sections(items, decisions, ticks)
    }

    /** The screen was left: the next layout works the order out afresh. */
    fun reset() {
        ticks = null
    }
}
