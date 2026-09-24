package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.CookStateRow
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.dao.RecipeSummaryRow
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Just enough of a RecipeDao for the import path, recording every write. */
internal class InMemoryRecipeDao : RecipeDao() {
    val rows = mutableMapOf<Long, RecipeEntity>()
    var writes = 0
        private set
    private var nextId = 1L

    override suspend fun get(id: Long) = rows[id]
    override suspend fun findByUrl(url: String) = rows.values.firstOrNull { it.sourceUrl == url }
    override suspend fun insert(recipe: RecipeEntity): Long {
        writes++
        val id = if (recipe.id != 0L) recipe.id else nextId++
        rows[id] = recipe.copy(id = id)
        return id
    }
    override suspend fun update(recipe: RecipeEntity) {
        writes++
        rows[recipe.id] = recipe
    }
    override suspend fun touch(id: Long, now: Long) {
        writes++
        rows[id]?.let { rows[id] = it.copy(lastViewedAt = now) }
    }
    override suspend fun setChecked(id: Long, checked: Set<Int>) {
        writes++
        rows[id]?.let { rows[id] = it.copy(checkedIngredients = checked) }
    }
    override suspend fun setNotes(id: Long, notes: String?) {
        writes++
        rows[id]?.let { rows[id] = it.copy(notes = notes) }
    }
    override suspend fun setCookState(id: Long, cookState: String?) {
        writes++
        rows[id]?.let { rows[id] = it.copy(cookState = cookState) }
    }
    override suspend fun setServingsTarget(id: Long, target: Int?) {
        writes++
        rows[id]?.let { rows[id] = it.copy(servingsTarget = target) }
    }
    override suspend fun cookStates() = rows.values.mapNotNull { row ->
        row.cookState?.let { CookStateRow(row.id, row.title, it) }
    }
    override suspend fun delete(id: Long) {
        writes++
        rows.remove(id)
    }
    override suspend fun crossRefsFor(recipeId: Long) = emptyList<RecipeListCrossRef>()
    override suspend fun insertCrossRefs(crossRefs: List<RecipeListCrossRef>) {}
    override fun observeHistory(): Flow<List<RecipeSummaryRow>> = emptyFlow()
    override fun observeHistory(query: String): Flow<List<RecipeSummaryRow>> = emptyFlow()
    override fun observeRecent(limit: Int): Flow<List<RecipeSummaryRow>> = emptyFlow()
    override suspend fun cullHistory(keep: Int, today: Long) {}
    override suspend fun planEntriesFor(recipeId: Long) = emptyList<MealPlanEntryEntity>()
    override suspend fun insertPlanEntries(entries: List<MealPlanEntryEntity>) {}
}
