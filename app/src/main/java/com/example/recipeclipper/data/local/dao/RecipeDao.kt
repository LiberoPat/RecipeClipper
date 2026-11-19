package com.example.recipeclipper.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MenuEntryEntity
import com.example.recipeclipper.data.local.entity.CookedPhotoEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.LibraryLimit
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
    val isClipped: Boolean = false,
    /** The latest day of the user's own photos (#116), or null for none. */
    val lastCookedDay: Long? = null,
    /** Its link, for the site the Recipes row credits (#286). */
    val sourceUrl: String = "",
    /** Read from a chat's JSON (#355, origin CHAT). */
    val isFromChat: Boolean = false
)

/** [RecipeDao.observeLibraryCounts]: every recipe, those in at least one list, cooked, typed in and from a chat (#345, #355). */
data class LibraryCountsRow(
    val total: Int,
    val inList: Int,
    val cooked: Int = 0,
    val typed: Int = 0,
    val chat: Int = 0
)

/** A recipe named by another (#354): its original, or one of its versions. */
data class RecipeLinkRow(val id: Long, val title: String)

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

    @Query("SELECT * FROM recipes WHERE id IN (:ids)")
    abstract suspend fun byIds(ids: List<Long>): List<RecipeEntity>

    /** One recipe, re-emitted on every change to it (#286: Home's resume card); null once gone. */
    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract fun observe(id: Long): Flow<RecipeEntity?>

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

    /** The three times alone (#179: the tour's sample, saved before they were formatted). */
    @Query("UPDATE recipes SET prepTime = :prep, cookTime = :cook, totalTime = :total WHERE id = :id")
    abstract suspend fun setTimes(id: Long, prep: String?, cook: String?, total: String?)

    /** Every recipe with saved cook progress, for finding its running timers. Small: a
     *  handful of rows at most, since a cook rarely has more than one recipe on the go. */
    @Query("SELECT id, title, cookState FROM recipes WHERE cookState IS NOT NULL")
    abstract suspend fun cookStates(): List<CookStateRow>

    @Query("DELETE FROM recipes WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /**
     * [delete] for a recipe the cook deleted (#354): the versions of it keep their place, but
     * their link clears, so none points at a recipe that is gone.
     */
    @Transaction
    open suspend fun deleteWithLinks(id: Long) {
        delete(id)
        clearOrphanedLinks()
    }

    /** Clears every version link whose original is no longer here (#354): after any delete. */
    @Query(
        """
        UPDATE recipes SET basedOnUid = NULL
        WHERE basedOnUid IS NOT NULL AND basedOnUid NOT IN (SELECT uid FROM recipes)
        """
    )
    abstract suspend fun clearOrphanedLinks()

    /** The versions of the recipe with [uid], read before its delete so an undo can link them again. */
    @Query("SELECT id FROM recipes WHERE basedOnUid = :uid")
    abstract suspend fun versionIdsOf(uid: String): List<Long>

    /** Links the recipes [ids] to the original [uid] again: an undo of its delete. */
    @Query("UPDATE recipes SET basedOnUid = :uid WHERE id IN (:ids)")
    abstract suspend fun relink(ids: List<Long>, uid: String)

    /** The recipe [id] is a version of, as it changes (#354); null for none. */
    @Query(
        """
        SELECT o.id AS id, o.title AS title FROM recipes v
        JOIN recipes o ON o.uid = v.basedOnUid
        WHERE v.id = :id
        """
    )
    abstract fun observeOriginal(id: Long): Flow<RecipeLinkRow?>

    /** The versions of recipe [id], newest first, as they change (#354). */
    @Query(
        """
        SELECT v.id AS id, v.title AS title FROM recipes v
        JOIN recipes o ON o.uid = v.basedOnUid
        WHERE o.id = :id
        ORDER BY v.id DESC
        """
    )
    abstract fun observeVersions(id: Long): Flow<List<RecipeLinkRow>>

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

    /** Read before [delete], like [planEntriesFor]: the recipe's meals in saved menus (#52). */
    @Query("SELECT * FROM cooked_photos WHERE recipeId = :recipeId")
    abstract suspend fun cookedPhotosFor(recipeId: Long): List<CookedPhotoEntity>

    @Insert
    protected abstract suspend fun insertCookedPhotos(photos: List<CookedPhotoEntity>)

    @Query("SELECT * FROM menu_entries WHERE recipeId = :recipeId")
    abstract suspend fun menuEntriesFor(recipeId: Long): List<MenuEntryEntity>

    /** One menu meal back, unless its menu or meal type was deleted meanwhile (see [restorePlanEntry]). */
    @Query(
        """
        INSERT INTO menu_entries (id, menuId, dayOffset, mealTypeId, recipeId, servings, note, sortOrder, updatedAt, uid)
        SELECT :id, :menuId, :dayOffset, :mealTypeId, :recipeId, :servings, :note, :sortOrder, :updatedAt, :uid
        WHERE EXISTS (SELECT 1 FROM meal_types WHERE id = :mealTypeId)
          AND EXISTS (SELECT 1 FROM menus WHERE id = :menuId)
        """
    )
    protected abstract suspend fun restoreMenuEntry(
        id: Long, menuId: Long, dayOffset: Int, mealTypeId: Long, recipeId: Long?, servings: Int?, note: String?,
        sortOrder: Int, updatedAt: Long, uid: String
    )

    /**
     * Undoes [delete]: re-inserts [recipe] with its original id — `@Insert` honours a
     * non-zero primary key — then its [crossRefs], [planEntries] and [menuEntries], so restored
     * list membership, planned meals and menu meals still point at the right row.
     */
    @Transaction
    open suspend fun restore(
        recipe: RecipeEntity,
        crossRefs: List<RecipeListCrossRef>,
        planEntries: List<MealPlanEntryEntity> = emptyList(),
        menuEntries: List<MenuEntryEntity> = emptyList(),
        cookedPhotos: List<CookedPhotoEntity> = emptyList(),
        versionIds: List<Long> = emptyList()
    ) {
        insert(recipe)
        // Its versions link to it again (#354); its own link only if its original is still here.
        if (versionIds.isNotEmpty()) relink(versionIds, recipe.uid)
        clearOrphanedLinks()
        if (cookedPhotos.isNotEmpty()) insertCookedPhotos(cookedPhotos)
        if (crossRefs.isNotEmpty()) insertCrossRefs(crossRefs)
        planEntries.forEach {
            restorePlanEntry(it.id, it.day, it.mealTypeId, it.recipeId, it.servings, it.note, it.sortOrder, it.updatedAt, it.uid)
        }
        menuEntries.forEach {
            restoreMenuEntry(
                it.id, it.menuId, it.dayOffset, it.mealTypeId, it.recipeId, it.servings, it.note, it.sortOrder,
                it.updatedAt, it.uid
            )
        }
    }

    /** What a delete took of one recipe, for [restoreAll]. */
    class DeletedRows(
        val recipe: RecipeEntity,
        val crossRefs: List<RecipeListCrossRef>,
        val planEntries: List<MealPlanEntryEntity>,
        val menuEntries: List<MenuEntryEntity>,
        val cookedPhotos: List<CookedPhotoEntity>,
        /** The rows that were versions of it (#354), linked again by an undo. */
        val versionIds: List<Long> = emptyList()
    )

    /**
     * Undoes a burst of deletes, all or nothing, under [limit] (#345). A [LibraryLimit.Capped]
     * library (the free tier, locked) restores none of them when they would take it past its
     * limit, the same count as [upsert]'s (the tour's sample counts toward nothing): without this
     * an Undo would let delete, add one, Undo grow the library by one each time. Anything else
     * restores. Returns whether it restored.
     */
    @Transaction
    open suspend fun restoreAll(rows: List<DeletedRows>, limit: LibraryLimit): Boolean {
        if (limit is LibraryLimit.Capped) {
            val adding = rows.count { it.recipe.sourceUrl != SAMPLE_SOURCE_URL }
            if (count() + adding > limit.max) return false
        }
        rows.forEach { restore(it.recipe, it.crossRefs, it.planEntries, it.menuEntries, it.cookedPhotos, it.versionIds) }
        return true
    }

    // "Saved" is derived: a recipe is saved when at least one list contains it.
    @Query(
        """
        SELECT id, title, imageUrl, totalTime, lastViewedAt,
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved,
               contentOrigin = 'CLIPPED' AS isClipped,
               (SELECT MAX(p.day) FROM cooked_photos p WHERE p.recipeId = recipes.id) AS lastCookedDay,
               sourceUrl,
               contentOrigin = 'CHAT' AS isFromChat
        FROM recipes
        ORDER BY lastViewedAt DESC, id DESC
        """
    )
    abstract fun observeHistory(): Flow<List<RecipeSummaryRow>>

    /** Every recipe matching [query] (see the two-argument form), in a list or not. */
    fun observeHistory(query: String): Flow<List<RecipeSummaryRow>> = observeHistory(query, LISTED_ANY)

    /** [observeHistory] with only the list filter (V1's pills). */
    fun observeHistory(query: String, listed: Int): Flow<List<RecipeSummaryRow>> =
        observeHistory(query, listed, cooked = 0, typed = 0, chat = 0)

    /**
     * Same as [observeHistory], filtered to titles or ingredients containing [query]
     * (case-insensitive). An empty [query] returns everything, in one code path rather than
     * branching between two queries in Kotlin.
     *
     * `instr()`, not `LIKE`: SQLite treats `%` and `_` in a `LIKE` pattern as wildcards, so a
     * search for "100% whole wheat" would silently misbehave. `instr()` does plain substring
     * matching and needs no ESCAPE clause.
     *
     * [listed] is the Recipes screen's filter (#345): [LISTED_ANY], [LISTED_YES] (in at least one
     * list) or [LISTED_NO]. It is the same `EXISTS` as the `isSaved` column, so a row's bookmark
     * and the filter can't disagree; the search still applies inside it. [cooked] = 1 keeps the
     * recipes with a cooking marked (the user's own photos, or "Mark as cooked", #116, #173);
     * [typed] = 1 those typed, scanned or read in by hand (`contentOrigin` MANUAL, not the tour's
     * sample), [chat] = 1 those read from a chat's JSON (`contentOrigin` CHAT, #355): V4's views.
     */
    @Query(
        """
        SELECT id, title, imageUrl, totalTime, lastViewedAt,
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved,
               contentOrigin = 'CLIPPED' AS isClipped,
               (SELECT MAX(p.day) FROM cooked_photos p WHERE p.recipeId = recipes.id) AS lastCookedDay,
               sourceUrl,
               contentOrigin = 'CHAT' AS isFromChat
        FROM recipes
        WHERE (:query = ''
           OR instr(lower(title), lower(:query)) > 0
           OR instr(lower(ingredients), lower(:query)) > 0)
          AND (:listed = -1
           OR EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) = :listed)
          AND (:cooked = 0
           OR EXISTS(SELECT 1 FROM cooked_photos p WHERE p.recipeId = recipes.id))
          AND (:typed = 0
           OR (contentOrigin = 'MANUAL' AND sourceUrl != 'manual:sample'))
          AND (:chat = 0
           OR contentOrigin = 'CHAT')
        ORDER BY lastViewedAt DESC, id DESC
        """
    )
    abstract fun observeHistory(query: String, listed: Int, cooked: Int, typed: Int, chat: Int): Flow<List<RecipeSummaryRow>>

    /** How many recipes there are, in a list, cooked and typed in, for the filter's and the strip's counts (#345). */
    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id)), 0) AS inList,
               COALESCE(SUM(EXISTS(SELECT 1 FROM cooked_photos p WHERE p.recipeId = recipes.id)), 0) AS cooked,
               COALESCE(SUM(contentOrigin = 'MANUAL' AND sourceUrl != 'manual:sample'), 0) AS typed,
               COALESCE(SUM(contentOrigin = 'CHAT'), 0) AS chat
        FROM recipes
        """
    )
    abstract fun observeLibraryCounts(): Flow<LibraryCountsRow>

    @Query(
        """
        SELECT id, title, imageUrl, totalTime, lastViewedAt,
               EXISTS(SELECT 1 FROM recipe_list_cross_ref c WHERE c.recipeId = recipes.id) AS isSaved,
               contentOrigin = 'CLIPPED' AS isClipped,
               (SELECT MAX(p.day) FROM cooked_photos p WHERE p.recipeId = recipes.id) AS lastCookedDay,
               sourceUrl,
               contentOrigin = 'CHAT' AS isFromChat
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
     * cap. A recipe planned only for past days is ordinary history again. Nor is one in a saved
     * menu (#52): the menu would lose it. Nor is one typed in by hand (#102, origin MANUAL, or CHAT, #355): it
     * has no link to bring it back. Nor is one with the user's own photos (#116).
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
              AND id NOT IN (SELECT recipeId FROM menu_entries WHERE recipeId IS NOT NULL)
              AND contentOrigin NOT IN ('MANUAL', 'CHAT')
              AND id NOT IN (SELECT recipeId FROM cooked_photos)
            ORDER BY lastViewedAt DESC, id DESC
            LIMIT -1 OFFSET :keep
        )
        """
    )
    abstract suspend fun cullHistory(keep: Int, today: Long = NO_PLAN_PROTECTION)

    /**
     * The free tier's one-for-one removal (#107): the oldest-viewed recipe [cullHistory] could
     * delete (the same protections, keep this WHERE in step with it), or null if every recipe
     * is protected.
     */
    @Query(
        """
        SELECT id FROM recipes
        WHERE id NOT IN (SELECT recipeId FROM recipe_list_cross_ref)
          AND id NOT IN (SELECT recipeId FROM meal_plan_entries
                         WHERE recipeId IS NOT NULL AND day >= :today)
          AND id NOT IN (SELECT recipeId FROM menu_entries WHERE recipeId IS NOT NULL)
          AND contentOrigin NOT IN ('MANUAL', 'CHAT')
          AND id NOT IN (SELECT recipeId FROM cooked_photos)
        ORDER BY lastViewedAt ASC, id ASC
        LIMIT 1
        """
    )
    abstract suspend fun oldestCullable(today: Long = NO_PLAN_PROTECTION): Long?

    /**
     * Every recipe but the tour's sample (#151, `SampleRecipe.SOURCE_URL`, spelled out here
     * because a query is a constant): it never takes a free-tier place (#107).
     */
    @Query("SELECT COUNT(*) FROM recipes WHERE sourceUrl != 'manual:sample'")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM recipes WHERE sourceUrl != 'manual:sample'")
    abstract fun observeCount(): Flow<Int>

    /**
     * Saves a freshly parsed recipe and returns its id. A link that has been seen before is
     * updated in place, so it keeps its id, its uid, its list membership, its note, its chosen servings,
     * its ticked ingredients if the ingredients didn't change, and its cook progress if the
     * steps didn't change (both hold indexes). The [limit] is applied in the same transaction
     * (see [LibraryLimit]): a [LibraryLimit.History] cap is culled after the save; a
     * [LibraryLimit.Free] one only when a new recipe is added to a library already at or over
     * it, by removing the oldest unprotected recipe first, one for one. If there is none, nothing
     * is written and [NOT_KEPT] is returned. A [LibraryLimit.Capped] one (#345) never removes
     * anything: a new recipe at the limit is [NOT_KEPT]. Updating a recipe already here never
     * removes one, and is allowed at the limit (it is already counted).
     *
     * A row that is the user's version (#29: edited, clipped or typed in, `contentOrigin` neither
     * PARSED nor EXTRACTED) keeps its content: the re-share only counts as a view. [replaceUsersVersion] is
     * "Update from source", which does replace it, and makes it PARSED again ([fresh] is).
     */
    @Transaction
    open suspend fun upsert(
        fresh: RecipeEntity,
        limit: LibraryLimit,
        replaceUsersVersion: Boolean = false,
        today: Long = NO_PLAN_PROTECTION
    ): Long {
        val existing = findByUrl(fresh.sourceUrl)
        val id = if (existing == null) {
            // One cookbook (#345): at the limit a new recipe is shown, not kept; nothing is removed.
            if (limit is LibraryLimit.Capped && count() >= limit.max) return NOT_KEPT
            if (limit is LibraryLimit.Free && count() >= limit.max) {
                deleteWithLinks(oldestCullable(today) ?: return NOT_KEPT)
            }
            insert(fresh)
        } else if (!ContentOrigin.isSources(existing.contentOrigin) && !replaceUsersVersion) {
            touch(existing.id, fresh.lastViewedAt)
            existing.id
        } else {
            update(keepingUserState(existing, fresh))
            existing.id
        }
        if (limit is LibraryLimit.History) {
            cullHistory(limit.keep, today)
            clearOrphanedLinks()
        }
        return id
    }

    /** [upsert] under the old history cap: the app before #107, and most tests. */
    suspend fun upsert(
        fresh: RecipeEntity,
        historyLimit: Int,
        replaceUsersVersion: Boolean = false,
        today: Long = NO_PLAN_PROTECTION
    ): Long = upsert(fresh, LibraryLimit.History(historyLimit), replaceUsersVersion, today)

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
            servingsTarget = existing.servingsTarget,
            basedOnUid = existing.basedOnUid
        )
    }

    companion object {
        /** [observeHistory]'s `listed` argument (#345): any recipe, one in a list, one in none. */
        const val LISTED_ANY = -1
        const val LISTED_YES = 1
        const val LISTED_NO = 0

        /** The `today` that protects no planned recipe from the cull: no day is on or after
         *  it. The default for callers with no plan in mind; the repository passes today. */
        const val NO_PLAN_PROTECTION = Long.MAX_VALUE

        /** [upsert]'s answer when a full free library had no room (#107): no row has id 0. */
        const val NOT_KEPT = 0L

        /** The tour's sample's `sourceUrl` (`SampleRecipe.SOURCE_URL`), which [count] leaves out. */
        private const val SAMPLE_SOURCE_URL = "manual:sample"
    }
}
