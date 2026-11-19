package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.CookStateRow
import com.example.recipeclipper.data.local.dao.LibraryCountsRow
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.dao.RecipeLinkRow
import com.example.recipeclipper.data.local.dao.RecipeSummaryRow
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MenuEntryEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Just enough of a RecipeDao for the import path, recording every write. */
internal class InMemoryRecipeDao : RecipeDao() {
    val rows = mutableMapOf<Long, RecipeEntity>()
    var writes = 0
        private set
    private var nextId = 1L

    override suspend fun get(id: Long) = rows[id]
    override suspend fun findByUrl(url: String) = rows.values.firstOrNull { it.sourceUrl == url }
    override suspend fun byIds(ids: List<Long>) = ids.mapNotNull { rows[it] }
    override fun observe(id: Long): Flow<RecipeEntity?> = emptyFlow()
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
    override suspend fun setTimes(id: Long, prep: String?, cook: String?, total: String?) {
        writes++
        rows[id]?.let { rows[id] = it.copy(prepTime = prep, cookTime = cook, totalTime = total) }
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
    override suspend fun clearOrphanedLinks() {
        val uids = rows.values.mapTo(HashSet()) { it.uid }
        rows.values.filter { it.basedOnUid != null && it.basedOnUid !in uids }.forEach {
            writes++
            rows[it.id] = it.copy(basedOnUid = null)
        }
    }
    override suspend fun versionIdsOf(uid: String) = rows.values.filter { it.basedOnUid == uid }.map { it.id }
    override suspend fun relink(ids: List<Long>, uid: String) {
        ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(basedOnUid = uid) } }
    }
    override fun observeOriginal(id: Long): Flow<RecipeLinkRow?> = flowOf(
        rows[id]?.basedOnUid?.let { uid -> rows.values.firstOrNull { it.uid == uid } }?.let { RecipeLinkRow(it.id, it.title) }
    )
    override fun observeVersions(id: Long): Flow<List<RecipeLinkRow>> = flowOf(
        rows[id]?.uid?.let { uid ->
            rows.values.filter { it.basedOnUid == uid }.sortedByDescending { it.id }.map { RecipeLinkRow(it.id, it.title) }
        }.orEmpty()
    )
    override suspend fun crossRefsFor(recipeId: Long) = emptyList<RecipeListCrossRef>()
    override suspend fun insertCrossRefs(crossRefs: List<RecipeListCrossRef>) {}
    override fun observeHistory(): Flow<List<RecipeSummaryRow>> = emptyFlow()
    override fun observeHistory(query: String, listed: Int, cooked: Int, typed: Int, chat: Int): Flow<List<RecipeSummaryRow>> = emptyFlow()
    override fun observeLibraryCounts(): Flow<LibraryCountsRow> = emptyFlow()
    override fun observeRecent(limit: Int): Flow<List<RecipeSummaryRow>> = emptyFlow()
    override suspend fun cullHistory(keep: Int, today: Long) {}
    override suspend fun oldestCullable(today: Long): Long? = rows.values
        .filter { it.contentOrigin != "MANUAL" && it.contentOrigin != "CHAT" }
        .minWithOrNull(compareBy<RecipeEntity>({ it.lastViewedAt }, { it.id }))?.id
    override suspend fun count() = rows.size
    override fun observeCount(): Flow<Int> = emptyFlow()
    override suspend fun planEntriesFor(recipeId: Long) = emptyList<MealPlanEntryEntity>()
    override suspend fun restorePlanEntry(
        id: Long, day: Long, mealTypeId: Long, recipeId: Long?, servings: Int?, note: String?,
        sortOrder: Int, updatedAt: Long, uid: String
    ) {}
    override suspend fun menuEntriesFor(recipeId: Long) = emptyList<MenuEntryEntity>()
    override suspend fun cookedPhotosFor(recipeId: Long) =
        emptyList<com.example.recipeclipper.data.local.entity.CookedPhotoEntity>()
    override suspend fun insertCookedPhotos(photos: List<com.example.recipeclipper.data.local.entity.CookedPhotoEntity>) {}
    override suspend fun restoreMenuEntry(
        id: Long, menuId: Long, dayOffset: Int, mealTypeId: Long, recipeId: Long?, servings: Int?, note: String?,
        sortOrder: Int, updatedAt: Long, uid: String
    ) {}
}
