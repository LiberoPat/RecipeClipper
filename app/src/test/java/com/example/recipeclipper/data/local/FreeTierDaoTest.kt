package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.SampleRecipe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The free tier's rules (#107) against real SQLite: one out for one in, never below what's here. */
@RunWith(AndroidJUnit4::class)
class FreeTierDaoTest {

    private lateinit var db: RecipeDatabase
    private lateinit var recipes: RecipeDao
    private val free = LibraryLimit.Free(20)
    private val today = 20_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            .build()
        recipes = db.recipeDao()
    }

    @After
    fun close() = db.close()

    private fun recipe(url: String, viewedAt: Long, origin: String = "PARSED") = RecipeEntity(
        sourceUrl = url, title = "Recipe $url", imageUrl = null, ingredients = listOf("1 cup flour"),
        instructions = listOf("Bake."), prepTime = null, cookTime = null, totalTime = null,
        servings = "4", sourceType = "BLOG", lastViewedAt = viewedAt, contentOrigin = origin
    )

    private suspend fun add(url: String, viewedAt: Long, limit: LibraryLimit = free, origin: String = "PARSED") =
        recipes.upsert(recipe(url, viewedAt, origin), limit, today = today)

    /** [n] recipes put there before the limit, as the old history cap allowed. */
    private suspend fun existing(n: Int, from: Long = 100): List<Long> =
        (0 until n).map { add("https://old.com/$from-$it", from + it, LibraryLimit.Unlimited) }

    private suspend fun putInFavorites(id: Long) = db.openHelper.writableDatabase.execSQL(
        "INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (?, 1, 1)", arrayOf<Any>(id)
    )

    private suspend fun plan(id: Long, day: Long) {
        val dinner = db.mealPlanDao().observeMealTypes().first().single { it.builtInKey == "dinner" }.id
        db.mealPlanDao().add(
            MealPlanEntryEntity(day = day, mealTypeId = dinner, recipeId = id, servings = null, note = null, sortOrder = 0, updatedAt = 1)
        )
    }

    /** The tour's sample (#151) takes no place: not counted, and adding it removes nothing. */
    @Test
    fun theSampleRecipeTakesNoPlace() = runBlocking {
        val old = existing(20)
        val sample = recipes.upsert(recipe(SampleRecipe.SOURCE_URL, 50, "MANUAL"), LibraryLimit.Unlimited, today = today)
        assertEquals("the sample isn't counted", 20, recipes.count())
        assertEquals(20, recipes.observeCount().first())
        assertNotNull("nothing made room for it", recipes.get(old[0]))

        add("https://a.com/new", viewedAt = 10_000)
        assertNull("a new recipe still makes room one for one", recipes.get(old[0]))
        assertNotNull("never the sample, which is typed in", recipes.get(sample))
    }

    @Test
    fun underTheLimitNothingIsRemoved() = runBlocking {
        val first = existing(19)
        add("https://a.com/new", viewedAt = 10_000)
        assertEquals(20, recipes.count())
        assertNotNull(recipes.get(first.first()))
    }

    @Test
    fun atTheLimitTheOldestUnprotectedMakesRoomOneForOne() = runBlocking {
        val old = existing(20)
        val added = add("https://a.com/new", viewedAt = 10_000)
        assertNotNull(recipes.get(added))
        assertNull("the oldest viewed goes", recipes.get(old[0]))
        assertNotNull(recipes.get(old[1]))
        assertEquals(20, recipes.count())
    }

    @Test
    fun listedPlannedAndTypedInRecipesAreNeverTheOneRemoved() = runBlocking {
        val old = existing(16)
        putInFavorites(old[0])
        plan(old[1], today)
        plan(old[2], today + 3)
        val typed = add("manual:typed", viewedAt = 50, limit = LibraryLimit.Unlimited, origin = "MANUAL")
        plan(old[3], today - 1) // planned only in the past: ordinary again
        existing(3, from = 5_000) // 20 now
        add("https://a.com/new", viewedAt = 10_000)
        listOf(old[0], old[1], old[2], typed).forEach { assertNotNull(recipes.get(it)) }
        assertNull(recipes.get(old[3]))
        assertEquals(20, recipes.count())
    }

    @Test
    fun existingUsersOverTheLimitKeepEverything() = runBlocking {
        val old = existing(50)
        val added = add("https://a.com/new", viewedAt = 10_000)
        assertNotNull(recipes.get(added))
        // Room is made for the new one; the library is never cut down to 20.
        assertEquals(50, recipes.count())
        assertNull(recipes.get(old[0]))
        assertNotNull(recipes.get(old[1]))
    }

    @Test
    fun whenEveryRecipeIsProtectedTheNewOneIsShownButNotKept() = runBlocking {
        existing(20).forEach { putInFavorites(it) }
        val id = add("https://a.com/new", viewedAt = 10_000)
        assertEquals(RecipeDao.NOT_KEPT, id)
        assertNull(recipes.findByUrl("https://a.com/new"))
        assertEquals(20, recipes.count())
    }

    @Test
    fun reSharingARecipeAlreadyHereRemovesNothing() = runBlocking {
        val old = existing(20)
        add("https://old.com/100-5", viewedAt = 10_000)
        assertEquals(20, recipes.count())
        assertNotNull(recipes.get(old[0]))
    }

    @Test
    fun unlockedIsNeverCulled() = runBlocking {
        existing(60)
        add("https://a.com/new", viewedAt = 10_000, limit = LibraryLimit.Unlimited)
        assertEquals(61, recipes.count())
    }

    @Test
    fun theCountCountsEveryRecipe() = runBlocking {
        val old = existing(3)
        putInFavorites(old[0])
        add("manual:typed", viewedAt = 50, origin = "MANUAL")
        assertEquals(4, recipes.observeCount().first())
    }
}
