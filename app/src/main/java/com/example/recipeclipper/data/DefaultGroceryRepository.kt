package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.GroceryDao
import com.example.recipeclipper.data.local.dao.PlannedIngredientsRow
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.Aisles
import com.example.recipeclipper.data.model.GroceryItem
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PlannedIngredients
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real, Room-backed [GroceryRepository]. Like [DefaultListRepository], a database failure
 * is logged and degrades ([ErrorLog.guard]): writes become no-ops, a delete returns null and a
 * Flow emits an empty list.
 */
@Singleton
class DefaultGroceryRepository @Inject constructor(
    private val dao: GroceryDao,
    private val clock: Clock,
    private val log: ErrorLog
) : GroceryRepository {

    override fun observeItems(): Flow<List<GroceryItem>> =
        dao.observeItems().map { rows -> rows.map { it.toDomain() } }.orEmptyOnError(log, "observeGroceries")

    override suspend fun add(lines: List<NewGroceryLine>) {
        val now = clock.now()
        val items = lines.mapNotNull { line ->
            val text = line.text.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            GroceryItemEntity(
                text = text,
                language = line.language,
                aisle = Aisles.of(text, LanguageWords.forTag(line.language)).key,
                sortOrder = 0, // the DAO puts them last, in order
                recipeId = line.recipeId,
                plannedDay = line.plannedDay,
                updatedAt = now
            )
        }
        if (items.isEmpty()) return
        log.guard("addGroceries", Unit) { dao.add(items) }
    }

    override suspend fun setChecked(ids: List<Long>, checked: Boolean) =
        log.guard("setGroceryChecked", Unit) { dao.setChecked(ids, checked, clock.now()) }

    override suspend fun setAisle(ids: List<Long>, aisle: Aisle) =
        log.guard("setGroceryAisle", Unit) { dao.setAisle(ids, aisle.key, clock.now()) }

    override suspend fun delete(ids: List<Long>): GroceryRepository.DeletedItems? = log.guard("deleteGroceries", null) {
        val entities = dao.items(ids).ifEmpty { return@guard null }
        dao.delete(entities.map { it.id })
        GroceryRepository.DeletedItems(entities)
    }

    override suspend fun clearChecked(): GroceryRepository.DeletedItems? = log.guard("clearCheckedGroceries", null) {
        val entities = dao.checkedItems().ifEmpty { return@guard null }
        dao.delete(entities.map { it.id })
        GroceryRepository.DeletedItems(entities)
    }

    override suspend fun restore(deleted: GroceryRepository.DeletedItems) =
        log.guard("restoreGroceries", Unit) { dao.restore(deleted.entities) }

    override suspend fun plannedIngredients(start: Long, end: Long): List<PlannedIngredients> =
        log.guard("plannedIngredients", emptyList()) { dao.plannedIngredients(start, end).map { it.toDomain() } }
}

private fun GroceryItemEntity.toDomain() = GroceryItem(
    id = id,
    text = text,
    language = language,
    aisle = Aisle.fromKey(aisle),
    checked = checked,
    sortOrder = sortOrder,
    recipeId = recipeId,
    plannedDay = plannedDay
)

private fun PlannedIngredientsRow.toDomain() = PlannedIngredients(
    entryId = entryId,
    day = day,
    servings = servings,
    recipeId = recipeId,
    title = title,
    ingredients = ingredients,
    yield = yield,
    language = language
)
