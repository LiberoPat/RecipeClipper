package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real, Room-backed [ListRepository]. Bound to the interface with `@Binds`.
 *
 * Database failures are logged and degrade instead of crashing (see [ErrorLog.guard]): the
 * writes become no-ops, a Flow emits an empty list, and [createList] returns [CREATE_FAILED].
 */
@Singleton
class DefaultListRepository @Inject constructor(
    private val listDao: ListDao,
    private val clock: Clock,
    private val log: ErrorLog
) : ListRepository {

    override fun observeLists(): Flow<List<RecipeList>> =
        listDao.observeLists(ListDao.NO_RECIPE).map { rows -> rows.map { it.toDomain() } }
            .orEmptyOnError(log, "observeLists")

    override fun observeListsFor(recipeId: Long): Flow<List<RecipeList>> =
        listDao.observeLists(recipeId).map { rows -> rows.map { it.toDomain() } }
            .orEmptyOnError(log, "observeListsFor")

    override fun observeRecipesIn(listId: Long): Flow<List<RecipeSummary>> =
        listDao.observeRecipesIn(listId).map { rows -> rows.map { it.toDomain() } }
            .orEmptyOnError(log, "observeRecipesIn")

    override suspend fun setMembership(recipeId: Long, listId: Long, inList: Boolean) =
        log.guard("setMembership", Unit) {
            if (inList) {
                listDao.addToList(RecipeListCrossRef(recipeId, listId, clock.now()))
            } else {
                listDao.removeFromList(recipeId, listId)
            }
        }

    override suspend fun createList(name: String, addRecipeId: Long?): Long =
        log.guard("createList", CREATE_FAILED) {
            listDao.create(name.trim(), addRecipeId ?: ListDao.NO_RECIPE, clock.now())
        }

    override suspend fun rename(listId: Long, name: String) =
        log.guard("rename", Unit) { listDao.rename(listId, name.trim()) }

    override suspend fun deleteList(listId: Long) =
        log.guard("deleteList", Unit) { listDao.delete(listId) }

    companion object {
        /** What [createList] returns when the insert failed: an id no row can have (Room ids
         *  start at 1). No caller uses the returned id today; one that does must check it. */
        const val CREATE_FAILED = -1L
    }
}
