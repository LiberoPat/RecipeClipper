package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.recipeclipper.data.local.entity.AiDecisionEntity
import kotlinx.coroutines.flow.Flow

/** The cache of the on-device model's typed decisions (#104). */
@Dao
interface AiDecisionDao {

    /** Every cached answer, then every change. Small: one short row per question ever asked. */
    @Query("SELECT * FROM ai_decisions")
    fun observe(): Flow<List<AiDecisionEntity>>

    @Query("SELECT answer FROM ai_decisions WHERE kind = :kind AND input = :input AND language = :language")
    suspend fun answer(kind: String, input: String, language: String): String?

    /** IGNORE: a question already answered keeps its row (and its uid). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: AiDecisionEntity)
}
