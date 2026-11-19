package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.CookedPhotoEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.SampleRecipe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One cookbook (#345) against real SQLite: the cap never removes, the limit never culls, and the
 * automatic views (Cooked, Typed by you, Not in a list) are plain questions about what is stored.
 */
@RunWith(AndroidJUnit4::class)
class OneCookbookDaoTest {

    private lateinit var db: RecipeDatabase
    private lateinit var recipes: RecipeDao
    private val capped = LibraryLimit.Capped(50)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            .allowMainThreadQueries()
            .build()
        recipes = db.recipeDao()
    }

    @After
    fun close() = db.close()

    private fun recipe(url: String, viewedAt: Long = 1, origin: String = "PARSED") = RecipeEntity(
        sourceUrl = url, title = "Recipe $url", imageUrl = null, ingredients = listOf("1 cup flour"),
        instructions = listOf("Bake."), prepTime = null, cookTime = null, totalTime = null,
        servings = "4", sourceType = "BLOG", lastViewedAt = viewedAt, contentOrigin = origin
    )

    private suspend fun fill(n: Int) = (0 until n).map {
        recipes.upsert(recipe("https://old.com/$it", viewedAt = it.toLong()), LibraryLimit.Unlimited)
    }

    @Test
    fun atTheLimitANewRecipeIsNotKeptAndNothingIsRemoved() = runBlocking {
        val old = fill(50)
        assertEquals(RecipeDao.NOT_KEPT, recipes.upsert(recipe("https://a.com/new", 10_000), capped))
        assertEquals(50, recipes.count())
        old.forEach { assertNotNull("nothing is removed to make room", recipes.get(it)) }
    }

    @Test
    fun underTheLimitARecipeIsKeptAndTheFiftiethFits() = runBlocking {
        fill(49)
        val id = recipes.upsert(recipe("https://a.com/new", 10_000), capped)
        assertNotNull(recipes.get(id))
        assertEquals(50, recipes.count())
    }

    @Test
    fun aRecipeAlreadyHereIsRefreshedAtTheLimit() = runBlocking {
        val first = recipes.upsert(recipe("https://a.com/soup"), LibraryLimit.Unlimited)
        fill(49)
        val again = recipes.upsert(recipe("https://a.com/soup", viewedAt = 99_000).copy(title = "Soup, better"), capped)
        assertEquals(first, again)
        assertEquals("Soup, better", recipes.get(first)!!.title)
        assertEquals(50, recipes.count())
    }

    @Test
    fun theTourSampleTakesNoPlace() = runBlocking {
        recipes.upsert(recipe(SampleRecipe.SOURCE_URL, 5, "MANUAL"), LibraryLimit.Unlimited)
        fill(50)
        assertEquals(50, recipes.count())
        assertEquals(RecipeDao.NOT_KEPT, recipes.upsert(recipe("https://a.com/new", 10_000), capped))
    }

    @Test
    fun noLimitNeverCullsPastFifty() = runBlocking {
        val ids = fill(60)
        recipes.upsert(recipe("https://a.com/new", 10_000), LibraryLimit.of(freeTier = false, unlocked = false, oneCookbook = true))
        assertEquals(61, recipes.count())
        ids.forEach { assertNotNull(recipes.get(it)) }
    }

    @Test
    fun theViewsAreQuestionsAboutWhatIsStored() = runBlocking {
        val inList = recipes.upsert(recipe("https://a.com/listed"), LibraryLimit.Unlimited)
        val cooked = recipes.upsert(recipe("https://a.com/cooked"), LibraryLimit.Unlimited)
        val typed = recipes.upsert(recipe("manual:typed", origin = "MANUAL"), LibraryLimit.Unlimited)
        val sample = recipes.upsert(recipe(SampleRecipe.SOURCE_URL, origin = "MANUAL"), LibraryLimit.Unlimited)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (?, 1, 1)", arrayOf<Any>(inList)
        )
        // "Mark as cooked" (#173) with no photo counts as cooked.
        db.cookedPhotoDao().insert(
            CookedPhotoEntity(recipeId = cooked, fileName = null, day = 20_000, note = null, createdAt = 1, updatedAt = 1)
        )

        fun ids(rows: List<com.example.recipeclipper.data.local.dao.RecipeSummaryRow>) = rows.map { it.id }.toSet()
        assertEquals(setOf(inList, cooked, typed, sample), ids(recipes.observeHistory("", RecipeDao.LISTED_ANY, 0, 0).first()))
        assertEquals(setOf(cooked, typed, sample), ids(recipes.observeHistory("", RecipeDao.LISTED_NO, 0, 0).first()))
        assertEquals(setOf(cooked), ids(recipes.observeHistory("", RecipeDao.LISTED_ANY, 1, 0).first()))
        assertEquals("never the tour's sample", setOf(typed), ids(recipes.observeHistory("", RecipeDao.LISTED_ANY, 0, 1).first()))
        assertEquals("the search still applies inside a view", emptySet<Long>(), ids(recipes.observeHistory("zzz", RecipeDao.LISTED_ANY, 0, 1).first()))

        val counts = recipes.observeLibraryCounts().first()
        assertEquals(4, counts.total)
        assertEquals(1, counts.inList)
        assertEquals(1, counts.cooked)
        assertEquals(1, counts.typed)
    }
}
