package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.HISTORY_LIMIT
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The database rules from CLAUDE.md, run against real SQLite on a device: they live in SQL,
 * so a JVM test with a fake would prove nothing.
 */
@RunWith(AndroidJUnit4::class)
class RecipeDaoTest {

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

    private fun recipe(
        url: String,
        viewedAt: Long,
        title: String = "Recipe $url",
        ingredients: List<String> = listOf("1 cup flour", "2 eggs"),
        checked: Set<Int> = emptySet()
    ) = RecipeEntity(
        sourceUrl = url,
        title = title,
        imageUrl = null,
        ingredients = ingredients,
        instructions = listOf("Mix.", "Bake 20 minutes."),
        prepTime = "5m",
        cookTime = "20m",
        totalTime = "25m",
        servings = "4",
        sourceType = "BLOG",
        lastViewedAt = viewedAt,
        checkedIngredients = checked
    )

    private suspend fun putInList(recipeId: Long, listId: Long, at: Long = 1) =
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (?, ?, ?)",
            arrayOf<Any>(recipeId, listId, at)
        )

    private fun count(): Int = db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM recipes").use { it.moveToFirst(); it.getInt(0) }

    // --- Seeding ---

    @Test
    fun builtInListsAreSeededOnFirstCreate() = runBlocking {
        val all = lists.observeLists(ListDao.NO_RECIPE).first()
        assertEquals(
            listOf("Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks"),
            all.map { it.name }
        )
        assertTrue(all.all { it.isBuiltIn })
    }

    @Test
    fun exactlyOneListIsFavoritesAndItIsFlaggedNotNamed() = runBlocking {
        val all = lists.observeLists(ListDao.NO_RECIPE).first()
        assertEquals(1, all.count { it.isFavorites })
        assertEquals("Favorites", all.single { it.isFavorites }.name)
    }

    // --- Upsert ---

    @Test
    fun aNewLinkIsInserted() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)
        assertTrue(id > 0)
        assertEquals("Recipe https://a.com/1", recipes.get(id)?.title)
        assertEquals(1, count())
    }

    @Test
    fun aReSharedLinkKeepsItsIdAndDoesNotDuplicate() = runBlocking {
        val first = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)
        val second = recipes.upsert(recipe("https://a.com/1", viewedAt = 900, title = "Renamed"), HISTORY_LIMIT)

        assertEquals(first, second)
        assertEquals(1, count())
        val saved = recipes.get(first)!!
        assertEquals(900L, saved.lastViewedAt)
        assertEquals("Renamed", saved.title)
    }

    @Test
    fun aReSharedLinkKeepsItsListMembership() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)
        putInList(id, listId = 1)

        recipes.upsert(recipe("https://a.com/1", viewedAt = 900), HISTORY_LIMIT)

        assertEquals(listOf(1L), recipes.crossRefsFor(id).map { it.listId })
    }

    @Test
    fun tickedIngredientsSurviveAReShareWhenTheIngredientsAreUnchanged() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)
        recipes.setChecked(id, setOf(0, 1))

        recipes.upsert(recipe("https://a.com/1", viewedAt = 900), HISTORY_LIMIT)

        assertEquals(setOf(0, 1), recipes.get(id)!!.checkedIngredients)
    }

    @Test
    fun tickedIngredientsResetWhenTheIngredientsChange() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)
        recipes.setChecked(id, setOf(0, 1))

        val changed = recipe("https://a.com/1", viewedAt = 900, ingredients = listOf("3 apples"))
        recipes.upsert(changed, HISTORY_LIMIT)

        assertEquals(emptySet<Int>(), recipes.get(id)!!.checkedIngredients)
    }

    // --- History cap ---

    @Test
    fun historyIsCappedAtTheLimitKeepingTheMostRecent() = runBlocking {
        val ids = (1..HISTORY_LIMIT + 5).map { n ->
            recipes.upsert(recipe("https://a.com/$n", viewedAt = n * 1000L), HISTORY_LIMIT)
        }

        assertEquals(HISTORY_LIMIT, count())
        // The five oldest are gone, the rest remain.
        ids.take(5).forEach { assertNull(recipes.get(it)) }
        ids.drop(5).forEach { assertNotNull(recipes.get(it)) }
    }

    @Test
    fun recipesInAListAreNeverCulled() = runBlocking {
        val oldest = recipes.upsert(recipe("https://a.com/old", viewedAt = 1), HISTORY_LIMIT)
        putInList(oldest, listId = 1)

        repeat(HISTORY_LIMIT + 5) { n ->
            recipes.upsert(recipe("https://a.com/$n", viewedAt = 1000L + n), HISTORY_LIMIT)
        }

        assertNotNull("a saved recipe must survive the cap", recipes.get(oldest))
        // The cap applies to the unsaved ones: 50 of them, plus the one saved recipe.
        assertEquals(HISTORY_LIMIT + 1, count())
    }

    @Test
    fun openingAnOldRecipeMovesItToTheTopSoItIsNotCulled() = runBlocking {
        val old = recipes.upsert(recipe("https://a.com/old", viewedAt = 1), HISTORY_LIMIT)
        repeat(HISTORY_LIMIT - 1) { n ->
            recipes.upsert(recipe("https://a.com/$n", viewedAt = 1000L + n), HISTORY_LIMIT)
        }
        recipes.touch(old, now = 999_999)

        recipes.upsert(recipe("https://a.com/new", viewedAt = 2_000_000), HISTORY_LIMIT)

        assertNotNull(recipes.get(old))
        assertEquals(HISTORY_LIMIT, count())
    }

    // --- Saved is derived ---

    @Test
    fun savedIsDerivedFromListMembership() = runBlocking {
        val kept = recipes.upsert(recipe("https://a.com/kept", viewedAt = 200), HISTORY_LIMIT)
        val plain = recipes.upsert(recipe("https://a.com/plain", viewedAt = 100), HISTORY_LIMIT)
        putInList(kept, listId = 2)

        val history = recipes.observeHistory().first().associateBy { it.id }
        assertTrue(history.getValue(kept).isSaved)
        assertFalse(history.getValue(plain).isSaved)
    }

    // --- Ordering, cascades, storage ---

    @Test
    fun historyIsNewestFirst() = runBlocking {
        val a = recipes.upsert(recipe("https://a.com/a", viewedAt = 100), HISTORY_LIMIT)
        val b = recipes.upsert(recipe("https://a.com/b", viewedAt = 300), HISTORY_LIMIT)
        val c = recipes.upsert(recipe("https://a.com/c", viewedAt = 200), HISTORY_LIMIT)

        assertEquals(listOf(b, c, a), recipes.observeHistory().first().map { it.id })
        assertEquals(listOf(b, c), recipes.observeRecent(2).first().map { it.id })
    }

    @Test
    fun deletingARecipeRemovesItsListMembership() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)
        putInList(id, listId = 1)

        db.openHelper.writableDatabase.execSQL("DELETE FROM recipes WHERE id = ?", arrayOf<Any>(id))

        val cursor = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM recipe_list_cross_ref")
        cursor.use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test
    fun aRecipeCannotBeAddedToAListThatDoesNotExist() {
        val id = runBlocking { recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT) }
        val failed = try {
            runBlocking { putInList(id, listId = 9999) }
            false
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            true
        }
        assertTrue("foreign keys must be enforced", failed)
    }

    @Test
    fun listsAndTickedIngredientsRoundTripThroughTheDatabase() = runBlocking {
        val ingredients = listOf("1 cup flour, sifted", "\"good\" oil", "½ tsp salt", "日本語")
        val id = recipes.upsert(
            recipe("https://a.com/1", viewedAt = 100, ingredients = ingredients, checked = setOf(1, 3)),
            HISTORY_LIMIT
        )

        val saved = recipes.get(id)!!
        assertEquals(ingredients, saved.ingredients)
        assertEquals(listOf("Mix.", "Bake 20 minutes."), saved.instructions)
        assertEquals(setOf(1, 3), saved.checkedIngredients)
    }

    @Test
    fun findByUrlReturnsNullForAnUnseenLink() = runBlocking {
        assertNull(recipes.findByUrl("https://never-seen.com"))
    }

    // --- Search ---

    @Test
    fun searchMatchesTitle() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100, title = "Chicken Adobo"), HISTORY_LIMIT)
        recipes.upsert(recipe("https://a.com/2", viewedAt = 200, title = "Beef Stew"), HISTORY_LIMIT)

        assertEquals(listOf(id), recipes.observeHistory("adobo").first().map { it.id })
    }

    @Test
    fun searchMatchesIngredients() = runBlocking {
        val id = recipes.upsert(
            recipe("https://a.com/1", viewedAt = 100, ingredients = listOf("2 anchovy fillets", "1 lemon")),
            HISTORY_LIMIT
        )
        recipes.upsert(
            recipe("https://a.com/2", viewedAt = 200, ingredients = listOf("2 cups flour")),
            HISTORY_LIMIT
        )

        assertEquals(listOf(id), recipes.observeHistory("anchovy").first().map { it.id })
    }

    @Test
    fun anEmptyQueryReturnsEverything() = runBlocking {
        val a = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)
        val b = recipes.upsert(recipe("https://a.com/2", viewedAt = 200), HISTORY_LIMIT)

        assertEquals(setOf(a, b), recipes.observeHistory("").first().map { it.id }.toSet())
    }

    @Test
    fun searchIsCaseInsensitive() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100, title = "Chicken ADOBO"), HISTORY_LIMIT)

        assertEquals(listOf(id), recipes.observeHistory("adobo").first().map { it.id })
        assertEquals(listOf(id), recipes.observeHistory("ADOBO").first().map { it.id })
    }

    @Test
    fun aQueryContainingAPercentSignMatchesLiterally() = runBlocking {
        // Regression guard: LIKE treats % as a wildcard. instr() must match only the literal
        // text, so a search for "100%" doesn't also match unrelated rows.
        val id = recipes.upsert(
            recipe("https://a.com/1", viewedAt = 100, title = "100% Whole Wheat Bread"),
            HISTORY_LIMIT
        )
        recipes.upsert(recipe("https://a.com/2", viewedAt = 200, title = "Sourdough Bread"), HISTORY_LIMIT)

        assertEquals(listOf(id), recipes.observeHistory("100%").first().map { it.id })
    }

    // --- Delete and restore ---

    @Test
    fun deletingARecipeByIdRemovesTheRow() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)

        recipes.delete(id)

        assertNull(recipes.get(id))
        assertEquals(0, count())
    }

    @Test
    fun deletingARecipeThroughDaoDeleteRemovesItsCrossRefs() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)
        putInList(id, listId = 1)

        recipes.delete(id)

        assertTrue(recipes.crossRefsFor(id).isEmpty())
    }

    @Test
    fun crossRefsForReturnsEmptyForARecipeInNoList() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)
        assertTrue(recipes.crossRefsFor(id).isEmpty())
    }

    @Test
    fun restoreBringsBackTheRowAndItsListMembershipWithTheSameId() = runBlocking {
        val id = recipes.upsert(
            recipe("https://a.com/1", viewedAt = 100, title = "Chicken Adobo"),
            HISTORY_LIMIT
        )
        putInList(id, listId = 1, at = 500)
        putInList(id, listId = 2, at = 600)

        val entity = recipes.get(id)!!
        val crossRefs = recipes.crossRefsFor(id)
        recipes.delete(id)
        assertNull(recipes.get(id))

        recipes.restore(entity, crossRefs)

        val restored = recipes.get(id)
        assertNotNull(restored)
        assertEquals(id, restored!!.id)
        assertEquals("Chicken Adobo", restored.title)
        assertEquals(setOf(1L, 2L), recipes.crossRefsFor(id).map { it.listId }.toSet())
    }

    @Test
    fun restoringARecipeWithNoListMembershipRestoresJustTheRow() = runBlocking {
        val id = recipes.upsert(recipe("https://a.com/1", viewedAt = 100), HISTORY_LIMIT)
        val entity = recipes.get(id)!!
        val crossRefs = recipes.crossRefsFor(id)
        recipes.delete(id)

        recipes.restore(entity, crossRefs)

        assertNotNull(recipes.get(id))
        assertTrue(recipes.crossRefsFor(id).isEmpty())
    }
}
