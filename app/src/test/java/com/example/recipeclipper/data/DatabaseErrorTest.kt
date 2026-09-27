package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.CookStateRow
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.dao.ListRow
import com.example.recipeclipper.data.local.dao.MealPlanDao
import com.example.recipeclipper.data.local.dao.MenuDao
import com.example.recipeclipper.data.local.dao.MenuRow
import com.example.recipeclipper.data.local.entity.MenuEntity
import com.example.recipeclipper.data.local.entity.MenuEntryEntity
import com.example.recipeclipper.data.local.dao.PlannedMealRow
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.dao.RecipeSummaryRow
import com.example.recipeclipper.data.local.entity.ListEntity
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.MealTypeEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.SourceType
import com.example.recipeclipper.data.remote.RecipeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A database that throws on every call must not crash the app: both repositories log and
 * degrade to what their contracts already allow. On the JVM over DAOs that throw what Room
 * throws (an `IllegalStateException`; `SQLiteException` is an Android stub that can't be
 * constructed off-device, and the guard treats every non-cancellation exception alike).
 */
class DatabaseErrorTest {

    private class Boom : IllegalStateException("database is locked")

    private class RecordingLog : ErrorLog {
        val messages = mutableListOf<String>()
        override fun error(message: String, cause: Throwable) {
            messages += message
        }
    }

    private class ThrowingRecipeDao(private val throwable: () -> Throwable = { Boom() }) : RecipeDao() {
        override suspend fun get(id: Long): RecipeEntity? = throw throwable()
        override suspend fun findByUrl(url: String): RecipeEntity? = throw throwable()
        override suspend fun insert(recipe: RecipeEntity): Long = throw throwable()
        override suspend fun update(recipe: RecipeEntity) = throw throwable()
        override suspend fun touch(id: Long, now: Long) = throw throwable()
        override suspend fun setChecked(id: Long, checked: Set<Int>) = throw throwable()
        override suspend fun setNotes(id: Long, notes: String?) = throw throwable()
        override suspend fun setCookState(id: Long, cookState: String?) = throw throwable()
        override suspend fun setServingsTarget(id: Long, target: Int?) = throw throwable()
        override suspend fun cookStates(): List<CookStateRow> = throw throwable()
        override suspend fun delete(id: Long) = throw throwable()
        override suspend fun crossRefsFor(recipeId: Long): List<RecipeListCrossRef> = throw throwable()
        override suspend fun insertCrossRefs(crossRefs: List<RecipeListCrossRef>) = throw throwable()
        override fun observeHistory(): Flow<List<RecipeSummaryRow>> = flow { throw throwable() }
        override fun observeHistory(query: String): Flow<List<RecipeSummaryRow>> = flow { throw throwable() }
        override fun observeRecent(limit: Int): Flow<List<RecipeSummaryRow>> = flow { throw throwable() }
        override suspend fun cullHistory(keep: Int, today: Long) = throw throwable()
        override suspend fun oldestCullable(today: Long): Long? = throw throwable()
        override suspend fun count(): Int = throw throwable()
        override fun observeCount(): Flow<Int> = flow { throw throwable() }
        override suspend fun planEntriesFor(recipeId: Long): List<MealPlanEntryEntity> = throw throwable()
        override suspend fun restorePlanEntry(
            id: Long, day: Long, mealTypeId: Long, recipeId: Long?, servings: Int?, note: String?,
            sortOrder: Int, updatedAt: Long, uid: String
        ) = throw throwable()
        override suspend fun menuEntriesFor(recipeId: Long): List<MenuEntryEntity> = throw throwable()
        override suspend fun cookedPhotosFor(recipeId: Long): List<com.example.recipeclipper.data.local.entity.CookedPhotoEntity> =
            throw throwable()
        override suspend fun insertCookedPhotos(photos: List<com.example.recipeclipper.data.local.entity.CookedPhotoEntity>) =
            throw throwable()
        override suspend fun restoreMenuEntry(
            id: Long, menuId: Long, dayOffset: Int, mealTypeId: Long, recipeId: Long?, servings: Int?, note: String?,
            sortOrder: Int, updatedAt: Long, uid: String
        ) = throw throwable()
    }

    private class ThrowingListDao : ListDao() {
        override fun observeLists(recipeId: Long): Flow<List<ListRow>> = flow { throw Boom() }
        override fun observeRecipesIn(listId: Long): Flow<List<RecipeSummaryRow>> = flow { throw Boom() }
        override suspend fun addToList(crossRef: RecipeListCrossRef) = throw Boom()
        override suspend fun removeFromList(recipeId: Long, listId: Long) = throw Boom()
        override suspend fun insert(list: ListEntity): Long = throw Boom()
        override suspend fun nextSortOrder(): Int = throw Boom()
        override suspend fun rename(id: Long, name: String) = throw Boom()
        override suspend fun delete(id: Long) = throw Boom()
    }

