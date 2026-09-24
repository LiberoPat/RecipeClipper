package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import kotlinx.coroutines.flow.Flow

/**
 * List membership: which lists exist, what is in them, and putting recipes in and out.
 *
 * Separate from [RecipeRepository] rather than bolted onto it. They cover different things —
 * one fetches, parses and persists recipes, the other only edits membership — and the three
 * list ViewModels need nothing from the recipe side, so a fake for their tests stays small.
 *
 * An interface for the same reason [RecipeRepository] is one: so a ViewModel test can hand it
 * a hand-written fake instead of a database.
 */
interface ListRepository {

    /** Every list with its recipe count, built-ins first. */
    fun observeLists(): Flow<List<RecipeList>>

    /**
     * Every list, each also saying whether it already contains [recipeId] — what the
     * save-to-list sheet renders its checkboxes from.
     */
    fun observeListsFor(recipeId: Long): Flow<List<RecipeList>>

    /** The recipes in one list, most recently added first. */
    fun observeRecipesIn(listId: Long): Flow<List<RecipeSummary>>

    /**
     * Ticks or unticks one list for one recipe. Writes immediately — the sheet is dismissed,
     * never submitted, so there is no later moment at which to save.
     */
    suspend fun setMembership(recipeId: Long, listId: Long, inList: Boolean)

    /**
     * Creates a user list. [addRecipeId] is the recipe to put straight into it, or null when
     * the list is being created from the Lists screen with no recipe in hand. Returns the new
     * list's id, or `-1` ([DefaultListRepository.CREATE_FAILED]) if the database refused.
     */
    suspend fun createList(name: String, addRecipeId: Long? = null): Long

    suspend fun rename(listId: Long, name: String)

    /** Deletes a user list. Built-ins are ignored, and the recipes in it are never deleted. */
    suspend fun deleteList(listId: Long)
}
