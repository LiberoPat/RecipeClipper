package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.GroceryItem
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PlannedIngredients
import kotlinx.coroutines.flow.Flow

/**
 * The grocery list (#50). Shaped like [ListRepository]: an interface so ViewModel tests hand it
 * a fake, and every write lands at once.
 */
interface GroceryRepository {

    /** Every item on the list, in the order added. */
    fun observeItems(): Flow<List<GroceryItem>>

    /** Adds [lines] at the end of the list, each in the aisle its name belongs to. Blank lines are skipped. */
    suspend fun add(lines: List<NewGroceryLine>)

    suspend fun setChecked(ids: List<Long>, checked: Boolean)

    /** Moves items to another aisle: the user's choice, kept from then on. */
    suspend fun setAisle(ids: List<Long>, aisle: Aisle)

    /** What a delete removed, for [restore]. Opaque to callers. */
    data class DeletedItems(val entities: List<GroceryItemEntity>)

    /** Deletes items; null when none were there. */
    suspend fun delete(ids: List<Long>): DeletedItems?

    /** Deletes every checked item; null when none was checked. */
    suspend fun clearChecked(): DeletedItems?

    /** Undoes [delete] or [clearChecked]: the same items, in the same places. */
    suspend fun restore(deleted: DeletedItems)

    /** The recipes planned from day [start] to [end] inclusive, with their ingredients. */
    suspend fun plannedIngredients(start: Long, end: Long): List<PlannedIngredients>
}
