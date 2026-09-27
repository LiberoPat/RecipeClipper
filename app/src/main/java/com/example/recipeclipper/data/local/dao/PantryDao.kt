package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import kotlinx.coroutines.flow.Flow

/** The pantry (#51). Sorting and searching are done in memory ([com.example.recipeclipper.data.model.PantryList]). */
@Dao
abstract class PantryDao {

    @Query("SELECT * FROM pantry_items ORDER BY id ASC")
    abstract fun observeItems(): Flow<List<PantryItemEntity>>

    @Query("SELECT * FROM pantry_items ORDER BY id ASC")
    abstract suspend fun items(): List<PantryItemEntity>

    @Query("SELECT * FROM pantry_items WHERE id = :id")
    abstract suspend fun item(id: Long): PantryItemEntity?

    @Insert
    abstract suspend fun insert(item: PantryItemEntity): Long

    @Query("UPDATE pantry_items SET inStock = :inStock, updatedAt = :now WHERE id IN (:ids)")
    abstract suspend fun setInStock(ids: List<Long>, inStock: Boolean, now: Long)

    /** Back in stock, bought on [day]. */
    @Query("UPDATE pantry_items SET inStock = 1, purchasedDay = :day, updatedAt = :now WHERE id IN (:ids)")
    abstract suspend fun restock(ids: List<Long>, day: Long, now: Long)

    @Query(
        "UPDATE pantry_items SET name = :name, quantity = :quantity, alwaysHave = :alwaysHave, " +
            "expiresDay = :expiresDay, updatedAt = :now WHERE id = :id"
    )
    abstract suspend fun edit(id: Long, name: String, quantity: String?, alwaysHave: Boolean, expiresDay: Long?, now: Long)

    @Query("DELETE FROM pantry_items WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** Undoes a delete, or a restock, or a stock change: the row exactly as it was. */
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    abstract suspend fun put(items: List<PantryItemEntity>)
}
