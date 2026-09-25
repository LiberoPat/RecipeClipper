package com.example.recipeclipper.fake

import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.Aisles
import com.example.recipeclipper.data.model.GroceryItem
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PlannedIngredients
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A fake that models the list rather than only recording calls: [items] is real state, a new
 * line gets its aisle from the aisle table as the real repository does, and delete and restore
 * round-trip. [planned] is what [plannedIngredients] answers, filtered by day.
 */
class FakeGroceryRepository : GroceryRepository {

    val items = MutableStateFlow<List<GroceryItem>>(emptyList())
    var planned: List<PlannedIngredients> = emptyList()

    private var nextId = 1L

    override fun observeItems(): Flow<List<GroceryItem>> = items

    override suspend fun add(lines: List<NewGroceryLine>) {
        var order = (items.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
        items.value = items.value + lines.filter { it.text.isNotBlank() }.map { line ->
            val text = line.text.trim()
            GroceryItem(
                id = nextId++,
                text = text,
                language = line.language,
                aisle = Aisles.of(text, LanguageWords.forTag(line.language)),
                checked = false,
                sortOrder = order++,
                recipeId = line.recipeId,
                plannedDay = line.plannedDay
            )
        }
    }

    override suspend fun setChecked(ids: List<Long>, checked: Boolean) {
        items.value = items.value.map { if (it.id in ids) it.copy(checked = checked) else it }
    }

    override suspend fun setAisle(ids: List<Long>, aisle: Aisle) {
        items.value = items.value.map { if (it.id in ids) it.copy(aisle = aisle) else it }
    }

    override suspend fun delete(ids: List<Long>): GroceryRepository.DeletedItems? =
        remove { it.id in ids }

    override suspend fun clearChecked(): GroceryRepository.DeletedItems? = remove { it.checked }

    private fun remove(which: (GroceryItem) -> Boolean): GroceryRepository.DeletedItems? {
        val gone = items.value.filter(which).ifEmpty { return null }
        items.value = items.value.filterNot(which)
        return GroceryRepository.DeletedItems(gone.map { it.toEntity() })
    }

    override suspend fun restore(deleted: GroceryRepository.DeletedItems) {
        items.value = (items.value + deleted.entities.map { it.toDomain() }).sortedBy { it.sortOrder }
    }

    override suspend fun plannedIngredients(start: Long, end: Long): List<PlannedIngredients> =
        planned.filter { it.day in start..end }

    private fun GroceryItem.toEntity() = GroceryItemEntity(
        id = id, text = text, language = language, aisle = aisle.key, checked = checked,
        sortOrder = sortOrder, recipeId = recipeId, plannedDay = plannedDay, updatedAt = 0
    )

    private fun GroceryItemEntity.toDomain() = GroceryItem(
        id = id, text = text, language = language, aisle = Aisle.fromKey(aisle), checked = checked,
        sortOrder = sortOrder, recipeId = recipeId, plannedDay = plannedDay
    )
}
