package com.example.recipeclipper.fake

import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.PlannedMeal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * A fake that models the plan rather than only recording calls: [meals] and [types] are real
 * state, [observeDays] filters and orders them as the SQL does (day, then meal type order,
 * then insertion), and deleting a type moves its meals to Dinner, as the DAO does. Recipe
 * titles for new meals come from [titles].
 */
class FakeMealPlanRepository : MealPlanRepository {

    val types = MutableStateFlow(DEFAULT_TYPES)
    val meals = MutableStateFlow<List<PlannedMeal>>(emptyList())

    /** recipeId → title, for meals added through [addRecipe]. */
    val titles = mutableMapOf<Long, String>()

    private var nextId = 1000L

    override fun observeMealTypes(): Flow<List<MealType>> = types

    override fun observeDays(start: Long, end: Long): Flow<List<PlannedMeal>> =
        combine(meals, types) { all, order ->
            val rank = order.withIndex().associate { (i, t) -> t.id to i }
            all.filter { it.day in start..end }
                .withIndex()
                .sortedWith(compareBy({ it.value.day }, { rank[it.value.mealTypeId] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
        }

    override suspend fun addRecipe(recipeId: Long, day: Long, mealTypeId: Long, servings: Int?) {
        meals.value = meals.value + PlannedMeal(
            id = nextId++, day = day, mealTypeId = mealTypeId, recipeId = recipeId,
            title = titles[recipeId] ?: "Recipe $recipeId", imageUrl = null, servings = servings, note = null
        )
    }

    override suspend fun addNote(note: String, day: Long, mealTypeId: Long) {
        if (note.isBlank()) return
        meals.value = meals.value + PlannedMeal(
            id = nextId++, day = day, mealTypeId = mealTypeId, recipeId = null,
            title = null, imageUrl = null, servings = null, note = note.trim()
        )
    }

    override suspend fun move(entryId: Long, day: Long, mealTypeId: Long) {
        val meal = meals.value.firstOrNull { it.id == entryId } ?: return
        meals.value = meals.value.filterNot { it.id == entryId } + meal.copy(day = day, mealTypeId = mealTypeId)
    }

    override suspend fun delete(entryId: Long): MealPlanRepository.DeletedMeal? {
        val meal = meals.value.firstOrNull { it.id == entryId } ?: return null
        meals.value = meals.value.filterNot { it.id == entryId }
        deleted[meal.id] = meal
        return MealPlanRepository.DeletedMeal(
            MealPlanEntryEntity(
                id = meal.id, day = meal.day, mealTypeId = meal.mealTypeId, recipeId = meal.recipeId,
                servings = meal.servings, note = meal.note, sortOrder = 0, updatedAt = 0
            )
        )
    }

    private val deleted = mutableMapOf<Long, PlannedMeal>()

    override suspend fun restore(deleted: MealPlanRepository.DeletedMeal) {
        val meal = this.deleted.remove(deleted.entity.id) ?: return
        meals.value = meals.value + meal
    }

    override suspend fun addMealType(name: String) {
        if (name.isBlank()) return
        types.value = types.value + MealType(nextId++, name.trim(), builtInKey = null, sortOrder = types.value.size)
    }

    override suspend fun renameMealType(id: Long, name: String) {
        types.value = types.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    override suspend fun reorderMealTypes(orderedIds: List<Long>) {
        val byId = types.value.associateBy { it.id }
        types.value = orderedIds.mapIndexedNotNull { i, id -> byId[id]?.copy(sortOrder = i) }
    }

    override suspend fun deleteMealType(id: Long) {
        val type = types.value.firstOrNull { it.id == id } ?: return
        if (type.isBuiltIn) return
        val dinner = types.value.first { it.builtInKey == MealType.DINNER }.id
        meals.value = meals.value.map { if (it.mealTypeId == id) it.copy(mealTypeId = dinner) else it }
        types.value = types.value.filterNot { it.id == id }
    }

    companion object {
        const val BREAKFAST = 1L
        const val LUNCH = 2L
        const val DINNER = 3L
        const val SNACK = 4L

        val DEFAULT_TYPES = listOf(
            MealType(BREAKFAST, "Breakfast", "breakfast", 0),
            MealType(LUNCH, "Lunch", "lunch", 1),
            MealType(DINNER, "Dinner", MealType.DINNER, 2),
            MealType(SNACK, "Snack", "snack", 3)
        )
    }
}
