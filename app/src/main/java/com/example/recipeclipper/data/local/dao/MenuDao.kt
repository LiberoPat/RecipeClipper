package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MenuEntity
import com.example.recipeclipper.data.local.entity.MenuEntryEntity
import com.example.recipeclipper.data.local.entity.newUid
import kotlinx.coroutines.flow.Flow

/** A menu as the Week screen's menu sheet shows it, with how many meals it holds. */
data class MenuRow(val id: Long, val name: String, val mealCount: Int)

/**
 * Reusable weekly menus (#52). Saving copies a week's meals into a new menu; applying copies a
 * menu's meals into a week, after what is already planned there. Neither ever changes or
 * removes a meal that is already on the plan.
 */
@Dao
abstract class MenuDao {
    @Query(
        """
        SELECT m.id, m.name, (SELECT COUNT(*) FROM menu_entries e WHERE e.menuId = m.id) AS mealCount
        FROM menus m
        ORDER BY m.name COLLATE NOCASE ASC, m.id ASC
        """
    )
    abstract fun observeMenus(): Flow<List<MenuRow>>

    @Query("SELECT * FROM menu_entries WHERE menuId = :menuId ORDER BY dayOffset ASC, mealTypeId ASC, sortOrder ASC, id ASC")
    abstract suspend fun entries(menuId: Long): List<MenuEntryEntity>

    @Query("SELECT * FROM meal_plan_entries WHERE day BETWEEN :start AND :end ORDER BY day ASC, mealTypeId ASC, sortOrder ASC, id ASC")
    protected abstract suspend fun planEntries(start: Long, end: Long): List<MealPlanEntryEntity>

    @Insert
    protected abstract suspend fun insertMenu(menu: MenuEntity): Long

    @Insert
    protected abstract suspend fun insertEntries(entries: List<MenuEntryEntity>)

    /**
     * Saves the seven days from [weekStart] as a new menu called [name], and returns its id.
     * An empty week saves nothing and returns null.
     */
    @Transaction
    open suspend fun saveWeek(name: String, weekStart: Long, now: Long): Long? {
        val meals = planEntries(weekStart, weekStart + 6)
        if (meals.isEmpty()) return null
        val menuId = insertMenu(MenuEntity(name = name, updatedAt = now))
        insertEntries(
            meals.mapIndexed { index, meal ->
                MenuEntryEntity(
                    menuId = menuId,
                    dayOffset = (meal.day - weekStart).toInt(),
                    mealTypeId = meal.mealTypeId,
                    recipeId = meal.recipeId,
                    servings = meal.servings,
                    note = meal.note,
                    sortOrder = index,
                    updatedAt = now
                )
            }
        )
        return menuId
    }

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM meal_plan_entries WHERE day = :day AND mealTypeId = :mealTypeId")
    protected abstract suspend fun nextPlanOrder(day: Long, mealTypeId: Long): Int

    @Insert
    protected abstract suspend fun insertPlanEntry(entry: MealPlanEntryEntity): Long

    /**
     * Adds every meal of [menuId] to the week from [weekStart], each at the end of its day and
     * meal type. It only adds: what is planned already stays as it is. Returns how many it added.
     */
    @Transaction
    open suspend fun apply(menuId: Long, weekStart: Long, now: Long): Int {
        val meals = entries(menuId)
        meals.forEach { meal ->
            val day = weekStart + meal.dayOffset
            insertPlanEntry(
                MealPlanEntryEntity(
                    day = day,
                    mealTypeId = meal.mealTypeId,
                    recipeId = meal.recipeId,
                    servings = meal.servings,
                    note = meal.note,
                    sortOrder = nextPlanOrder(day, meal.mealTypeId),
                    updatedAt = now,
                    uid = newUid()
                )
            )
        }
        return meals.size
    }

    @Query("UPDATE menus SET name = :name, updatedAt = :now WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String, now: Long)

    /** Deletes a menu and its meals (cascade). The plan and the recipes are untouched. */
    @Query("DELETE FROM menus WHERE id = :id")
    abstract suspend fun delete(id: Long)
}
