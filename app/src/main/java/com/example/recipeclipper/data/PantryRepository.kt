package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.PantryDao
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.Aisles
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.NewPantryItem
import com.example.recipeclipper.data.model.PantryEdit
import com.example.recipeclipper.data.model.PantryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pantry (#51). Shaped like [GroceryRepository]: an interface so ViewModel tests hand it a
 * fake, and every write lands at once.
 */
interface PantryRepository {

    fun observeItems(): Flow<List<PantryItem>>

    /** Everything in the pantry now, for a one-off match (the grocery sheet's first ticks). */
    suspend fun items(): List<PantryItem>

    /** Adds an item in stock, in its [NewPantryItem.aisle] or else the one its name belongs to. A blank name is ignored. */
    suspend fun add(item: NewPantryItem)

    suspend fun setInStock(ids: List<Long>, inStock: Boolean)

    /** Back in stock, bought on [day] (an epoch day). */
    suspend fun restock(ids: List<Long>, day: Long)

    /** A blank name is ignored; a blank quantity is none. */
    suspend fun edit(id: Long, edit: PantryEdit)

    /** Rows as they were, for [restore]. Opaque to callers. */
    data class Snapshot(val entities: List<PantryItemEntity>)

    /** The rows [ids] as they are now, to undo a change to them. */
    suspend fun snapshot(ids: List<Long>): Snapshot

    /** Deletes an item; null when it was already gone. */
    suspend fun delete(id: Long): Snapshot?

    /** Puts rows back exactly as [snapshot] had them: undoes a delete, a restock or a stock change. */
    suspend fun restore(snapshot: Snapshot)
}

/**
 * The real, Room-backed [PantryRepository]. A database failure is logged and degrades
 * ([ErrorLog.guard]): writes become no-ops, reads are empty.
 */
@Singleton
class DefaultPantryRepository @Inject constructor(
    private val dao: PantryDao,
    private val clock: Clock,
    private val log: ErrorLog
) : PantryRepository {

    override fun observeItems(): Flow<List<PantryItem>> =
        dao.observeItems().map { rows -> rows.map { it.toDomain() } }.orEmptyOnError(log, "observePantry")

    override suspend fun items(): List<PantryItem> =
        log.guard("pantryItems", emptyList()) { dao.items().map { it.toDomain() } }

    override suspend fun add(item: NewPantryItem) {
        val name = item.name.trim().takeIf { it.isNotEmpty() } ?: return
        log.guard("addPantryItem", Unit) {
            dao.insert(
                PantryItemEntity(
                    name = name,
                    quantity = item.quantity?.trim()?.takeIf { it.isNotEmpty() },
                    language = item.language,
                    aisle = (item.aisle ?: Aisles.of(name, LanguageWords.forTag(item.language))).key,
                    inStock = true,
                    alwaysHave = false,
                    purchasedDay = item.purchasedDay,
                    expiresDay = null,
                    updatedAt = clock.now()
                )
            )
        }
    }

    override suspend fun setInStock(ids: List<Long>, inStock: Boolean) =
        log.guard("setPantryInStock", Unit) { dao.setInStock(ids, inStock, clock.now()) }

    override suspend fun restock(ids: List<Long>, day: Long) =
        log.guard("restockPantry", Unit) { dao.restock(ids, day, clock.now()) }

    override suspend fun edit(id: Long, edit: PantryEdit) {
        val name = edit.name.trim().takeIf { it.isNotEmpty() } ?: return
        log.guard("editPantryItem", Unit) {
            dao.edit(id, name, edit.quantity?.trim()?.takeIf { it.isNotEmpty() }, edit.alwaysHave, edit.expiresDay, clock.now())
        }
    }

    override suspend fun snapshot(ids: List<Long>): PantryRepository.Snapshot =
        log.guard("pantrySnapshot", PantryRepository.Snapshot(emptyList())) {
            PantryRepository.Snapshot(ids.mapNotNull { dao.item(it) })
        }

    override suspend fun delete(id: Long): PantryRepository.Snapshot? = log.guard("deletePantryItem", null) {
        val entity = dao.item(id) ?: return@guard null
        dao.delete(id)
        PantryRepository.Snapshot(listOf(entity))
    }

    override suspend fun restore(snapshot: PantryRepository.Snapshot) {
        if (snapshot.entities.isEmpty()) return
        log.guard("restorePantry", Unit) { dao.put(snapshot.entities) }
    }
}

internal fun PantryItemEntity.toDomain() = PantryItem(
    id = id,
    name = name,
    quantity = quantity,
    language = language,
    aisle = Aisle.fromKey(aisle),
    inStock = inStock,
    alwaysHave = alwaysHave,
    purchasedDay = purchasedDay,
    expiresDay = expiresDay
)
