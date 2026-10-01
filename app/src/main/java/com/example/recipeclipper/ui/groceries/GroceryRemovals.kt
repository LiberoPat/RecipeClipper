package com.example.recipeclipper.ui.groceries

import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.model.NewPantryItem

/**
 * Removing from the grocery list, with undo (#146, #219, #234). One undo at a time: a second
 * removal settles the first. Each removal gives what the snackbar says ([RemovedGroceries]); its
 * Undo puts back the list's items and, after "Done shopping", the pantry as it was.
 */
class GroceryRemovals(
    private val repository: GroceryRepository,
    private val pantry: PantryRepository
) {

    /** What the snackbar's Undo puts back: the list's items and, after "Done shopping", the pantry. */
    class Undo(
        val groceries: GroceryRepository.DeletedItems?,
        val restocked: PantryRepository.Snapshot? = null,
        val added: List<Long> = emptyList()
    )

    private var undo: Undo? = null
    private var removals = 0L

    /** [deleted] left the list: one row's [label], the checked items ([label] null), or the whole list ([all]). */
    fun removed(deleted: GroceryRepository.DeletedItems, label: String?, all: Boolean = false): RemovedGroceries {
        undo = Undo(deleted)
        return RemovedGroceries(++removals, label, all = all)
    }

    /**
     * Restocks or adds [items], bought on [today], then clears every ticked line: one undo for it
     * all. Null when nothing changed, which leaves the last undo as it was.
     */
    suspend fun putAway(items: List<PutAwayItem>, today: Long): RemovedGroceries? {
        val restock = items.mapNotNull { it.trackedId }
        val restocked = if (restock.isEmpty()) null else pantry.snapshot(restock).also { pantry.restock(restock, today) }
        val added = items.filter { it.trackedId == null }
            .mapNotNull { pantry.add(NewPantryItem(it.name, it.language, it.aisle, purchasedDay = today)) }
        val cleared = repository.clearChecked()
        if (cleared == null && restocked == null && added.isEmpty()) return null
        undo = Undo(cleared, restocked, added)
        return RemovedGroceries(++removals, null, putAway = items.isNotEmpty())
    }

    /** The snackbar's Undo: the last removal's undo, taken, for [restore]; null when there's none. */
    fun takeUndo(): Undo? = undo.also { undo = null }

    /** Puts back what [last] took: the items and, after "Done shopping", the pantry as it was. */
    suspend fun restore(last: Undo) {
        last.groceries?.let { repository.restore(it) }
        last.restocked?.let { pantry.restore(it) }
        last.added.forEach { pantry.delete(it) }
    }

    /** The snackbar timed out or was dismissed: the removal stands. */
    fun settle() {
        undo = null
    }
}
