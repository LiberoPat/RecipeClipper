package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * List membership against real SQLite, for the same reason [RecipeDaoTest] runs on a device:
 * these rules are SQL — the built-in ordering, the derived counts, the cascade, and the
 * `isBuiltIn = 0` guard on delete — and a fake would only re-test the fake.
 */
@RunWith(AndroidJUnit4::class)
class ListDaoTest {

    private lateinit var db: RecipeDatabase
    private lateinit var recipes: RecipeDao
    private lateinit var lists: ListDao

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists) // the same callback production uses
            .build()
        recipes = db.recipeDao()
        lists = db.listDao()
    }

    @After
    fun close() = db.close()

    private fun recipe(url: String, title: String = "Recipe $url") = RecipeEntity(
        sourceUrl = url,
        title = title,
        imageUrl = null,
        ingredients = listOf("1 cup flour"),
        instructions = listOf("Mix."),
        prepTime = null,
        cookTime = null,
        totalTime = null,
        servings = "4",
        sourceType = "BLOG",
        lastViewedAt = 1
    )

    private suspend fun addRecipe(url: String) = recipes.upsert(recipe(url), historyLimit = 50)

    private suspend fun allLists() = lists.observeLists(ListDao.NO_RECIPE).first()

    private suspend fun favoritesId() = allLists().single { it.isFavorites }.id

    // --- The seeded built-ins ---

    /** That the four are seeded at all is covered by [RecipeDaoTest]; this is their order. */
    @Test
    fun favoritesSortsFirstAmongTheBuiltIns() = runBlocking {
        assertTrue(allLists().first().isFavorites)
    }

    @Test
    fun builtInsSortBeforeUserListsWhateverTheirNames() = runBlocking {
        // "Aaa" would sort first alphabetically; ordering is by isBuiltIn then sortOrder.
        lists.create("Aaa", ListDao.NO_RECIPE, now = 10)
        assertEquals(
            listOf("Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks", "Aaa"),
            allLists().map { it.name }
        )
    }

    @Test
    fun newListsKeepTheOrderTheyWereCreatedIn() = runBlocking {
        lists.create("First", ListDao.NO_RECIPE, now = 10)
        lists.create("Second", ListDao.NO_RECIPE, now = 11)
        lists.create("Third", ListDao.NO_RECIPE, now = 12)
        assertEquals(
            listOf("First", "Second", "Third"),
            allLists().filterNot { it.isBuiltIn }.map { it.name }
        )
    }

    // --- Counts and containsRecipe ---

    @Test
    fun countsAreDerivedFromMembership() = runBlocking {
        val favorites = favoritesId()
        val a = addRecipe("https://example.com/a")
        val b = addRecipe("https://example.com/b")
        lists.addToList(RecipeListCrossRef(a, favorites, 1))
        lists.addToList(RecipeListCrossRef(b, favorites, 2))

        assertEquals(2, allLists().single { it.id == favorites }.recipeCount)
        assertEquals(0, allLists().single { it.name == "Lunch" }.recipeCount)
    }

    @Test
    fun containsRecipeIsTrueOnlyForTheRecipeAskedAbout() = runBlocking {
        val favorites = favoritesId()
        val mine = addRecipe("https://example.com/mine")
        val other = addRecipe("https://example.com/other")
        lists.addToList(RecipeListCrossRef(other, favorites, 1))

        val forMine = lists.observeLists(mine).first()
        assertFalse(forMine.single { it.id == favorites }.containsRecipe)
        // The other recipe still counts towards the list's size.
        assertEquals(1, forMine.single { it.id == favorites }.recipeCount)

        lists.addToList(RecipeListCrossRef(mine, favorites, 2))
        assertTrue(lists.observeLists(mine).first().single { it.id == favorites }.containsRecipe)
    }

    @Test
    fun noRecipeSentinelNeverMatchesAnything() = runBlocking {
        val favorites = favoritesId()
        val id = addRecipe("https://example.com/a")
        lists.addToList(RecipeListCrossRef(id, favorites, 1))

        assertTrue(allLists().none { it.containsRecipe })
    }

    // --- Adding and removing ---

    @Test
    fun addingTwiceIsANoOpAndKeepsTheOriginalAddedAt() = runBlocking {
        val favorites = favoritesId()
        val id = addRecipe("https://example.com/a")
        lists.addToList(RecipeListCrossRef(id, favorites, addedAt = 100))
        lists.addToList(RecipeListCrossRef(id, favorites, addedAt = 999))

        assertEquals(1, allLists().single { it.id == favorites }.recipeCount)
        // addedAt orders the list, so an IGNORE that silently became a REPLACE would reorder it.
        assertEquals(100L, recipes.crossRefsFor(id).single().addedAt)
    }

    @Test
    fun removingTakesOutOnlyThatOnePairing() = runBlocking {
        val favorites = favoritesId()
        val lunch = allLists().single { it.name == "Lunch" }.id
        val id = addRecipe("https://example.com/a")
        lists.addToList(RecipeListCrossRef(id, favorites, 1))
        lists.addToList(RecipeListCrossRef(id, lunch, 2))

        lists.removeFromList(id, favorites)

        assertEquals(listOf(lunch), recipes.crossRefsFor(id).map { it.listId })
    }

    /** The answer to CLAUDE.md's open question: out of its last list is back to history. */
    @Test
    fun removingFromTheLastListLeavesTheRecipeInHistory() = runBlocking {
        val favorites = favoritesId()
        val id = addRecipe("https://example.com/a")
        lists.addToList(RecipeListCrossRef(id, favorites, 1))

        lists.removeFromList(id, favorites)

        assertNotNull(recipes.get(id))
        val row = recipes.observeHistory().first().single { it.id == id }
        assertFalse(row.isSaved) // no longer saved, but still there
    }

    @Test
    fun recipesInAListComeBackNewestAddedFirst() = runBlocking {
        val favorites = favoritesId()
        val a = addRecipe("https://example.com/a")
        val b = addRecipe("https://example.com/b")
        val c = addRecipe("https://example.com/c")
        lists.addToList(RecipeListCrossRef(a, favorites, addedAt = 10))
        lists.addToList(RecipeListCrossRef(b, favorites, addedAt = 30))
        lists.addToList(RecipeListCrossRef(c, favorites, addedAt = 20))

        assertEquals(listOf(b, c, a), lists.observeRecipesIn(favorites).first().map { it.id })
    }

    @Test
    fun recipesInAListAreAlwaysMarkedSaved() = runBlocking {
        val favorites = favoritesId()
        val id = addRecipe("https://example.com/a")
        lists.addToList(RecipeListCrossRef(id, favorites, 1))

        assertTrue(lists.observeRecipesIn(favorites).first().single().isSaved)
    }

    @Test
    fun anEmptyListReturnsNoRecipesRatherThanFailing() = runBlocking {
        assertTrue(lists.observeRecipesIn(favoritesId()).first().isEmpty())
    }

    // --- Creating ---

    @Test
    fun creatingWithARecipePutsThatRecipeStraightIn() = runBlocking {
        val id = addRecipe("https://example.com/a")
        val listId = lists.create("Weeknights", recipeId = id, now = 50)

        val created = allLists().single { it.id == listId }
        assertEquals(1, created.recipeCount)
        assertFalse(created.isBuiltIn)
        assertFalse(created.isFavorites)
        assertEquals(listOf(listId), recipes.crossRefsFor(id).map { it.listId })
    }

    @Test
    fun creatingWithoutARecipeLeavesTheListEmpty() = runBlocking {
        val listId = lists.create("Weeknights", ListDao.NO_RECIPE, now = 50)
        assertEquals(0, allLists().single { it.id == listId }.recipeCount)
    }

    // --- Renaming ---

    @Test
    fun renamingAUserListWorks() = runBlocking {
        val listId = lists.create("Weeknights", ListDao.NO_RECIPE, now = 50)
        lists.rename(listId, "Midweek")
        assertEquals("Midweek", allLists().single { it.id == listId }.name)
    }

    @Test
    fun renamingABuiltInWorksAndItStaysBuiltIn() = runBlocking {
        val favorites = favoritesId()
        lists.rename(favorites, "Best of")

        val renamed = allLists().single { it.id == favorites }
        assertEquals("Best of", renamed.name)
        // isFavorites is a column, never a name match — this is what that protects.
        assertTrue(renamed.isFavorites)
        assertTrue(renamed.isBuiltIn)
        assertEquals(favorites, allLists().single { it.isFavorites }.id)
    }

    // --- Deleting ---

    @Test
    fun deletingAUserListRemovesItAndItsMembership() = runBlocking {
        val id = addRecipe("https://example.com/a")
        val listId = lists.create("Weeknights", recipeId = id, now = 50)

        lists.delete(listId)

        assertTrue(allLists().none { it.id == listId })
        assertTrue(recipes.crossRefsFor(id).isEmpty()) // cascaded
    }

    @Test
    fun deletingAListNeverDeletesItsRecipes() = runBlocking {
        val id = addRecipe("https://example.com/a")
        val listId = lists.create("Weeknights", recipeId = id, now = 50)

        lists.delete(listId)

        assertNotNull(recipes.get(id))
    }

    /** Favorites is the one list that cannot go: "saved" is built around it. */
    @Test
    fun deletingFavoritesIsRefused() = runBlocking {
        val favorites = favoritesId()
        val id = addRecipe("https://example.com/a")
        lists.addToList(RecipeListCrossRef(id, favorites, 1))

        lists.delete(favorites)

        assertNotNull(allLists().singleOrNull { it.id == favorites })
        assertEquals(1, allLists().single { it.id == favorites }.recipeCount)
    }

    /**
     * The other seeded lists are starting suggestions, not fixtures. This is what would break
     * if the delete guard ever went back to `isBuiltIn = 0`.
     */
    @Test
    fun deletingASeededListThatIsNotFavoritesWorks() = runBlocking {
        val lunch = allLists().single { it.name == "Lunch" }
        assertTrue(lunch.isBuiltIn)
        val id = addRecipe("https://example.com/a")
        lists.addToList(RecipeListCrossRef(id, lunch.id, 1))

        lists.delete(lunch.id)

        assertTrue(allLists().none { it.name == "Lunch" })
        assertTrue(recipes.crossRefsFor(id).isEmpty()) // cascaded
        assertNotNull(recipes.get(id))                 // the recipe itself survives
    }

    @Test
    fun everySeededListExceptFavoritesCanBeDeleted() = runBlocking {
        allLists().filterNot { it.isFavorites }.forEach { lists.delete(it.id) }

        val left = allLists()
        assertEquals(1, left.size)
        assertTrue(left.single().isFavorites)
    }

    @Test
    fun deletingARecipeCascadesOutOfItsLists() = runBlocking {
        val favorites = favoritesId()
        val id = addRecipe("https://example.com/a")
        lists.addToList(RecipeListCrossRef(id, favorites, 1))

        recipes.delete(id)

        assertEquals(0, allLists().single { it.id == favorites }.recipeCount)
        assertTrue(lists.observeRecipesIn(favorites).first().isEmpty())
    }
}
