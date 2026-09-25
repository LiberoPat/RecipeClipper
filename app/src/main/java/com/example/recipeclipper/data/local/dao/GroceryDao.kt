package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * A planned recipe's ingredients, for "Add this week's ingredients" (#50): the entry's day and
 * servings with the recipe's lines, yield and language.
 */
data class PlannedIngredientsRow(
    val entryId: Long,
    val day: Long,
    val servings: Int?,
    val recipeId: Long,
    val title: String,
    val ingredients: List<String>,
    val yield: String?,
    val language: String?
)

/** The grocery list (#50). One list for now ([GroceryItemEntity.DEFAULT_LIST]). */
@Dao
abstract class GroceryDao {

    @Query("SELECT * FROM grocery_items WHERE listId = :listId ORDER BY sortOrder ASC, id ASC")
    abstract fun observeItems(listId: Long = GroceryItemEntity.DEFAULT_LIST): Flow<List<GroceryItemEntity>>

    @Query("SELECT * FROM grocery_items WHERE id IN (:ids)")
    abstract suspend fun items(ids: List<Long>): List<GroceryItemEntity>

    @Query("SELECT * FROM grocery_items WHERE listId = :listId AND checked = 1")
    abstract suspend fun checkedItems(listId: Long = GroceryItemEntity.DEFAULT_LIST): List<GroceryItemEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM grocery_items WHERE listId = :listId")
    protected abstract suspend fun nextOrder(listId: Long): Int

    @Insert
    protected abstract suspend fun insert(items: List<GroceryItemEntity>)

    /** Adds [items] at the end of their list, in the order given, whatever their sortOrder said. */
    @Transaction
    open suspend fun add(items: List<GroceryItemEntity>) {
        if (items.isEmpty()) return
        val start = nextOrder(items.first().listId)
        insert(items.mapIndexed { index, item -> item.copy(id = 0, sortOrder = start + index) })
    }

    /** Undoes a delete: the same rows back, ids, uids and places included. */
    @Insert
    abstract suspend fun restore(items: List<GroceryItemEntity>)

    @Query("UPDATE grocery_items SET checked = :checked, updatedAt = :now WHERE id IN (:ids)")
    abstract suspend fun setChecked(ids: List<Long>, checked: Boolean, now: Long)

    @Query("UPDATE grocery_items SET aisle = :aisle, updatedAt = :now WHERE id IN (:ids)")
    abstract suspend fun setAisle(ids: List<Long>, aisle: String, now: Long)

    @Query("DELETE FROM grocery_items WHERE id IN (:ids)")
    abstract suspend fun delete(ids: List<Long>)

    /**
     * Every recipe planned from day [start] to [end] inclusive, in plan order (as the Week shows
     * it). Notes have no ingredients, so they are left out.
     */
    @Query(
        """
        SELECT e.id AS entryId, e.day, e.servings, r.id AS recipeId, r.title, r.ingredients,
               r.servings AS yield, r.language
        FROM meal_plan_entries e
        JOIN meal_types t ON t.id = e.mealTypeId
        JOIN recipes r ON r.id = e.recipeId
        WHERE e.day BETWEEN :start AND :end
        ORDER BY e.day ASC, t.sortOrder ASC, t.id ASC, e.sortOrder ASC, e.id ASC
        """
    )
    abstract suspend fun plannedIngredients(start: Long, end: Long): List<PlannedIngredientsRow>
}
