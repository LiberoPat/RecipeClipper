package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
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

    /** The stock state (#194); [runningLow] only with [inStock]. */
    @Query("UPDATE pantry_items SET inStock = :inStock, runningLow = :runningLow, updatedAt = :now WHERE id IN (:ids)")
    abstract suspend fun setStock(ids: List<Long>, inStock: Boolean, runningLow: Boolean, now: Long)

    /** Back in stock (no longer running low), bought on [day]. */
    @Query("UPDATE pantry_items SET inStock = 1, runningLow = 0, purchasedDay = :day, updatedAt = :now WHERE id IN (:ids)")
    abstract suspend fun restock(ids: List<Long>, day: Long, now: Long)

    @Query(
        "UPDATE pantry_items SET name = :name, quantity = :quantity, alwaysHave = :alwaysHave, " +
            "expiresDay = :expiresDay, updatedAt = :now WHERE id = :id"
    )
    abstract suspend fun edit(id: Long, name: String, quantity: String?, alwaysHave: Boolean, expiresDay: Long?, now: Long)

    @Query("DELETE FROM pantry_items WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT * FROM pantry_items WHERE inStock = 0 ORDER BY id ASC")
    protected abstract suspend fun runOutItems(): List<PantryItemEntity>

    @Query("DELETE FROM pantry_items WHERE inStock = 0")
    protected abstract suspend fun deleteAllRunOut()

    /** "Clear run-out items" (#194): deletes every item that has run out, returning them whole for undo. */
    @Transaction
    open suspend fun deleteRunOut(): List<PantryItemEntity> {
        val gone = runOutItems()
        if (gone.isNotEmpty()) deleteAllRunOut()
        return gone
    }

    /** Undoes a delete, or a restock, or a stock change: the row exactly as it was. */
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    abstract suspend fun put(items: List<PantryItemEntity>)
}
