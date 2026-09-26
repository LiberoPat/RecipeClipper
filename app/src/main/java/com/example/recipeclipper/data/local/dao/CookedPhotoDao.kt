package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.recipeclipper.data.local.entity.CookedPhotoEntity
import kotlinx.coroutines.flow.Flow

/** "I made this" (#116): a recipe's own photos, newest cook first. */
@Dao
abstract class CookedPhotoDao {

    @Query("SELECT * FROM cooked_photos WHERE recipeId = :recipeId ORDER BY day DESC, createdAt DESC, id DESC")
    abstract fun observeFor(recipeId: Long): Flow<List<CookedPhotoEntity>>

    @Query("SELECT * FROM cooked_photos WHERE recipeId = :recipeId ORDER BY day DESC, createdAt DESC, id DESC")
    abstract suspend fun photosFor(recipeId: Long): List<CookedPhotoEntity>

    @Query("SELECT * FROM cooked_photos WHERE id = :id")
    abstract suspend fun get(id: Long): CookedPhotoEntity?

    /** Every file a row still names: what the orphan sweep must leave alone. */
    @Query("SELECT fileName FROM cooked_photos")
    abstract suspend fun fileNames(): List<String>

    @Insert
    abstract suspend fun insert(photo: CookedPhotoEntity): Long

    @Query("UPDATE cooked_photos SET day = :day, note = :note, updatedAt = :now WHERE id = :id")
    abstract suspend fun edit(id: Long, day: Long, note: String?, now: Long)

    @Query("DELETE FROM cooked_photos WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** Undoes a delete (the photo's own, or its recipe's): the rows exactly as they were. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(photos: List<CookedPhotoEntity>)
}
