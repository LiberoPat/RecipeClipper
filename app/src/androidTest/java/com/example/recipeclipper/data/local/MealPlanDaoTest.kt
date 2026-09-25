package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.HISTORY_LIMIT
import com.example.recipeclipper.data.local.dao.MealPlanDao
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
 * The meal plan's rules (#49) against real SQLite: the cull keeps recipes planned for today or
 * later, a recipe's meals go and come back with it, and meal types are deleted only when they
 * are the user's own, their meals moving to Dinner.
 */
@RunWith(AndroidJUnit4::class)
class MealPlanDaoTest {

    private lateinit var db: RecipeDatabase
    private lateinit var recipes: RecipeDao
    private lateinit var plan: MealPlanDao

    private val today = 20_000L

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists) // seeds the meal types too
            .build()
        recipes = db.recipeDao()
        plan = db.mealPlanDao()
    }

    @After
    fun close() = db.close()

    private fun recipe(url: String, viewedAt: Long) = RecipeEntity(
        sourceUrl = url, title = "Recipe $url", imageUrl = null, ingredients = listOf("1 cup flour"),
        instructions = listOf("Bake."), prepTime = null, cookTime = null, totalTime = null,
        servings = "4", sourceType = "BLOG", lastViewedAt = viewedAt
    )

    private suspend fun typeId(key: String) = plan.observeMealTypes().first().single { it.builtInKey == key }.id

    private suspend fun planRecipe(recipeId: Long?, day: Long, type: String = "dinner", note: String? = null) =
        plan.add(
            MealPlanEntryEntity(
                day = day, mealTypeId = typeId(type), recipeId = recipeId, servings = null, note = note,
                sortOrder = 0, updatedAt = 1
            )
        )

    private suspend fun fillHistory(from: Int = 0) = repeat(HISTORY_LIMIT + 5) { n ->
        recipes.upsert(recipe("https://a.com/$from-$n", viewedAt = 1000L + n), HISTORY_LIMIT, today = today)
    }

    // --- The cull rule

    @Test
    fun aRecipePlannedForTodayOrLaterIsNeverCulled() = runBlocking {
        val forToday = recipes.upsert(recipe("https://a.com/today", viewedAt = 1), HISTORY_LIMIT, today = today)
        val forLater = recipes.upsert(recipe("https://a.com/later", viewedAt = 2), HISTORY_LIMIT, today = today)
        planRecipe(forToday, today)
        planRecipe(forLater, today + 10)

        fillHistory()

        assertNotNull(recipes.get(forToday))
        assertNotNull(recipes.get(forLater))
        // Like a saved recipe, a planned one is outside the cap: 50 others stay too.
        assertEquals(HISTORY_LIMIT + 2, recipes.observeHistory().first().size)
    }

    @Test
    fun aRecipePlannedOnlyForPastDaysIsOrdinaryHistory() = runBlocking {
        val past = recipes.upsert(recipe("https://a.com/past", viewedAt = 1), HISTORY_LIMIT, today = today)
        planRecipe(past, today - 1)

        fillHistory()

        assertNull(recipes.get(past))
        assertEquals(HISTORY_LIMIT, recipes.observeHistory().first().size)
    }

    @Test
    fun aPlannedNoteDoesNotStopTheCull() = runBlocking {
        // A note's recipeId is NULL; `NOT IN` a set holding NULL would match nothing at all.
        planRecipe(recipeId = null, day = today, note = "Eat out")

        fillHistory()

        assertEquals(HISTORY_LIMIT, recipes.observeHistory().first().size)
    }

    // --- Deleting a recipe

    @Test
    fun aDeletedRecipeTakesItsMealsAndUndoBringsThemBack() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/soup", viewedAt = 1), HISTORY_LIMIT, today = today)
        planRecipe(id, today)
        planRecipe(id, today + 2, type = "lunch")

        val entity = recipes.get(id)!!
        val crossRefs = recipes.crossRefsFor(id)
        val meals = recipes.planEntriesFor(id)
        recipes.delete(id)
        assertEquals(emptyList<Any>(), plan.observeDays(today, today + 6).first())

        recipes.restore(entity, crossRefs, meals)
        assertEquals(listOf(today, today + 2), plan.observeDays(today, today + 6).first().map { it.day })
    }

    // --- Order and moving

    @Test
    fun daysAreOrderedByDayThenMealTypeThenWhenAdded() = runBlocking {
        val a = recipes.upsert(recipe("https://a.com/a", viewedAt = 1), HISTORY_LIMIT, today = today)
        val b = recipes.upsert(recipe("https://a.com/b", viewedAt = 2), HISTORY_LIMIT, today = today)
        planRecipe(a, today + 1, type = "dinner")
        planRecipe(b, today, type = "dinner")
        planRecipe(null, today, type = "breakfast", note = "Toast")
        planRecipe(a, today, type = "dinner")

        val rows = plan.observeDays(today, today + 6).first()
        assertEquals(
            listOf("Toast", "Recipe https://a.com/b", "Recipe https://a.com/a", "Recipe https://a.com/a"),
            rows.map { it.note ?: it.title }
        )
        assertEquals(listOf(today, today, today, today + 1), rows.map { it.day })
    }

    @Test
    fun aMovedMealGoesToTheEndOfItsNewSlot() = runBlocking {
        val first = planRecipe(null, today, note = "First")
        planRecipe(null, today + 1, note = "Already there")

        plan.move(first, today + 1, typeId("dinner"), now = 5)

        assertEquals(
            listOf("Already there", "First"),
            plan.observeDays(today + 1, today + 1).first().map { it.note }
        )
        assertEquals(5L, plan.entry(first)?.updatedAt)
    }

    // --- Meal types

    @Test
    fun deletingAUserTypeMovesItsMealsToDinner() = runBlocking {
        val brunch = plan.addType("Brunch", now = 1)
        plan.add(
            MealPlanEntryEntity(
                day = today, mealTypeId = brunch, recipeId = null, servings = null, note = "Pancakes",
                sortOrder = 0, updatedAt = 1
            )
        )

        plan.deleteType(brunch, now = 2)

        val types = plan.observeMealTypes().first()
        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snack"), types.map { it.name })
        val meal = plan.observeDays(today, today).first().single()
        assertEquals(typeId("dinner"), meal.mealTypeId)
        assertEquals("Pancakes", meal.note)
    }

    @Test
    fun aSeededTypeIsNeverDeletedAndKeepsItsMeals() = runBlocking {
        val lunch = typeId("lunch")
        planRecipe(null, today, type = "lunch", note = "Sandwich")

        plan.deleteType(lunch, now = 2)

        assertEquals(4, plan.observeMealTypes().first().size)
        assertEquals(lunch, plan.observeDays(today, today).first().single().mealTypeId)
    }

    @Test
    fun aRenamedSeededTypeKeepsItsKey() = runBlocking {
        val dinner = typeId("dinner")
        plan.renameType(dinner, "Supper", now = 3)
        val renamed = plan.observeMealTypes().first().single { it.id == dinner }
        assertEquals("Supper", renamed.name)
        assertEquals("dinner", renamed.builtInKey)
        assertEquals(3L, renamed.updatedAt)
    }

    @Test
    fun reorderingTypesReordersTheWeek() = runBlocking {
        planRecipe(null, today, type = "breakfast", note = "Toast")
        planRecipe(null, today, type = "dinner", note = "Stew")
        val ids = plan.observeMealTypes().first().map { it.id }

        plan.reorderTypes(ids.reversed(), now = 4)

        assertEquals(listOf("Snack", "Dinner", "Lunch", "Breakfast"), plan.observeMealTypes().first().map { it.name })
        assertEquals(listOf("Stew", "Toast"), plan.observeDays(today, today).first().map { it.note })
    }
}
