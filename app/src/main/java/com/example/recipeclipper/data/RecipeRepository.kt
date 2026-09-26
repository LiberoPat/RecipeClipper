package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MenuEntryEntity
import com.example.recipeclipper.data.local.entity.CookedPhotoEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.StepAlarm
import kotlinx.coroutines.flow.Flow

/** While the free tier (#107) is off, history keeps this many unprotected recipes. */
const val HISTORY_LIMIT = LibraryLimit.HISTORY_RECIPES

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
     *
     * A new link on a full free library (#107) whose recipes are all protected is shown but
     * not kept: `Success(recipe, kept = false)`, with no id.
     */
    suspend fun importFromUrl(sharedUrl: String): ParseResult

    /**
     * Saves a recipe that was shown but not kept (#107), once there is room (after unlocking).
     * `Success(kept = false)` again if there still isn't.
     */
    suspend fun keep(recipe: Recipe): ParseResult

    /**
     * Saves a recipe the user clipped by hand from a page with no recipe data (#37), keyed on
     * the cleaned [Recipe.sourceUrl] like an import: a link seen before keeps its id, note and
     * list membership, and its content is replaced by the clip. Counts as a view. Returns the
     * saved recipe, or `Error(SaveFailed)`, or `Success(kept = false)` on a full library
     * (#107), which the clip screen doesn't leave.
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

    /**
     * Saves a recipe typed in by hand (MANUAL, with a `manual:` link). Null as for [saveEdit].
     * On a full free library (#107) with nothing to make room, the recipe comes back with id 0:
     * not kept, and the editor stays open.
     */
    suspend fun addManual(draft: RecipeDraft): Recipe?

    /** Opens a recipe from history, a list or home. Counts as a view, so it moves to the top. */
    suspend fun open(id: Long): Recipe?

    /** The tour's sample recipe's id (#151), or null if it isn't in the library. */
    suspend fun sampleId(): Long?

    /**
     * Saves the tour's sample recipe (#151; [recipe] is `SampleRecipe.forLanguage`) as a
     * typed-in recipe under `SampleRecipe.SOURCE_URL`. No library limit applies: it counts
     * toward none, so it never removes a recipe. The sample already here keeps its id and
     * content, and counts as a view. Null if the save failed.
     */
    suspend fun addSample(recipe: Recipe): Long?

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
     * What a delete removed: enough for [restore] to undo it, list membership, planned
     * meals and menu meals included. Opaque to callers — they hold it and hand it back, nothing more.
     */
    data class DeletedRecipe(
        val entity: RecipeEntity,
        val crossRefs: List<RecipeListCrossRef>,
        val planEntries: List<MealPlanEntryEntity> = emptyList(),
        val menuEntries: List<MenuEntryEntity> = emptyList(),
        /** The user's own photos (#116): their rows; the files stay until [forget]. */
        val cookedPhotos: List<CookedPhotoEntity> = emptyList()
    )

    /**
     * Deletes a recipe outright, cross-refs included (they cascade). Null if it was already
     * gone. Hard delete, not a soft flag.
     */
    suspend fun delete(id: Long): DeletedRecipe?

    /** Undoes a [delete]: the recipe comes back with its original id and list membership. */
    suspend fun restore(deleted: DeletedRecipe)

    /** The [delete] stands (no undo now): removes what only an undo needed, its photo files. */
    suspend fun forget(deleted: DeletedRecipe) {}

    /** Titles and ingredients matching [query]; everything when [query] is blank. */
    fun observeHistory(query: String): Flow<List<RecipeSummary>>

    fun observeRecent(limit: Int): Flow<List<RecipeSummary>>

    /** How many recipes are saved, of every kind but the tour's sample (#107: the Recipes screen's count; #151). */
    fun observeCount(): Flow<Int>
}
