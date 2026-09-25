package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.Menu
import com.example.recipeclipper.data.model.PlannedMeal
import kotlinx.coroutines.flow.Flow

/**
 * The week meal plan (#49): what is planned on which day, and the meal types it is sorted by.
 * Shaped like [ListRepository]: an interface so ViewModel tests hand it a fake, and every
 * write lands at once (nothing is submitted later).
 */
interface MealPlanRepository {

    /** Every meal type, in the user's order. */
    fun observeMealTypes(): Flow<List<MealType>>

    /** The meals planned from day [start] to [end] inclusive (epoch days), in plan order. */
    fun observeDays(start: Long, end: Long): Flow<List<PlannedMeal>>

    /** Plans [recipeId] on [day]; [servings] null means the recipe's own yield. */
    suspend fun addRecipe(recipeId: Long, day: Long, mealTypeId: Long, servings: Int?)

    /** Plans a free-text note on [day]. A blank note is ignored. */
    suspend fun addNote(note: String, day: Long, mealTypeId: Long)

    /** Moves a meal to another day or meal type, at the end of its new slot. */
    suspend fun move(entryId: Long, day: Long, mealTypeId: Long)

    /** What a delete removed, for [restore]. Opaque to callers. */
    data class DeletedMeal(val entity: MealPlanEntryEntity)

    /** Removes a meal from the plan (never the recipe). Null if it was already gone. */
    suspend fun delete(entryId: Long): DeletedMeal?

    /** Undoes [delete]: the same meal, in the same place. */
    suspend fun restore(deleted: DeletedMeal)

    /** A user's own meal type, last in the order. A blank name is ignored. */
    suspend fun addMealType(name: String)

    suspend fun renameMealType(id: Long, name: String)

    /** Saves the whole order at once, as the meal-types screen shows it. */
    suspend fun reorderMealTypes(orderedIds: List<Long>)

    /** Deletes a user's meal type; its meals move to Dinner. Seeded types are ignored. */
    suspend fun deleteMealType(id: Long)

    // --- Reusable weekly menus (#52)

    /** Every saved menu, by name. */
    fun observeMenus(): Flow<List<Menu>>

    /**
     * Saves the seven days from [weekStart] as a new menu called [name]. A blank name or an empty
     * week saves nothing; returns whether a menu was saved.
     */
    suspend fun saveWeekAsMenu(name: String, weekStart: Long): Boolean

    /**
     * Adds a menu's meals to the week from [weekStart], after what is planned there. Never
     * changes or removes a meal already planned. Returns how many meals it added.
     */
    suspend fun applyMenu(menuId: Long, weekStart: Long): Int

    /** A blank name is ignored. */
    suspend fun renameMenu(id: Long, name: String)

    /** Deletes a menu; the plan and the recipes are untouched. */
    suspend fun deleteMenu(id: Long)
}
