package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.DefaultShareFileRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.LibraryPolicy
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.backup.PantryDestination
import com.example.recipeclipper.data.backup.ShareChoice
import com.example.recipeclipper.data.backup.decodeOrFail
import com.example.recipeclipper.data.backup.fixture
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared file (#149, phase 2) against real SQLite: what "Send as file" reads, and a received
 * file merged in (never replacing a recipe here, each item once, the history cap after).
 */
@RunWith(AndroidJUnit4::class)
class ShareFileRepositoryTest {

    private lateinit var db: RecipeDatabase
    private val log = ErrorLog { _, _ -> }
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    private fun repo(limit: LibraryLimit = LibraryLimit.History(50)) = DefaultShareFileRepository(
        db, { now }, log,
        object : LibraryPolicy {
            override val limit = limit
            override val limits: Flow<LibraryLimit> = flowOf(limit)
        }
    )

    private fun recipe(url: String, title: String = "Soup", viewed: Long = 1) = RecipeEntity(
        sourceUrl = url, title = title, imageUrl = null, ingredients = listOf("1 egg", "2 cups flour"),
        instructions = listOf("Cook."), prepTime = null, cookTime = null, totalTime = null,
        servings = "2", sourceType = "BLOG", lastViewedAt = viewed, checkedIngredients = setOf(1), notes = "salt"
    )

    private fun grocery(text: String, recipeId: Long?, checked: Boolean = false, order: Int = 0) = GroceryItemEntity(
        text = text, language = "en", aisle = "other", checked = checked, sortOrder = order,
        recipeId = recipeId, plannedDay = null, updatedAt = 1
    )

    private fun success(result: BackupResult<ImportSummary>): ImportSummary =
        (result as? BackupResult.Success)?.value ?: throw AssertionError("receive failed: $result")

    @Test
    fun aRecipeIsSentCompleteWithoutTheSendersTicksOrNote() = runBlocking {
        val id = db.recipeDao().upsert(recipe("https://example.com/soup"), 50)

        val file = decodeOrFail(repo().recipeFile(id)!!)

        assertTrue(file.isShare)
        val sent = file.recipes.single()
        assertEquals(listOf("1 egg", "2 cups flour"), sent.ingredients)
        assertTrue(sent.checkedIngredients.isEmpty())
        assertNull(sent.notes)
        assertTrue(file.groceries.isEmpty() && file.lists.isEmpty())
        assertNull(repo().recipeFile(id + 99))
    }

    @Test
    fun theGroceriesFileHoldsTheUntickedItemsAndTheirRecipes() = runBlocking {
        val soup = db.recipeDao().upsert(recipe("https://example.com/soup", "Soup"), 50)
        db.recipeDao().upsert(recipe("https://example.com/other", "Other"), 50)
        db.groceryDao().add(
            listOf(grocery("2 onions", soup), grocery("bread", null), grocery("1 egg", soup, checked = true))
        )

        val file = decodeOrFail(repo().groceriesFile()!!)

        assertEquals(listOf("2 onions", "bread"), file.groceries.map { it.text })
        assertEquals(listOf("Soup"), file.recipes.map { it.title })
        assertEquals(listOf(file.recipes.single().id, null), file.groceries.map { it.recipeId })

        db.groceryDao().setChecked(db.groceryDao().observeItems().first().map { it.id }, true, 2)
        assertNull(repo().groceriesFile())
    }

    @Test
    fun thePantryFileHoldsWhatIsInStockAsItIs() = runBlocking {
        fun item(name: String, inStock: Boolean, quantity: String? = null) = PantryItemEntity(
            name = name, quantity = quantity, language = "en", aisle = "grains", inStock = inStock,
            alwaysHave = false, purchasedDay = 20_000, expiresDay = 20_100, updatedAt = 1
        )
        val rice = db.pantryDao().insert(item("basmati rice", inStock = true, quantity = "half a bag"))
        db.pantryDao().insert(item("oats", inStock = false))

        val file = decodeOrFail(repo().pantryFile()!!)

        assertTrue(file.isShare)
        val sent = file.pantry.single()
        assertEquals("basmati rice", sent.name)
        assertEquals("half a bag", sent.quantity)
        assertEquals(20_100L, sent.expiresDay)
        assertEquals(db.pantryDao().item(rice)!!.uid, sent.id)
        assertTrue(file.recipes.isEmpty() && file.groceries.isEmpty())

        db.pantryDao().setInStock(listOf(rice), false, 2)
        assertNull(repo().pantryFile())
    }

    @Test
    fun aReceivedFileMergesWithoutReplacingAndOnlyOnce() = runBlocking {
        // The chicken is here already, under a link with a tracking parameter the file's lacks.
        val here = db.recipeDao().upsert(recipe("https://example.com/sheet-pan-chicken", "My chicken", viewed = 3), 50)
        val file = decodeOrFail(fixture("share-v1.recipeclipper"))
        val everything = ShareChoice(
            recipeIds = setOf("r-chicken", "r-cake"),
            groceryIds = setOf("g-thighs", "g-lemon", "g-paper"),
            pantryIds = setOf("p-rice"),
            pantryTo = PantryDestination.GROCERIES
        )

        val summary = success(repo().receive(file, everything))

        assertEquals(1, summary.recipesAdded)
        assertEquals(1, summary.recipesAlreadyHere)
        val chicken = db.recipeDao().get(here)!!
        assertEquals("My chicken", chicken.title)
        assertEquals("salt", chicken.notes)
        assertEquals(now, chicken.lastViewedAt)
        val cake = db.recipeDao().findByUrl("manual:5d1c2a4e-2f61-4a8e-9b1a-6e0f2c9d7a11")
        assertNotNull(cake)
        assertEquals(now, cake!!.lastViewedAt)
        assertTrue(cake.checkedIngredients.isEmpty())

        val items = db.groceryDao().observeItems().first()
        assertEquals(listOf("2 lb chicken thighs", "1 lemon", "baking paper", "Basmati rice"), items.map { it.text })
        assertEquals(listOf(here, here, null, null), items.map { it.recipeId })
        assertTrue(items.none { it.checked })
        assertTrue(db.pantryDao().items().isEmpty())

        // The same file opened again adds nothing.
        val again = success(repo().receive(file, everything))
        assertEquals(0, again.recipesAdded + again.groceriesAdded)
        assertEquals(4, db.groceryDao().observeItems().first().size)
    }

    @Test
    fun pantryItemsGoToThePantryWhereWhatIsHereStands() = runBlocking {
        db.pantryDao().insert(
            PantryItemEntity(
                name = "basmati rice", quantity = null, language = "en", aisle = "grains", inStock = false,
                alwaysHave = false, purchasedDay = null, expiresDay = null, updatedAt = 1
            )
        )
        val file = decodeOrFail(fixture("share-v1.recipeclipper"))

        val summary = success(repo().receive(file, ShareChoice(emptySet(), emptySet(), setOf("p-rice"))))

        assertEquals(0, summary.pantryAdded)
        assertEquals(listOf(false), db.pantryDao().items().map { it.inStock })
        assertTrue(db.groceryDao().observeItems().first().isEmpty())
    }

    @Test
    fun aReceivedRecipeIsTheNewestViewedAndTheHistoryCapFollows() = runBlocking {
        db.recipeDao().upsert(recipe("https://example.com/old", viewed = 1), 50)
        db.recipeDao().upsert(recipe("https://example.com/newer", viewed = 2), 50)
        val file = decodeOrFail(fixture("share-v1.recipeclipper"))

        success(repo(LibraryLimit.History(2)).receive(file, ShareChoice(setOf("r-chicken"), emptySet())))

        assertNull(db.recipeDao().findByUrl("https://example.com/old"))
        assertNotNull(db.recipeDao().findByUrl("https://example.com/newer"))
        assertNotNull(db.recipeDao().findByUrl("https://example.com/sheet-pan-chicken"))
    }
}
