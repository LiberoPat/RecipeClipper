package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import kotlinx.coroutines.flow.Flow

/** One row of a list of recipes: everything except the ingredients and steps. */
data class RecipeSummaryRow(
    val id: Long,
    val title: String,
    val imageUrl: String?,
    val totalTime: String?,
    val lastViewedAt: Long,
    val isSaved: Boolean
)

/** A recipe's saved cook progress, with what a timer alert needs to name it. */
data class CookStateRow(
    val id: Long,
    val title: String,
    val cookState: String
)

@Dao
abstract class RecipeDao {

    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract suspend fun get(id: Long): RecipeEntity?

    @Query("SELECT * FROM recipes WHERE sourceUrl = :url")
    abstract suspend fun findByUrl(url: String): RecipeEntity?

    @Insert
    protected abstract suspend fun insert(recipe: RecipeEntity): Long

    @Update
    protected abstract suspend fun update(recipe: RecipeEntity)

    @Query("UPDATE recipes SET lastViewedAt = :now WHERE id = :id")
    abstract suspend fun touch(id: Long, now: Long)

    @Query("UPDATE recipes SET checkedIngredients = :checked WHERE id = :id")
    abstract suspend fun setChecked(id: Long, checked: Set<Int>)

    /** The user's note; null clears it. */
    @Query("UPDATE recipes SET notes = :notes WHERE id = :id")
    abstract suspend fun setNotes(id: Long, notes: String?)

    /** Cook progress as [com.example.recipeclipper.data.local.CookStateJson]; null clears it. */
    @Query("UPDATE recipes SET cookState = :cookState WHERE id = :id")
    abstract suspend fun setCookState(id: Long, cookState: String?)

    /** The chosen servings; null means the recipe's own yield. */
    @Query("UPDATE recipes SET servingsTarget = :target WHERE id = :id")
    abstract suspend fun setServingsTarget(id: Long, target: Int?)

    /** Every recipe with saved cook progress, for finding its running timers. Small: a
     *  handful of rows at most, since a cook rarely has more than one recipe on the go. */
    @Query("SELECT id, title, cookState FROM recipes WHERE cookState IS NOT NULL")
    abstract suspend fun cookStates(): List<CookStateRow>

    @Query("DELETE FROM recipes WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** Read before [delete] so undo has something to restore. */
    @Query("SELECT * FROM recipe_list_cross_ref WHERE recipeId = :recipeId")
    abstract suspend fun crossRefsFor(recipeId: Long): List<RecipeListCrossRef>

    @Insert
    protected abstract suspend fun insertCrossRefs(crossRefs: List<RecipeListCrossRef>)

    /**
     * Undoes [delete]: re-inserts [recipe] with its original id — `@Insert` honours a
     * non-zero primary key — then its [crossRefs], so restored list membership still points
     * at the right row.
     */
    @Transaction
    open suspend fun restore(recipe: RecipeEntity, crossRefs: List<RecipeListCrossRef>) {
        insert(recipe)
        if (crossRefs.isNotEmpty()) insertCrossRefs(crossRefs)
    }

    // "Saved" is derived: a recipe is saved when at least one list contains it.
    @Query(
        """
        SELECT id, title, imageUrl, totalTime, lastViewedAt,
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved
        FROM recipes
        ORDER BY lastViewedAt DESC, id DESC
        """
    )
    abstract fun observeHistory(): Flow<List<RecipeSummaryRow>>

    /**
     * Same as [observeHistory], filtered to titles or ingredients containing [query]
     * (case-insensitive). An empty [query] returns everything, in one code path rather than
     * branching between two queries in Kotlin.
     *
     * `instr()`, not `LIKE`: SQLite treats `%` and `_` in a `LIKE` pattern as wildcards, so a
     * search for "100% whole wheat" would silently misbehave. `instr()` does plain substring
     * matching and needs no ESCAPE clause.
     */
    @Query(
        """
        SELECT id, title, imageUrl, totalTime, lastViewedAt,
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved
        FROM recipes
        WHERE :query = ''
           OR instr(lower(title), lower(:query)) > 0
           OR instr(lower(ingredients), lower(:query)) > 0
        ORDER BY lastViewedAt DESC, id DESC
        """
    )
    abstract fun observeHistory(query: String): Flow<List<RecipeSummaryRow>>

    @Query(
        """
        SELECT id, title, imageUrl, totalTime, lastViewedAt,
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved
        FROM recipes
        ORDER BY lastViewedAt DESC, id DESC
        LIMIT :limit
        """
    )
    abstract fun observeRecent(limit: Int): Flow<List<RecipeSummaryRow>>

    /**
     * Deletes recipes that are in no list, oldest view first, keeping the [keep] most
     * recently viewed of them. A recipe in any list is never touched.
     */
    @Query(
        """
        DELETE FROM recipes WHERE id IN (
            SELECT id FROM recipes
            WHERE id NOT IN (SELECT recipeId FROM recipe_list_cross_ref)
            ORDER BY lastViewedAt DESC, id DESC
            LIMIT -1 OFFSET :keep
        )
        """
    )
    abstract suspend fun cullHistory(keep: Int)

    /**
     * Saves a freshly parsed recipe and returns its id. A link that has been seen before is
     * updated in place, so it keeps its id, its uid, its list membership, its note, its chosen servings,
     * its ticked ingredients if the ingredients didn't change, and its cook progress if the
     * steps didn't change (both hold indexes). The history cap is enforced in the same
     * transaction, so the table is never left over the limit.
     */
    @Transaction
    open suspend fun upsert(fresh: RecipeEntity, historyLimit: Int): Long {
        val existing = findByUrl(fresh.sourceUrl)
        val id = if (existing == null) {
            insert(fresh)
        } else {
            val ticked = if (existing.ingredients == fresh.ingredients) {
                existing.checkedIngredients
            } else {
                emptySet() // the indexes no longer mean the same ingredients
            }
            // Step indexes, like ticks, only mean the same steps if the steps are unchanged.
            val cook = if (existing.instructions == fresh.instructions) existing.cookState else null
            // The uid is the recipe's identity in exports: a re-share never changes it.
            // The note and the servings are the user's, not the source's: a fresh parse never
            // carries them, and neither depends on the exact wording of the steps.
            update(
                fresh.copy(
                    id = existing.id,
                    uid = existing.uid,
                    checkedIngredients = ticked,
                    notes = existing.notes,
                    cookState = cook,
                    servingsTarget = existing.servingsTarget
                )
            )
            existing.id
        }
        cullHistory(historyLimit)
        return id
    }
}
