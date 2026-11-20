package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import kotlinx.coroutines.flow.Flow

/**
 * One list as a screen needs it: the list itself, how many recipes it holds, and — when the
 * query was asked about a particular recipe — whether it holds that one.
 *
 * [containsRecipe] is always false when the caller passed [ListDao.NO_RECIPE], which is what
 * the Lists screen does; only the save-to-list sheet has a recipe in hand.
 */
data class ListRow(
    val id: Long,
    val name: String,
    val isBuiltIn: Boolean,
    val isFavorites: Boolean,
    val recipeCount: Int,
    val containsRecipe: Boolean
)

/** How many of a set of recipes one list holds (#377): the sheet's tri-state per list. */
data class ListMemberCountRow(
    val listId: Long,
    val memberCount: Int
)

/** One photo on a list's cover (#286): a recipe in [listId] that has an [imageUrl]. */
data class ListCoverPhotoRow(
    val listId: Long,
    val imageUrl: String
)

/** A saved recipe as "Use it up" (#305) reads it: its lines as stored. */
data class SavedRecipeRow(
    val id: Long,
    val title: String,
    val language: String?,
    val ingredients: List<String>
)

@Dao
abstract class ListDao {

    /**
     * Lists in the cook's own order (`sortOrder`, #381; seeded lists start first), each with its recipe count and whether it contains [recipeId].
     *
     * One query rather than two, in the same spirit as [RecipeDao.observeHistory]'s empty-query
     * branch: the Lists screen passes [NO_RECIPE], an id no row can have, so `containsRecipe`
     * comes back false for every row and the screen ignores the column. Two near-identical
     * queries would be two things to keep in step.
     */
    @Query(
        """
        SELECT l.id, l.name, l.isBuiltIn, l.isFavorites,
               (SELECT COUNT(*) FROM recipe_list_cross_ref c
                WHERE c.listId = l.id) AS recipeCount,
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c2
                      WHERE c2.listId = l.id AND c2.recipeId = :recipeId) AS containsRecipe
        FROM lists l
        ORDER BY l.sortOrder ASC, l.id ASC
        """
    )
    abstract fun observeLists(recipeId: Long): Flow<List<ListRow>>

    /**
     * The recipes in one list, most recently added first. `isSaved` is a constant 1: being in
     * this list is what "saved" means, so the subquery the other summary rows run would only
     * ever come back true here.
     */
    @Query(
        """
        SELECT r.id, r.title, r.imageUrl, r.totalTime, r.lastViewedAt, 1 AS isSaved,
               r.contentOrigin = 'CLIPPED' AS isClipped, r.sourceUrl,
               r.contentOrigin = 'CHAT' AS isFromChat
        FROM recipes r
        JOIN recipe_list_cross_ref c ON c.recipeId = r.id
        WHERE c.listId = :listId
        ORDER BY c.addedAt DESC, r.id DESC
        """
    )
    abstract fun observeRecipesIn(listId: Long): Flow<List<RecipeSummaryRow>>

    /**
     * The Lists screen's covers (#286): up to [COVER_PHOTOS] photos per list, from the recipes
     * in it that have one, most recently added first (the order its own screen shows), grouped
     * by list. A list with no photo has no rows.
     *
     * The cap is a correlated `LIMIT` rather than `ROW_NUMBER()`: window functions arrived in
     * SQLite 3.25, and API 26 ships 3.18.
     */
    @Query(
        """
        SELECT c.listId, r.imageUrl
        FROM recipe_list_cross_ref c
        JOIN recipes r ON r.id = c.recipeId
        WHERE r.imageUrl IS NOT NULL AND r.imageUrl != ''
          AND r.id IN (
              SELECT c2.recipeId FROM recipe_list_cross_ref c2
              JOIN recipes r2 ON r2.id = c2.recipeId
              WHERE c2.listId = c.listId AND r2.imageUrl IS NOT NULL AND r2.imageUrl != ''
              ORDER BY c2.addedAt DESC, r2.id DESC
              LIMIT $COVER_PHOTOS)
        ORDER BY c.listId, c.addedAt DESC, r.id DESC
        """
    )
    abstract fun observeCoverPhotos(): Flow<List<ListCoverPhotoRow>>

    /**
     * Every saved recipe (in at least one list, once however many) with its ingredient lines,
     * for "Use it up" (#305).
     */
    @Query(
        """
        SELECT r.id, r.title, r.language, r.ingredients
        FROM recipes r
        WHERE r.id IN (SELECT recipeId FROM recipe_list_cross_ref)
        ORDER BY r.id
        """
    )
    abstract fun observeSavedRecipes(): Flow<List<SavedRecipeRow>>

