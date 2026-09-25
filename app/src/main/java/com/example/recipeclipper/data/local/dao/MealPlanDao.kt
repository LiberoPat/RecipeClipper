package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MealTypeEntity
import com.example.recipeclipper.data.model.MealType
import kotlinx.coroutines.flow.Flow

/** One planned meal as the Week screen shows it: the entry, with its recipe's title and photo. */
data class PlannedMealRow(
    val id: Long,
    val day: Long,
    val mealTypeId: Long,
    val recipeId: Long?,
    val servings: Int?,
    val note: String?,
    val title: String?,
    val imageUrl: String?
)

/** The meal plan (#49): meal types and plan entries. */
@Dao
abstract class MealPlanDao {

    @Query("SELECT * FROM meal_types ORDER BY sortOrder ASC, id ASC")
    abstract fun observeMealTypes(): Flow<List<MealTypeEntity>>

    /**
     * Days [start] to [end] inclusive, by day, then meal type order, then the order meals were
     * added to their slot. A note has no recipe, so the recipe is a LEFT JOIN.
     */
    @Query(
        """
        SELECT e.id, e.day, e.mealTypeId, e.recipeId, e.servings, e.note, r.title, r.imageUrl
        FROM meal_plan_entries e
        JOIN meal_types t ON t.id = e.mealTypeId
        LEFT JOIN recipes r ON r.id = e.recipeId
        WHERE e.day BETWEEN :start AND :end
        ORDER BY e.day ASC, t.sortOrder ASC, t.id ASC, e.sortOrder ASC, e.id ASC
        """
    )
    abstract fun observeDays(start: Long, end: Long): Flow<List<PlannedMealRow>>

    @Query("SELECT * FROM meal_plan_entries WHERE id = :id")
    abstract suspend fun entry(id: Long): MealPlanEntryEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM meal_plan_entries WHERE day = :day AND mealTypeId = :mealTypeId")
    protected abstract suspend fun nextEntryOrder(day: Long, mealTypeId: Long): Int

    @Insert
    protected abstract suspend fun insertEntry(entry: MealPlanEntryEntity): Long

    /** Adds [entry] at the end of its day and meal type, whatever its sortOrder said. */
    @Transaction
    open suspend fun add(entry: MealPlanEntryEntity): Long =
        insertEntry(entry.copy(id = 0, sortOrder = nextEntryOrder(entry.day, entry.mealTypeId)))

    /** Undoes a delete: the same row back, id, uid and place included. */
    @Insert
    abstract suspend fun restore(entry: MealPlanEntryEntity)

    @Query("UPDATE meal_plan_entries SET day = :day, mealTypeId = :mealTypeId, sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    protected abstract suspend fun setSlot(id: Long, day: Long, mealTypeId: Long, sortOrder: Int, now: Long)

    /** Moves a meal to the end of another day or meal type. */
    @Transaction
    open suspend fun move(id: Long, day: Long, mealTypeId: Long, now: Long) {
        val current = entry(id) ?: return
        if (current.day == day && current.mealTypeId == mealTypeId) return
        setSlot(id, day, mealTypeId, nextEntryOrder(day, mealTypeId), now)
    }

    @Query("DELETE FROM meal_plan_entries WHERE id = :id")
    abstract suspend fun deleteEntry(id: Long)

    // --- Meal types

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM meal_types")
    protected abstract suspend fun nextTypeOrder(): Int

    @Insert
    protected abstract suspend fun insertType(type: MealTypeEntity): Long

    /** A user's own meal type, last in the order. */
    @Transaction
    open suspend fun addType(name: String, now: Long): Long =
        insertType(MealTypeEntity(name = name, builtInKey = null, sortOrder = nextTypeOrder(), updatedAt = now))

    /** Any type can be renamed, a seeded one included: its key, not its name, identifies it. */
    @Query("UPDATE meal_types SET name = :name, updatedAt = :now WHERE id = :id")
    abstract suspend fun renameType(id: Long, name: String, now: Long)

    @Query("UPDATE meal_types SET sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    protected abstract suspend fun setTypeOrder(id: Long, sortOrder: Int, now: Long)

    /** Numbers [orderedIds] 0, 1, 2… in the order given. */
    @Transaction
    open suspend fun reorderTypes(orderedIds: List<Long>, now: Long) {
        orderedIds.forEachIndexed { index, id -> setTypeOrder(id, index, now) }
    }

    /** Moves a user type's entries to Dinner. A seeded type's are never moved: see [deleteType]. */
    @Query(
        """
        UPDATE meal_plan_entries
        SET mealTypeId = (SELECT id FROM meal_types WHERE builtInKey = '${MealType.DINNER}'),
            updatedAt = :now
        WHERE mealTypeId = :id
          AND EXISTS(SELECT 1 FROM meal_types WHERE id = :id AND builtInKey IS NULL)
        """
    )
    protected abstract suspend fun moveEntriesToDinner(id: Long, now: Long)

    /** The guard is `builtInKey IS NULL`, in the SQL, so no code path deletes a seeded type. */
    @Query("DELETE FROM meal_types WHERE id = :id AND builtInKey IS NULL")
    protected abstract suspend fun deleteUserType(id: Long)

    /**
     * Deletes a user's meal type. Its meals are never deleted with it: they move to Dinner
     * first, in the same transaction. A seeded type is left alone.
     */
    @Transaction
    open suspend fun deleteType(id: Long, now: Long) {
        moveEntriesToDinner(id, now)
        deleteUserType(id)
    }
}
