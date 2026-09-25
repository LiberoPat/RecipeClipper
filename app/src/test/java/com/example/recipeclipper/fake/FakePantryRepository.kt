package com.example.recipeclipper.fake

import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.Aisles
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.NewPantryItem
import com.example.recipeclipper.data.model.PantryEdit
import com.example.recipeclipper.data.model.PantryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A fake that models the pantry rather than only recording calls: [items] is real state, a new
 * item gets its aisle from the aisle table as the real repository does, and snapshots restore.
 */
class FakePantryRepository(initial: List<PantryItem> = emptyList()) : PantryRepository {

    val items = MutableStateFlow(initial)

    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    override fun observeItems(): Flow<List<PantryItem>> = items

    override suspend fun items(): List<PantryItem> = items.value

    override suspend fun add(item: NewPantryItem) {
        val name = item.name.trim().takeIf { it.isNotEmpty() } ?: return
        items.value = items.value + PantryItem(
            id = nextId++,
            name = name,
            quantity = item.quantity?.trim()?.takeIf { it.isNotEmpty() },
            language = item.language,
            aisle = item.aisle ?: Aisles.of(name, LanguageWords.forTag(item.language)),
            inStock = true,
            alwaysHave = false,
            purchasedDay = item.purchasedDay,
            expiresDay = null
        )
    }

    override suspend fun setInStock(ids: List<Long>, inStock: Boolean) {
        items.value = items.value.map { if (it.id in ids) it.copy(inStock = inStock) else it }
    }

    override suspend fun restock(ids: List<Long>, day: Long) {
        items.value = items.value.map { if (it.id in ids) it.copy(inStock = true, purchasedDay = day) else it }
    }

    override suspend fun edit(id: Long, edit: PantryEdit) {
        val name = edit.name.trim().takeIf { it.isNotEmpty() } ?: return
        items.value = items.value.map {
            if (it.id == id) it.copy(
                name = name,
                quantity = edit.quantity?.trim()?.takeIf { q -> q.isNotEmpty() },
                alwaysHave = edit.alwaysHave,
                expiresDay = edit.expiresDay
            ) else it
        }
    }

    override suspend fun snapshot(ids: List<Long>): PantryRepository.Snapshot =
        PantryRepository.Snapshot(items.value.filter { it.id in ids }.map { it.toEntity() })

    override suspend fun delete(id: Long): PantryRepository.Snapshot? {
        val gone = items.value.firstOrNull { it.id == id } ?: return null
        items.value = items.value - gone
        return PantryRepository.Snapshot(listOf(gone.toEntity()))
    }

    override suspend fun restore(snapshot: PantryRepository.Snapshot) {
        val back = snapshot.entities.map { it.toDomain() }
        items.value = (items.value.filterNot { item -> back.any { it.id == item.id } } + back).sortedBy { it.id }
    }

    private fun PantryItem.toEntity() = PantryItemEntity(
        id = id, name = name, quantity = quantity, language = language, aisle = aisle.key, inStock = inStock,
        alwaysHave = alwaysHave, purchasedDay = purchasedDay, expiresDay = expiresDay, updatedAt = 0
    )

    private fun PantryItemEntity.toDomain() = PantryItem(
        id = id, name = name, quantity = quantity, language = language, aisle = Aisle.fromKey(aisle), inStock = inStock,
        alwaysHave = alwaysHave, purchasedDay = purchasedDay, expiresDay = expiresDay
    )
}