    private val url = "https://example.com/soup"
    private val recipe = Recipe(
        name = "Soup", image = null, ingredients = listOf("1 onion"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, yield = "4", sourceUrl = url
    )
    private val entity = RecipeEntity(
        id = 1, sourceUrl = url, title = "Soup", imageUrl = null, ingredients = listOf("1 onion"),
        instructions = listOf("Cook."), prepTime = null, cookTime = null, totalTime = null,
        servings = "4", sourceType = SourceType.BLOG.name, lastViewedAt = 1
    )

    private fun recipes(result: ParseResult, log: ErrorLog, dao: RecipeDao = ThrowingRecipeDao()) =
        DefaultRecipeRepository(
            source = object : RecipeSource { override suspend fun fetch(url: String) = result },
            recipeDao = dao,
            clock = Clock { 1_000L },
            log = log
        )

    @Test fun `a save that throws gives SaveFailed and is logged`() = runTest {
        val log = RecordingLog()
        val result = recipes(ParseResult.Success(recipe), log).importFromUrl(url)
        assertEquals(ParseResult.Error(ParseError.SaveFailed), result)
        // The lookup for a user's version (#29) fails first; the fetch goes ahead regardless.
        assertEquals(listOf("find user's version failed", "import save failed"), log.messages)
    }

    @Test fun `a failed fetch whose fallback lookup throws still returns the fetch's cause`() = runTest {
        val log = RecordingLog()
        val result = recipes(ParseResult.Error(ParseError.NoRecipeFound), log).importFromUrl(url)
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), result)
        assertEquals(2, log.messages.size) // the user's-version lookup (#29), then the fallback
    }

    @Test fun `open, delete, restore and the per-recipe writes degrade instead of throwing`() = runTest {
        val log = RecordingLog()
        val repository = recipes(ParseResult.Error(ParseError.NoRecipeFound), log)

        assertNull(repository.open(1))
        assertNull(repository.delete(1))
        repository.restore(RecipeRepository.DeletedRecipe(entity, emptyList()))
        repository.setChecked(1, setOf(0))
        repository.setNotes(1, "Half the sugar")
        repository.setCookProgress(1, CookProgress(active = true))
        repository.setServingsTarget(1, 4)
        assertEquals(emptyList<Any>(), repository.runningTimers())

        assertEquals(
            listOf(
                "open failed", "delete failed", "restore failed", "setChecked failed", "setNotes failed",
                "setCookProgress failed", "setServingsTarget failed", "runningTimers failed"
            ),
            log.messages
        )
    }

    @Test fun `a failing history or recent query emits an empty list and completes`() = runTest {
        val log = RecordingLog()
        val repository = recipes(ParseResult.Error(ParseError.NoRecipeFound), log)

        assertEquals(listOf(emptyList<Any>()), repository.observeHistory("").toList())
        assertEquals(listOf(emptyList<Any>()), repository.observeRecent(5).toList())
        assertEquals(listOf("observeHistory failed", "observeRecent failed"), log.messages)
    }

    @Test fun `cancellation is never swallowed as a database error`() = runTest {
        val log = RecordingLog()
        val repository = recipes(
            ParseResult.Error(ParseError.NoRecipeFound), log,
            dao = ThrowingRecipeDao { CancellationException("left the screen") }
        )
        val thrown = runCatching { repository.open(1) }.exceptionOrNull()
        assertTrue("$thrown", thrown is CancellationException)
        assertEquals(emptyList<String>(), log.messages)
    }

    @Test fun `list writes are no-ops, createList gives the sentinel, flows go empty`() = runTest {
        val log = RecordingLog()
        val lists = DefaultListRepository(ThrowingListDao(), Clock { 1_000L }, log)

        lists.setMembership(recipeId = 1, listId = 2, inList = true)
        lists.setMembership(recipeId = 1, listId = 2, inList = false)
        assertEquals(DefaultListRepository.CREATE_FAILED, lists.createList("Soups", addRecipeId = 1))
        lists.rename(2, "Stews")
        lists.deleteList(2)
        assertEquals(listOf(emptyList<Any>()), lists.observeLists().toList())
        assertEquals(listOf(emptyList<Any>()), lists.observeListsFor(1).toList())
        assertEquals(listOf(emptyList<Any>()), lists.observeRecipesIn(2).toList())

        assertEquals(
            listOf(
                "setMembership failed", "setMembership failed", "createList failed",
                "rename failed", "deleteList failed",
                "observeLists failed", "observeListsFor failed", "observeRecipesIn failed"
            ),
            log.messages
        )
    }

    private class ThrowingMenuDao : MenuDao() {
        override fun observeMenus(): Flow<List<MenuRow>> = flow { throw Boom() }
        override suspend fun entries(menuId: Long): List<MenuEntryEntity> = throw Boom()
        override suspend fun planEntries(start: Long, end: Long): List<MealPlanEntryEntity> = throw Boom()
        override suspend fun insertMenu(menu: MenuEntity): Long = throw Boom()
        override suspend fun insertEntries(entries: List<MenuEntryEntity>) = throw Boom()
        override suspend fun nextPlanOrder(day: Long, mealTypeId: Long): Int = throw Boom()
        override suspend fun insertPlanEntry(entry: MealPlanEntryEntity): Long = throw Boom()
        override suspend fun saveWeek(name: String, weekStart: Long, now: Long): Long? = throw Boom()
        override suspend fun apply(menuId: Long, weekStart: Long, now: Long): Int = throw Boom()
        override suspend fun rename(id: Long, name: String, now: Long) = throw Boom()
        override suspend fun delete(id: Long) = throw Boom()
    }

    private class ThrowingMealPlanDao : MealPlanDao() {
        override fun observeMealTypes(): Flow<List<MealTypeEntity>> = flow { throw Boom() }
        override fun observeDays(start: Long, end: Long): Flow<List<PlannedMealRow>> = flow { throw Boom() }
        override suspend fun entry(id: Long): MealPlanEntryEntity? = throw Boom()
        override suspend fun nextEntryOrder(day: Long, mealTypeId: Long): Int = throw Boom()
        override suspend fun insertEntry(entry: MealPlanEntryEntity): Long = throw Boom()
        override suspend fun restore(entry: MealPlanEntryEntity) = throw Boom()
        override suspend fun setSlot(id: Long, day: Long, mealTypeId: Long, sortOrder: Int, now: Long) = throw Boom()
        override suspend fun deleteEntry(id: Long) = throw Boom()
        override suspend fun nextTypeOrder(): Int = throw Boom()
        override suspend fun insertType(type: MealTypeEntity): Long = throw Boom()
        override suspend fun renameType(id: Long, name: String, now: Long) = throw Boom()
        override suspend fun setTypeOrder(id: Long, sortOrder: Int, now: Long) = throw Boom()
        override suspend fun moveEntriesToDinner(id: Long, now: Long) = throw Boom()
        override suspend fun moveMenuEntriesToDinner(id: Long, now: Long) = throw Boom()
        override suspend fun deleteUserType(id: Long) = throw Boom()
    }

    @Test fun `meal plan writes are no-ops, a delete gives null, flows go empty`() = runTest {
        val log = RecordingLog()
        val plan = DefaultMealPlanRepository(ThrowingMealPlanDao(), ThrowingMenuDao(), Clock { 1_000L }, log)

        plan.addRecipe(recipeId = 1, day = 20_000, mealTypeId = 3, servings = 4)
        plan.addNote("Eat out", day = 20_000, mealTypeId = 3)
        plan.move(entryId = 5, day = 20_001, mealTypeId = 2)
        assertNull(plan.delete(5))
        plan.addMealType("Brunch")
        plan.renameMealType(5, "Supper")
        plan.reorderMealTypes(listOf(2, 1))
        plan.deleteMealType(5)
        assertEquals(listOf(emptyList<Any>()), plan.observeMealTypes().toList())
        assertEquals(listOf(emptyList<Any>()), plan.observeDays(20_000, 20_006).toList())
        assertEquals(false, plan.saveWeekAsMenu("Usual", 20_000))
        assertEquals(0, plan.applyMenu(1, 20_007))
        plan.renameMenu(1, "Winter")
        plan.deleteMenu(1)
        assertEquals(listOf(emptyList<Any>()), plan.observeMenus().toList())

        assertEquals(
            listOf(
                "addRecipe failed", "addNote failed", "move failed", "deleteMeal failed",
                "addMealType failed", "renameMealType failed", "reorderMealTypes failed",
                "deleteMealType failed", "observeMealTypes failed", "observeDays failed",
                "saveWeekAsMenu failed", "applyMenu failed", "renameMenu failed", "deleteMenu failed",
                "observeMenus failed"
            ),
            log.messages
        )
    }
}
