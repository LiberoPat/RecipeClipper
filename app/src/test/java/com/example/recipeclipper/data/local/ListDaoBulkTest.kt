package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.LibraryLimit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Several recipes at once (#377), against real SQLite like [ListDaoTest]: ticking a list for all of
 * them, a new list that takes all of them, the per-list counts the sheet's indeterminate state
 * reads, and deleting several recipes. Each is one transaction, so a failure part-way leaves nothing
 * half done.
 */
@RunWith(AndroidJUnit4::class)
class ListDaoBulkTest {

    private lateinit var db: RecipeDatabase
    private lateinit var recipes: RecipeDao
    private lateinit var lists: ListDao

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            .allowMainThreadQueries()
            .build()
        recipes = db.recipeDao()
        lists = db.listDao()
    }

    @After
    fun close() = db.close()

    private fun recipe(url: String) = RecipeEntity(
        sourceUrl = url, title = "Recipe $url", imageUrl = null, ingredients = listOf("1 cup flour"),
        instructions = listOf("Mix."), prepTime = null, cookTime = null, totalTime = null,
        servings = "4", sourceType = "BLOG", lastViewedAt = 1
    )

    private suspend fun addRecipe(url: String) = recipes.upsert(recipe(url), LibraryLimit.Unlimited)

    private suspend fun listIdOf(name: String) = lists.observeLists(ListDao.NO_RECIPE).first().single { it.name == name }.id

    private suspend fun membersOf(listId: Long) =
        lists.observeRecipesIn(listId).first().map { it.id }.toSet()

    @Test
    fun tickingAddsEveryRecipe_andKeepsTheAddedAtOfThoseAlreadyIn() = runBlocking {
        val a = addRecipe("https://a.com")
        val b = addRecipe("https://b.com")
        val c = addRecipe("https://c.com")
        val lunch = listIdOf("Lunch")
        lists.addToList(RecipeListCrossRef(a, lunch, addedAt = 5))

        lists.setMembers(listOf(a, b, c), lunch, inList = true, now = 100)

        assertEquals(setOf(a, b, c), membersOf(lunch))
        // IGNORE, not REPLACE: the one already in keeps the time it joined (it sorts after the new ones).
        assertEquals(a, lists.observeRecipesIn(lunch).first().last().id)
    }

    @Test
    fun untickingTakesEveryRecipeOffThatList_andNoOther() = runBlocking {
        val a = addRecipe("https://a.com")
        val b = addRecipe("https://b.com")
        val lunch = listIdOf("Lunch")
        val dinner = listIdOf("Dinner")
        listOf(a, b).forEach {
            lists.addToList(RecipeListCrossRef(it, lunch, 1))
            lists.addToList(RecipeListCrossRef(it, dinner, 1))
        }

        lists.setMembers(listOf(a, b), lunch, inList = false, now = 100)

        assertEquals(emptySet<Long>(), membersOf(lunch))
        assertEquals(setOf(a, b), membersOf(dinner))
        // Taking recipes off a list never deletes them.
        assertEquals(2, recipes.count())
    }

    @Test
    fun aFailurePartWayAddsNone() = runBlocking {
        val a = addRecipe("https://a.com")
        val lunch = listIdOf("Lunch")

        // 9999 is no recipe: the foreign key refuses it, and the insert for [a] before it is undone.
        try {
            lists.setMembers(listOf(a, 9_999L), lunch, inList = true, now = 100)
            fail("the foreign key should refuse a recipe that is not there")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            // expected
        }

        assertEquals(emptySet<Long>(), membersOf(lunch))
    }

    @Test
    fun aNewListTakesEveryRecipe() = runBlocking {
        val a = addRecipe("https://a.com")
        val b = addRecipe("https://b.com")

        val id = lists.createWith("Weeknights", listOf(a, b), now = 50)

        assertEquals(setOf(a, b), membersOf(id))
        assertEquals("Weeknights", lists.observeLists(ListDao.NO_RECIPE).first().single { it.id == id }.name)
    }

    @Test
    fun aNewListWithARecipeThatIsGoneIsNotCreatedAtAll() = runBlocking {
        val a = addRecipe("https://a.com")
        val before = lists.observeLists(ListDao.NO_RECIPE).first().size

        try {
            lists.createWith("Weeknights", listOf(a, 9_999L), now = 50)
            fail("the foreign key should refuse a recipe that is not there")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            // expected
        }

        assertEquals("no list without its recipes", before, lists.observeLists(ListDao.NO_RECIPE).first().size)
    }

    @Test
    fun countsSayHowManyOfTheRecipesEachListHolds() = runBlocking {
        val a = addRecipe("https://a.com")
        val b = addRecipe("https://b.com")
        val c = addRecipe("https://c.com")
        val favorites = listIdOf("Favorites")
        val lunch = listIdOf("Lunch")
        lists.addToList(RecipeListCrossRef(a, favorites, 1))
        lists.addToList(RecipeListCrossRef(b, favorites, 1))
        lists.addToList(RecipeListCrossRef(a, lunch, 1))
        lists.addToList(RecipeListCrossRef(c, lunch, 1)) // not among the ones asked about

        val counts = lists.observeMemberCounts(listOf(a, b)).first().associate { it.listId to it.memberCount }

        assertEquals(mapOf(favorites to 2, lunch to 1), counts)
    }

    @Test
    fun deletingSeveralRecipesGoesInOneTransaction_andTheirRowsComeBackTogether() = runBlocking {
        val a = addRecipe("https://a.com")
        val b = addRecipe("https://b.com")
        val c = addRecipe("https://c.com")
        val lunch = listIdOf("Lunch")
        lists.addToList(RecipeListCrossRef(a, lunch, 1))
        lists.addToList(RecipeListCrossRef(b, lunch, 2))

        val removed = recipes.deleteAll(listOf(a, b, 9_999L))

        assertEquals(listOf(a, b), removed.map { it.recipe.id })
        assertEquals("their list rows were read first, for the undo", listOf(lunch), removed.first().crossRefs.map { it.listId })
        assertEquals(1, recipes.count())
        assertNotNull(recipes.get(c))
        assertEquals(emptySet<Long>(), membersOf(lunch))

        assertTrue(recipes.restoreAll(removed.map { RecipeDao.DeletedRows(it.recipe, it.crossRefs, it.planEntries, it.menuEntries, it.cookedPhotos, it.versionIds) }, LibraryLimit.Unlimited))
        assertEquals(setOf(a, b), membersOf(lunch))
    }
}
