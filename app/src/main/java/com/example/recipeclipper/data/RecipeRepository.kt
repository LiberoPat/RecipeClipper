package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeSummary
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
     */
    suspend fun importFromUrl(sharedUrl: String): ParseResult

    /** Opens a recipe from history, a list or home. Counts as a view, so it moves to the top. */
    suspend fun open(id: Long): Recipe?

    suspend fun setChecked(id: Long, checked: Set<Int>)

    /**
     * What a delete removed: enough for [restore] to undo it, list membership included.
     * Opaque to callers — they hold it and hand it back, nothing more.
     */
    data class DeletedRecipe(val entity: RecipeEntity, val crossRefs: List<RecipeListCrossRef>)

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
