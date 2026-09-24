package com.example.recipeclipper.fake

import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * A hand-written fake that actually models membership rather than only recording calls.
 *
 * The behaviour worth testing here is a round trip — ticking a list writes, and the flow the
 * sheet is collecting re-emits with the box now checked — and a fake that just recorded
 * `setMembership` would prove only half of that. So [membership] is real state, and
 * `recipeCount` and `containsRecipe` are derived from it exactly as the SQL derives them.
 *
 * That means the `recipeCount` a test puts in a staged [lists] entry is ignored: stage
 * [membership] instead and the counts follow.
 */
class FakeListRepository : ListRepository {

    /** The lists that exist. `recipeCount`/`containsRecipe` on these are overwritten. */
    val lists = MutableStateFlow<List<RecipeList>>(emptyList())

    /** recipeId to listId, one pair per membership row. */
    val membership = MutableStateFlow<Set<Pair<Long, Long>>>(emptySet())

    /** What [observeRecipesIn] emits, regardless of the list asked for. */
    val recipesIn = MutableStateFlow<List<RecipeSummary>>(emptyList())

    /** Name and the recipe to add (or null), for every [createList] call, in order. */
    val createCalls = mutableListOf<Pair<String, Long?>>()

    /** Every [rename] call, in order. */
    val renameCalls = mutableListOf<Pair<Long, String>>()

    /** Every list id [deleteList] was called with, in order. */
    val deleteCalls = mutableListOf<Long>()

    /** Every list id [observeRecipesIn] was asked for, in order. */
    val recipesInQueries = mutableListOf<Long>()

    private var nextId = 100L

    override fun observeLists(): Flow<List<RecipeList>> =
        combine(lists, membership) { all, rows -> all.map { it.withCounts(rows, recipeId = null) } }

    override fun observeListsFor(recipeId: Long): Flow<List<RecipeList>> =
        combine(lists, membership) { all, rows -> all.map { it.withCounts(rows, recipeId) } }

    override fun observeRecipesIn(listId: Long): Flow<List<RecipeSummary>> {
        recipesInQueries += listId
        return recipesIn.map { it }
    }

    override suspend fun setMembership(recipeId: Long, listId: Long, inList: Boolean) {
        membership.value = if (inList) {
            membership.value + (recipeId to listId)
        } else {
            membership.value - (recipeId to listId)
        }
    }

    override suspend fun createList(name: String, addRecipeId: Long?): Long {
        createCalls += name to addRecipeId
        val id = nextId++
        lists.value = lists.value + RecipeList(
            id = id,
            name = name,
            isBuiltIn = false,
            isFavorites = false,
            recipeCount = 0
        )
        if (addRecipeId != null) membership.value = membership.value + (addRecipeId to id)
        return id
    }

    override suspend fun rename(listId: Long, name: String) {
        renameCalls += listId to name
        lists.value = lists.value.map { if (it.id == listId) it.copy(name = name) else it }
    }

    override suspend fun deleteList(listId: Long) {
        deleteCalls += listId
        // Favorites is refused in SQL, so the fake refuses it here for the same reason.
        if (lists.value.any { it.id == listId && it.isFavorites }) return
        lists.value = lists.value.filterNot { it.id == listId }
        membership.value = membership.value.filterNot { it.second == listId }.toSet()
    }

    private fun RecipeList.withCounts(rows: Set<Pair<Long, Long>>, recipeId: Long?) = copy(
        recipeCount = rows.count { it.second == id },
        containsRecipe = recipeId != null && (recipeId to id) in rows
    )
}