    /**
     * Ticking a list in the sheet. IGNORE rather than REPLACE: re-adding a recipe already in
     * the list is a no-op, and must not rewrite `addedAt`, which is what orders the list.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addToList(crossRef: RecipeListCrossRef)

    /**
     * Unticking. The recipe row itself is deliberately untouched, so a recipe removed from its
     * last list falls back to being ordinary history — visible, and cullable by the history cap
     * like anything else. It is not deleted; that is what the explicit delete action is for.
     */
    @Query("DELETE FROM recipe_list_cross_ref WHERE recipeId = :recipeId AND listId = :listId")
    abstract suspend fun removeFromList(recipeId: Long, listId: Long)

    /**
     * Ticking or unticking a list for several recipes at once (#377): all of them or none, in one
     * transaction. Adding is IGNORE like [addToList] (a recipe already in keeps its `addedAt`).
     */
    @Transaction
    open suspend fun setMembers(recipeIds: List<Long>, listId: Long, inList: Boolean, now: Long) {
        if (inList) {
            recipeIds.forEach { addToList(RecipeListCrossRef(it, listId, now)) }
        } else {
            recipeIds.forEach { removeFromList(it, listId) }
        }
    }

    /**
     * For each list holding at least one of [recipeIds], how many it holds (#377): the save-to-list
     * sheet for several recipes shows a list as ticked when it holds all of them, unticked when
     * none, and indeterminate between.
     */
    @Query(
        """
        SELECT listId, COUNT(DISTINCT recipeId) AS memberCount
        FROM recipe_list_cross_ref
        WHERE recipeId IN (:recipeIds)
        GROUP BY listId
        """
    )
    abstract fun observeMemberCounts(recipeIds: List<Long>): Flow<List<ListMemberCountRow>>

    @Insert
    protected abstract suspend fun insert(list: ListEntity): Long

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM lists")
    protected abstract suspend fun nextSortOrder(): Int

    /**
     * Creates a user list and, when [recipeId] is not [NO_RECIPE], puts that recipe straight
     * into it — creating a list from the save-to-list sheet is meant to save the recipe you
     * were looking at, not leave you to tick it afterwards. One transaction so a list can
     * never exist with the recipe that prompted it missing.
     */
    @Transaction
    open suspend fun create(name: String, recipeId: Long, now: Long): Long {
        val id = insert(
            ListEntity(
                name = name,
                isBuiltIn = false,
                isFavorites = false,
                sortOrder = nextSortOrder(),
                createdAt = now
            )
        )
        if (recipeId != NO_RECIPE) addToList(RecipeListCrossRef(recipeId, id, now))
        return id
    }

    @Query("SELECT id FROM lists ORDER BY sortOrder ASC, id ASC")
    protected abstract suspend fun idsInOrder(): List<Long>

    @Query("UPDATE lists SET sortOrder = :position WHERE id = :id")
    protected abstract suspend fun setPosition(id: Long, position: Int)

    /**
     * The cook's new order (#381): [orderedIds] first, in that order, then any list not named
     * (one created while a drag was in flight) in the order it already had. Ids that are not
     * lists are ignored, and every list ends up with a distinct position 0, 1, 2... One
     * transaction, so the Lists screen sees the move once.
     */
    @Transaction
    open suspend fun reorder(orderedIds: List<Long>) {
        val current = idsInOrder()
        val known = current.toSet()
        val named = orderedIds.filter { it in known }.distinct()
        val namedSet = named.toSet()
        (named + current.filterNot { it in namedSet }).forEachIndexed { position, id ->
            setPosition(id, position)
        }
    }

    /** [create] with several recipes (#377): the list and every one of them go in together, or nothing does. */
    @Transaction
    open suspend fun createWith(name: String, recipeIds: List<Long>, now: Long): Long {
        val id = create(name, NO_RECIPE, now)
        recipeIds.forEach { addToList(RecipeListCrossRef(it, id, now)) }
        return id
    }

    /** Built-ins are renameable — the restriction is on deleting them, not naming them. */
    @Query("UPDATE lists SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    /**
     * Deletes a list and, by cascade, its membership rows. Deleting a list never deletes the
     * recipes in it — they fall back to ordinary history.
     *
     * The guard is `isFavorites = 0`, not `isBuiltIn = 0`: **Favorites is the only list that
     * cannot be deleted.** The other seeded lists (Lunch, Dinner, Desserts, Breakfast, Snacks)
     * are starting suggestions, not fixtures, and delete like any list the user made. Favorites
     * stays because "saved" is built around it and a missing one would strand what is in it.
     *
     * The guard is in the SQL rather than in Kotlin so no code path can get around it.
     */
    @Query("DELETE FROM lists WHERE id = :id AND isFavorites = 0")
    abstract suspend fun delete(id: Long)

    companion object {
        /** Passed as `recipeId` by callers that aren't asking about a recipe. No row has it. */
        const val NO_RECIPE = -1L

        /** The most photos a list's cover shows: a 2×2 mosaic. */
        const val COVER_PHOTOS = 4
    }
}
