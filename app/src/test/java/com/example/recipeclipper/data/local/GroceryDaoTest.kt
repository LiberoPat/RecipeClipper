package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.local.dao.GroceryDao
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The grocery list (#50) against real SQLite: items keep the order added, a delete restores
 * whole, a recipe's items outlive it, and the week's planned recipes come in plan order.
 */
@RunWith(AndroidJUnit4::class)
class GroceryDaoTest {

    private lateinit var db: RecipeDatabase
    private lateinit var groceries: GroceryDao

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            .build()
        groceries = db.groceryDao()
    }

    @After
    fun close() = db.close()

    private fun item(text: String, recipeId: Long? = null) = GroceryItemEntity(
        text = text, language = "en", aisle = "other", sortOrder = 99, recipeId = recipeId,
        plannedDay = null, updatedAt = 1
    )

    private suspend fun recipe(url: String, ingredients: List<String> = listOf("1 cup flour")) =
        db.recipeDao().upsert(
            RecipeEntity(
                sourceUrl = url, title = "Recipe $url", imageUrl = null, ingredients = ingredients,
                instructions = listOf("Bake."), prepTime = null, cookTime = null, totalTime = null,
                servings = "4", sourceType = "BLOG", lastViewedAt = 1
            ),
            historyLimit = 50
        )

    private suspend fun texts() = groceries.observeItems().first().map { it.text }

    @Test
    fun itemsGoLastInTheOrderGiven() = runBlocking {
        groceries.add(listOf(item("a"), item("b")))
        groceries.add(listOf(item("c")))
        assertEquals(listOf("a", "b", "c"), texts())
        assertEquals(listOf(0, 1, 2), groceries.observeItems().first().map { it.sortOrder })
        assertEquals(3, groceries.observeItems().first().map { it.uid }.toSet().size)
    }

    @Test
    fun checkingMovingAndDeletingTouchOnlyTheGivenItems() = runBlocking {
        groceries.add(listOf(item("a"), item("b"), item("c")))
        val (a, b, c) = groceries.observeItems().first().map { it.id }

        groceries.setChecked(listOf(a, c), true, now = 5)
        groceries.setAisle(listOf(b), "dairy", now = 6)
        val rows = groceries.observeItems().first()
        assertEquals(listOf(true, false, true), rows.map { it.checked })
        assertEquals(listOf("other", "dairy", "other"), rows.map { it.aisle })
        assertEquals(listOf(5L, 6L, 5L), rows.map { it.updatedAt })

        assertEquals(listOf("a", "c"), groceries.checkedItems().map { it.text })
        groceries.delete(listOf(a, c))
        assertEquals(listOf("b"), texts())
    }

    @Test
    fun aDeleteRestoresWholeInItsPlace() = runBlocking {
        groceries.add(listOf(item("a"), item("b"), item("c")))
        val before = groceries.observeItems().first()
        val gone = groceries.items(listOf(before[1].id))

        groceries.delete(gone.map { it.id })
        groceries.restore(gone)

        assertEquals(before, groceries.observeItems().first())
    }

    @Test
    fun aRecipesItemsOutliveIt() = runBlocking {
        val id = recipe("https://example.com/a")
        groceries.add(listOf(item("1 cup flour", recipeId = id)))

        db.recipeDao().delete(id)

        val left = groceries.observeItems().first().single()
        assertEquals("1 cup flour", left.text)
        assertNull(left.recipeId)
    }

    @Test
    fun undoAfterTheRecipeWentDropsOnlyTheSource() = runBlocking {
        val id = recipe("https://example.com/a")
        groceries.add(listOf(item("1 cup flour", recipeId = id)))
        val saved = groceries.observeItems().first()

        groceries.delete(saved.map { it.id })
        db.recipeDao().delete(id)
        groceries.restore(saved)

        val left = groceries.observeItems().first().single()
        assertEquals("1 cup flour", left.text)
        assertNull(left.recipeId)
    }

    @Test
    fun theWeeksPlannedRecipesComeInPlanOrderWithoutNotes() = runBlocking {
        val soup = recipe("https://example.com/soup", listOf("1 onion"))
        val bread = recipe("https://example.com/bread", listOf("500 g flour"))
        val plan = db.mealPlanDao()
        val types = plan.observeMealTypes().first()
        val dinner = types.single { it.builtInKey == "dinner" }.id
        val lunch = types.single { it.builtInKey == "lunch" }.id
        fun entry(day: Long, type: Long, recipeId: Long?, note: String? = null, servings: Int? = null) =
            MealPlanEntryEntity(
                day = day, mealTypeId = type, recipeId = recipeId, servings = servings, note = note,
                sortOrder = 0, updatedAt = 1
            )
        plan.add(entry(20_001, dinner, soup, servings = 6))
        plan.add(entry(20_001, lunch, bread))
        plan.add(entry(20_000, dinner, null, note = "Leftovers"))
        plan.add(entry(20_010, dinner, soup))

        val planned = groceries.plannedIngredients(20_000, 20_006)
        assertEquals(listOf(bread, soup), planned.map { it.recipeId })
        assertEquals(listOf(null, 6), planned.map { it.servings })
        assertEquals(listOf("4", "4"), planned.map { it.yield })
        assertEquals(listOf("1 onion"), planned[1].ingredients)
        assertTrue(planned.all { it.day == 20_001L })
    }

    @Test
    fun theRecipesItemsCameFromAreNamed() = runBlocking {
        val soup = recipe("https://example.com/soup")
        val bread = recipe("https://example.com/bread")
        recipe("https://example.com/cake")
        groceries.add(listOf(item("1 onion", soup), item("2 onions", soup), item("flour", bread), item("milk")))
        assertEquals(
            mapOf(soup to "Recipe https://example.com/soup", bread to "Recipe https://example.com/bread"),
            groceries.observeRecipeTitles().first().associate { it.id to it.title }
        )
    }
}
