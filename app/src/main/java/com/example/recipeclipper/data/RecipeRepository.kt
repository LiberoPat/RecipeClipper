package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.StepAlarm
import kotlinx.coroutines.flow.Flow

/** Everything shared lands in history, capped at this many unsaved recipes. */
const val HISTORY_LIMIT = 50

/**
 * Fetches, parses and persists recipes. An interface so a ViewModel test can hand it a fake
 * ([DefaultRecipeRepository] is the real, Room-backed implementation, bound with `@Binds`).
 */
interface RecipeRepository {

    /**
     * The share-target path: fetch, parse, then persist. A link seen before is updated in
     * place. If the fetch fails but the recipe was saved earlier, the saved copy is shown, so
     * a recipe you've opened once still opens offline. A blocked or failed fetch is retried
     * once, after a short pause, before either of those; a page with no recipe is not.
     *
     * A link whose saved copy is the user's version (edited or clipped, #29) is not fetched
     * at all: the re-share opens that copy and counts as a view.
     */
    suspend fun importFromUrl(sharedUrl: String): ParseResult

    /**
     * Saves a recipe the user clipped by hand from a page with no recipe data (#37), keyed on
     * the cleaned [Recipe.sourceUrl] like an import: a link seen before keeps its id, note and
     * list membership, and its content is replaced by the clip. Counts as a view. Returns the
     * saved recipe, or `Error(SaveFailed)`.
     */
    suspend fun saveClip(recipe: Recipe): ParseResult

    /**
     * "Update from source" (#29): fetches the recipe's link again and replaces the user's
     * version with the site's, keeping the id, note and list membership, and making it PARSED.
     * On any failure nothing changes and the cause is returned.
     */
    suspend fun updateFromSource(id: Long): ParseResult

    /**
     * Saves the user's edit of recipe [id]: the content becomes [draft]'s, `editedAt` is now,
     * and a parsed recipe becomes EDITED. Null if [draft] isn't a recipe (no name, or neither
     * ingredients nor steps), the recipe is gone, or the save failed.
     */
    suspend fun saveEdit(id: Long, draft: RecipeDraft): Recipe?

    /** Saves a recipe typed in by hand (MANUAL, with a `manual:` link). Null as for [saveEdit]. */
    suspend fun addManual(draft: RecipeDraft): Recipe?

    /** Opens a recipe from history, a list or home. Counts as a view, so it moves to the top. */
    suspend fun open(id: Long): Recipe?

    suspend fun setChecked(id: Long, checked: Set<Int>)

    /** Saves the user's note on a recipe. A blank note is stored as no note. */
    suspend fun setNotes(id: Long, notes: String)

    /** Saves where the cook stands. An empty [CookProgress] is stored as none. */
    suspend fun setCookProgress(id: Long, progress: CookProgress)

    /** Saves the chosen servings; null goes back to the recipe's own yield. */
    suspend fun setServingsTarget(id: Long, target: Int?)

    /**
     * Every step timer still running in the database, whatever its deadline: what the timer
     * alarm checks before announcing one, and what is rescheduled after a reboot.
     */
    suspend fun runningTimers(): List<StepAlarm>

    /**
     * What a delete removed: enough for [restore] to undo it, list membership and planned
     * meals included. Opaque to callers — they hold it and hand it back, nothing more.
     */
    data class DeletedRecipe(
        val entity: RecipeEntity,
        val crossRefs: List<RecipeListCrossRef>,
        val planEntries: List<MealPlanEntryEntity> = emptyList()
    )

    /**
     * Deletes a recipe outright, cross-refs included (they cascade). Null if it was already
     * gone. Hard delete, not a soft flag.
     */
    suspend fun delete(id: Long): DeletedRecipe?

    /** Undoes a [delete]: the recipe comes back with its original id and list membership. */
    suspend fun restore(deleted: DeletedRecipe)

    /** Titles and ingredients matching [query]; everything when [query] is blank. */
    fun observeHistory(query: String): Flow<List<RecipeSummary>>

    fun observeRecent(limit: Int): Flow<List<RecipeSummary>>
}
