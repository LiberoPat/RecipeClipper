package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.MealPlanDao
import com.example.recipeclipper.data.local.dao.PlannedMealRow
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MealTypeEntity
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.PlannedMeal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real, Room-backed [MealPlanRepository]. Like [DefaultListRepository], a database failure
 * is logged and degrades ([ErrorLog.guard]): writes become no-ops, a delete returns null and a
 * Flow emits an empty list.
 */
@Singleton
class DefaultMealPlanRepository @Inject constructor(
    private val dao: MealPlanDao,
    private val clock: Clock,
    private val log: ErrorLog
) : MealPlanRepository {

    override fun observeMealTypes(): Flow<List<MealType>> =
        dao.observeMealTypes().map { rows -> rows.map { it.toDomain() } }
            .orEmptyOnError(log, "observeMealTypes")

    override fun observeDays(start: Long, end: Long): Flow<List<PlannedMeal>> =
        dao.observeDays(start, end).map { rows -> rows.map { it.toDomain() } }
            .orEmptyOnError(log, "observeDays")

    override suspend fun addRecipe(recipeId: Long, day: Long, mealTypeId: Long, servings: Int?) =
        log.guard("addRecipe", Unit) {
            dao.add(entry(day, mealTypeId, recipeId = recipeId, servings = servings, note = null))
            Unit
        }

    override suspend fun addNote(note: String, day: Long, mealTypeId: Long) {
        val text = note.trim()
        if (text.isEmpty()) return
        log.guard("addNote", Unit) {
            dao.add(entry(day, mealTypeId, recipeId = null, servings = null, note = text))
            Unit
        }
    }

    private fun entry(day: Long, mealTypeId: Long, recipeId: Long?, servings: Int?, note: String?) =
        MealPlanEntryEntity(
            day = day,
            mealTypeId = mealTypeId,
            recipeId = recipeId,
            servings = servings,
            note = note,
            sortOrder = 0, // the DAO puts it last in its slot
            updatedAt = clock.now()
        )

    override suspend fun move(entryId: Long, day: Long, mealTypeId: Long) =
        log.guard("move", Unit) { dao.move(entryId, day, mealTypeId, clock.now()) }

    override suspend fun delete(entryId: Long): MealPlanRepository.DeletedMeal? = log.guard("deleteMeal", null) {
        val entity = dao.entry(entryId) ?: return@guard null
        dao.deleteEntry(entryId)
        MealPlanRepository.DeletedMeal(entity)
    }

    override suspend fun restore(deleted: MealPlanRepository.DeletedMeal) =
        log.guard("restoreMeal", Unit) { dao.restore(deleted.entity) }

    override suspend fun addMealType(name: String) {
        val text = name.trim()
        if (text.isEmpty()) return
        log.guard("addMealType", Unit) {
            dao.addType(text, clock.now())
            Unit
        }
    }

    override suspend fun renameMealType(id: Long, name: String) {
        val text = name.trim()
        if (text.isEmpty()) return
        log.guard("renameMealType", Unit) { dao.renameType(id, text, clock.now()) }
    }

    override suspend fun reorderMealTypes(orderedIds: List<Long>) =
        log.guard("reorderMealTypes", Unit) { dao.reorderTypes(orderedIds, clock.now()) }

    override suspend fun deleteMealType(id: Long) =
        log.guard("deleteMealType", Unit) { dao.deleteType(id, clock.now()) }
}

private fun MealTypeEntity.toDomain() = MealType(id = id, name = name, builtInKey = builtInKey, sortOrder = sortOrder)

private fun PlannedMealRow.toDomain() = PlannedMeal(
    id = id,
    day = day,
    mealTypeId = mealTypeId,
    recipeId = recipeId,
    title = title,
    imageUrl = imageUrl,
    servings = servings,
    note = note
)
