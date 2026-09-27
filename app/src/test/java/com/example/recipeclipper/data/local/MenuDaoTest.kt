package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.HISTORY_LIMIT
import com.example.recipeclipper.data.local.dao.MealPlanDao
import com.example.recipeclipper.data.local.dao.MenuDao
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reusable weekly menus (#52) against real SQLite: saving copies a week, applying only ever
 * adds, a menu's recipes are kept from the cull, and deleting a recipe, a meal type or a menu
 * does what the plan does.
 */
@RunWith(AndroidJUnit4::class)
class MenuDaoTest {

    private lateinit var db: RecipeDatabase
    private lateinit var recipes: RecipeDao
    private lateinit var plan: MealPlanDao
    private lateinit var menus: MenuDao

    private val week = 20_000L
    private val nextWeek = week + 7

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            .build()
        recipes = db.recipeDao()
        plan = db.mealPlanDao()
        menus = db.menuDao()
    }

    @After
    fun close() = db.close()

    private suspend fun recipe(url: String, viewedAt: Long = 1) = recipes.upsert(
        RecipeEntity(
            sourceUrl = url, title = "Recipe $url", imageUrl = null, ingredients = listOf("1 cup flour"),
            instructions = listOf("Bake."), prepTime = null, cookTime = null, totalTime = null,
            servings = "4", sourceType = "BLOG", lastViewedAt = viewedAt
        ),
        HISTORY_LIMIT, today = week
    )

    private suspend fun typeId(key: String) = plan.observeMealTypes().first().single { it.builtInKey == key }.id

    private suspend fun planMeal(day: Long, recipeId: Long? = null, note: String? = null, type: Long? = null, servings: Int? = null) =
        plan.add(
            MealPlanEntryEntity(
                day = day, mealTypeId = type ?: typeId("dinner"), recipeId = recipeId, servings = servings,
                note = note, sortOrder = 0, updatedAt = 1
            )
        )

    private suspend fun shown(start: Long) = plan.observeDays(start, start + 6).first()

    @Test
    fun aSavedWeekAppliesToAnotherWeekOnTheSameWeekdays() = runBlocking {
        val soup = recipe("https://a.com/soup")
        planMeal(week, recipeId = soup, servings = 2)
        planMeal(week + 3, note = "Leftovers", type = typeId("lunch"))
        planMeal(week + 7, note = "Not this week")

        assertNotNull(menus.saveWeek("Usual", week, now = 5))
        val menu = menus.observeMenus().first().single()
        assertEquals("Usual" to 2, menu.name to menu.mealCount)

        assertEquals(2, menus.apply(menu.id, week + 14, now = 6))
        val applied = shown(week + 14)
        assertEquals(listOf(week + 14, week + 17), applied.map { it.day })
        assertEquals(listOf(soup, null), applied.map { it.recipeId })
        assertEquals(listOf(2, null), applied.map { it.servings })
        assertEquals(listOf(null, "Leftovers"), applied.map { it.note })
        assertEquals(listOf(typeId("dinner"), typeId("lunch")), applied.map { it.mealTypeId })
    }

    @Test
    fun applyingAddsAfterWhatIsPlannedAndNeverOverwrites() = runBlocking {
        planMeal(week, note = "Tacos")
        menus.saveWeek("Tacos night", week, now = 1)
        planMeal(nextWeek, note = "Pizza")

        val id = menus.observeMenus().first().single().id
        menus.apply(id, nextWeek, now = 2)
        menus.apply(id, nextWeek, now = 3)

        assertEquals(listOf("Pizza", "Tacos", "Tacos"), shown(nextWeek).map { it.note })
        assertEquals(3, shown(nextWeek).map { it.uid }.toSet().size)
    }

    @Test
    fun anEmptyWeekSavesNoMenu() = runBlocking {
        assertNull(menus.saveWeek("Nothing", week, now = 1))
        assertEquals(emptyList<Any>(), menus.observeMenus().first())
    }

    @Test
    fun aRecipeInAMenuIsKeptFromTheCull() = runBlocking {
        val kept = recipe("https://a.com/kept", viewedAt = 0)
        planMeal(week - 7, recipeId = kept) // a past week: the plan alone wouldn't keep it
        menus.saveWeek("Old favourites", week - 7, now = 1)
        repeat(HISTORY_LIMIT + 5) { recipe("https://a.com/$it", viewedAt = 1000L + it) }

        assertNotNull(recipes.get(kept))
    }

    @Test
    fun aDeletedRecipeLeavesItsMenusAndUndoBringsItBack() = runBlocking {
        val soup = recipe("https://a.com/soup")
        planMeal(week, recipeId = soup)
        planMeal(week + 1, note = "Out")
        menus.saveWeek("Usual", week, now = 1)

        val entity = recipes.get(soup)!!
        val menuMeals = recipes.menuEntriesFor(soup)
        val planMeals = recipes.planEntriesFor(soup)
        recipes.delete(soup)
        assertEquals(1, menus.observeMenus().first().single().mealCount)

        recipes.restore(entity, emptyList(), planMeals, menuMeals)
        assertEquals(2, menus.observeMenus().first().single().mealCount)
    }

    @Test
    fun deletingAMealTypeMovesMenuMealsToDinner() = runBlocking {
        plan.addType("Brunch", now = 1)
        val brunch = plan.observeMealTypes().first().single { it.name == "Brunch" }.id
        planMeal(week, note = "Pancakes", type = brunch)
        menus.saveWeek("Weekend", week, now = 1)

        plan.deleteType(brunch, now = 2)
        val id = menus.observeMenus().first().single().id
        assertEquals(listOf(typeId("dinner")), menus.entries(id).map { it.mealTypeId })
    }

    @Test
    fun deletingAMenuLeavesThePlanAndRenameKeepsTheUid() = runBlocking {
        planMeal(week, note = "Soup")
        menus.saveWeek("Usual", week, now = 1)
        val id = menus.observeMenus().first().single().id
        val uid = db.backupDao().allMenus().single().uid

        menus.rename(id, "Winter", now = 2)
        assertEquals("Winter", menus.observeMenus().first().single().name)
        assertEquals(uid, db.backupDao().allMenus().single().uid)

        menus.delete(id)
        assertEquals(emptyList<Any>(), menus.observeMenus().first())
        assertEquals(emptyList<Any>(), menus.entries(id))
        assertEquals(listOf("Soup"), shown(week).map { it.note })
    }
}
