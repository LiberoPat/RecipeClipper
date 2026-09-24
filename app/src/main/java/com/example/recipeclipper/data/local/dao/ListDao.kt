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

@Dao
abstract class ListDao {

    /**
     * Lists, built-ins first, each with its recipe count and whether it contains [recipeId].
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
        ORDER BY l.isBuiltIn DESC, l.sortOrder ASC, l.id ASC
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
               r.contentOrigin = 'CLIPPED' AS isClipped
        FROM recipes r
        JOIN recipe_list_cross_ref c ON c.recipeId = r.id
        WHERE c.listId = :listId
        ORDER BY c.addedAt DESC, r.id DESC
        """
    )
    abstract fun observeRecipesIn(listId: Long): Flow<List<RecipeSummaryRow>>

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
    }
}
