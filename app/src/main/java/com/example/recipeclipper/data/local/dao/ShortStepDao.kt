package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.recipeclipper.data.local.entity.ShortStepEntity
import kotlinx.coroutines.flow.Flow

/** Chef mode's cache of short steps (#100). */
@Dao
interface ShortStepDao {

    /** The rows for [recipeId] written in [language], then every change. */
    @Query("SELECT * FROM short_steps WHERE recipeId = :recipeId AND language = :language")
    fun observe(recipeId: Long, language: String): Flow<List<ShortStepEntity>>

    /** IGNORE: a step already written keeps its row (and its uid). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ShortStepEntity)

    /** Drops [recipeId]'s rows for steps it no longer has, or written in another language. */
    @Query(
        "DELETE FROM short_steps WHERE recipeId = :recipeId " +
            "AND (language != :language OR stepHash NOT IN (:keep))"
    )
    suspend fun prune(recipeId: Long, language: String, keep: List<String>)
}
