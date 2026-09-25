package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
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
    val isSaved: Boolean,
    /** Picked from the page by hand (#37): History says so. */
    val isClipped: Boolean = false
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

    /** Read before [delete], like [crossRefsFor]: the recipe's planned meals cascade with it. */
    @Query("SELECT * FROM meal_plan_entries WHERE recipeId = :recipeId")
    abstract suspend fun planEntriesFor(recipeId: Long): List<MealPlanEntryEntity>

    /**
     * One planned meal back, unless its meal type was deleted meanwhile: that would fail the
     * foreign key and roll back the whole restore, losing the recipe over a meal type.
     */
    @Query(
        """
        INSERT INTO meal_plan_entries (id, day, mealTypeId, recipeId, servings, note, sortOrder, updatedAt, uid)
        SELECT :id, :day, :mealTypeId, :recipeId, :servings, :note, :sortOrder, :updatedAt, :uid
        WHERE EXISTS (SELECT 1 FROM meal_types WHERE id = :mealTypeId)
        """
    )
    protected abstract suspend fun restorePlanEntry(
        id: Long, day: Long, mealTypeId: Long, recipeId: Long?, servings: Int?, note: String?,
        sortOrder: Int, updatedAt: Long, uid: String
    )

    /**
     * Undoes [delete]: re-inserts [recipe] with its original id — `@Insert` honours a
     * non-zero primary key — then its [crossRefs] and [planEntries], so restored list
     * membership and planned meals still point at the right row.
     */
    @Transaction
    open suspend fun restore(
        recipe: RecipeEntity,
        crossRefs: List<RecipeListCrossRef>,
        planEntries: List<MealPlanEntryEntity> = emptyList()
    ) {
        insert(recipe)
        if (crossRefs.isNotEmpty()) insertCrossRefs(crossRefs)
        planEntries.forEach {
            restorePlanEntry(it.id, it.day, it.mealTypeId, it.recipeId, it.servings, it.note, it.sortOrder, it.updatedAt, it.uid)
        }
    }

    // "Saved" is derived: a recipe is saved when at least one list contains it.
    @Query(
        """
        SELECT id, title, imageUrl, totalTime, lastViewedAt,
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved,
               contentOrigin = 'CLIPPED' AS isClipped
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
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved,
               contentOrigin = 'CLIPPED' AS isClipped
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
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved,
               contentOrigin = 'CLIPPED' AS isClipped
        FROM recipes
        ORDER BY lastViewedAt DESC, id DESC
        LIMIT :limit
        """
    )
    abstract fun observeRecent(limit: Int): Flow<List<RecipeSummaryRow>>

    /**
     * Deletes recipes that are in no list, oldest view first, keeping the [keep] most
     * recently viewed of them. A recipe in any list is never touched, and neither is one
     * planned for [today] or later (#49; an epoch day, see `PlanDays`): both are outside the
     * cap. A recipe planned only for past days is ordinary history again.
     *
     * The plan subquery filters out NULL recipe ids (a note): `NOT IN` a set holding a NULL
     * is never true, which would silently stop the cull altogether.
     */
    @Query(
        """
        DELETE FROM recipes WHERE id IN (
            SELECT id FROM recipes
            WHERE id NOT IN (SELECT recipeId FROM recipe_list_cross_ref)
              AND id NOT IN (SELECT recipeId FROM meal_plan_entries
                             WHERE recipeId IS NOT NULL AND day >= :today)
            ORDER BY lastViewedAt DESC, id DESC
            LIMIT -1 OFFSET :keep
        )
        """
    )
    abstract suspend fun cullHistory(keep: Int, today: Long = NO_PLAN_PROTECTION)

    /**
     * Saves a freshly parsed recipe and returns its id. A link that has been seen before is
     * updated in place, so it keeps its id, its uid, its list membership, its note, its chosen servings,
     * its ticked ingredients if the ingredients didn't change, and its cook progress if the
     * steps didn't change (both hold indexes). The history cap is enforced in the same
     * transaction, so the table is never left over the limit.
     *
     * A row that is the user's version (#29: edited, clipped or typed in, `contentOrigin` not
     * PARSED) keeps its content: the re-share only counts as a view. [replaceUsersVersion] is
     * "Update from source", which does replace it, and makes it PARSED again ([fresh] is).
     */
    @Transaction
    open suspend fun upsert(
        fresh: RecipeEntity,
        historyLimit: Int,
        replaceUsersVersion: Boolean = false,
        today: Long = NO_PLAN_PROTECTION
    ): Long {
        val existing = findByUrl(fresh.sourceUrl)
        val id = if (existing == null) {
            insert(fresh)
        } else if (existing.contentOrigin != ORIGIN_PARSED && !replaceUsersVersion) {
            touch(existing.id, fresh.lastViewedAt)
            existing.id
        } else {
            update(keepingUserState(existing, fresh))
            existing.id
        }
        cullHistory(historyLimit, today)
        return id
    }

    /**
     * Saves the user's edit of recipe [id] (#29): [edited]'s content, with [origin] and
     * [editedAt] as given. Everything that is the user's rather than the content (id, uid,
     * link, note, servings, list membership, last view) stays, and ticks and cook progress
     * follow the same rule as a re-share. False if the recipe is gone.
     */
    @Transaction
    open suspend fun saveEdit(id: Long, edited: RecipeEntity, origin: String, editedAt: Long): Boolean {
        val existing = get(id) ?: return false
        update(
            keepingUserState(existing, edited).copy(
                sourceUrl = existing.sourceUrl,
                sourceType = existing.sourceType,
                language = existing.language,
                lastViewedAt = existing.lastViewedAt,
                contentOrigin = origin,
                editedAt = editedAt
            )
        )
        return true
    }

    /** [fresh]'s content under [existing]'s identity and user state. */
    private fun keepingUserState(existing: RecipeEntity, fresh: RecipeEntity): RecipeEntity {
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
        return fresh.copy(
            id = existing.id,
            uid = existing.uid,
            checkedIngredients = ticked,
            notes = existing.notes,
            cookState = cook,
            servingsTarget = existing.servingsTarget
        )
    }

    companion object {
        /** [com.example.recipeclipper.data.model.ContentOrigin.PARSED], as stored. */
        const val ORIGIN_PARSED = "PARSED"

        /** The `today` that protects no planned recipe from the cull: no day is on or after
         *  it. The default for callers with no plan in mind; the repository passes today. */
        const val NO_PLAN_PROTECTION = Long.MAX_VALUE
    }
}
