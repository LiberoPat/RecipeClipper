package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.DefaultBackupRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ExportedBackup
import com.example.recipeclipper.data.local.dao.ListDao
import com.example.recipeclipper.data.local.entity.GroceryItemEntity
import com.example.recipeclipper.data.local.entity.PantryItemEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Export and import against real SQLite: the parts that are SQL, so a JVM fake can't prove
 * them — insert-or-ignore keeping `addedAt`, one transaction rolling back a failed import, and
 * an export importing into an empty phone intact. The merge rules themselves are the pure
 * `BackupMerger`, tested on the JVM against the shared fixture.
 */
@RunWith(AndroidJUnit4::class)
class BackupDaoTest {

    private lateinit var db: RecipeDatabase
    private val log = ErrorLog { _, _ -> }

    private fun open(): RecipeDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            .build()

    @Before
    fun setUp() {
        db = open()
    }

    @After
    fun close() = db.close()

    private fun recipe(url: String, viewed: Long = 1) = RecipeEntity(
        sourceUrl = url, title = "R", imageUrl = null, ingredients = listOf("1 egg", "2 eggs"),
        instructions = listOf("Cook."), prepTime = null, cookTime = null, totalTime = null,
        servings = "2", sourceType = "BLOG", lastViewedAt = viewed, checkedIngredients = setOf(1), notes = "salt"
    )

    private fun repo(on: RecipeDatabase) = DefaultBackupRepository(on.backupDao(), { 99L }, log)

    @Test
    fun anExportImportsIntoAnEmptyPhoneIntact() = runBlocking {
        val id = db.recipeDao().upsert(recipe("https://example.com/a", viewed = 5), 50)
        val listId = db.listDao().create("Weeknight", id, now = 42)
        val favorites = db.listDao().observeLists(ListDao.NO_RECIPE).first().single { it.isFavorites }.id
        db.listDao().addToList(RecipeListCrossRef(id, favorites, 7))

        val exported = (repo(db).export() as BackupResult.Success<ExportedBackup>).value
        val other = open()
        try {
            val summary = repo(other).import(exported.json)
            assertTrue(summary is BackupResult.Success)

            val original = db.recipeDao().get(id)!!
            val copy = other.recipeDao().findByUrl("https://example.com/a")!!
            assertEquals(original.copy(id = copy.id), copy)
            val lists = other.listDao().observeLists(ListDao.NO_RECIPE).first()
            val refs = other.recipeDao().crossRefsFor(copy.id).associate { it.listId to it.addedAt }
            assertEquals(7L, refs[lists.single { it.isFavorites }.id])
            assertEquals(42L, refs[lists.single { it.name == "Weeknight" }.id])
            assertEquals(1, lists.count { it.isFavorites })
        } finally {
            other.close()
        }
        assertTrue(listId > 0)
    }

    /** The pantry (#51) and the grocery list (#50) go into the file and come back whole, a grocery keeping its recipe. */
    @Test
    fun pantryAndGroceriesRoundTrip() = runBlocking {
        val id = db.recipeDao().upsert(recipe("https://example.com/a"), 50)
        db.pantryDao().insert(
            PantryItemEntity(name = "flour", quantity = "half a bag", language = "en", aisle = "baking", inStock = false,
                alwaysHave = true, purchasedDay = 20_000, expiresDay = 20_100, updatedAt = 3)
        )
        db.groceryDao().add(
            listOf(GroceryItemEntity(text = "2 eggs", language = "en", aisle = "dairy", sortOrder = 0, recipeId = id,
                plannedDay = 20_001, updatedAt = 4))
        )

        val exported = (repo(db).export() as BackupResult.Success<ExportedBackup>).value
        val other = open()
        try {
            val summary = (repo(other).import(exported.json) as BackupResult.Success).value
            assertEquals(1, summary.pantryAdded)
            assertEquals(1, summary.groceriesAdded)

            val original = db.pantryDao().items().single()
            assertEquals(original.copy(id = other.pantryDao().items().single().id), other.pantryDao().items().single())
            val grocery = other.groceryDao().observeItems().first().single()
            assertEquals("2 eggs", grocery.text)
            assertEquals(other.recipeDao().findByUrl("https://example.com/a")!!.id, grocery.recipeId)
            assertEquals(20_001L, grocery.plannedDay)
            assertEquals(db.groceryDao().observeItems().first().single().uid, grocery.uid)

            // Again: everything is already there.
            val again = (repo(other).import(exported.json) as BackupResult.Success).value
            assertEquals(0, again.pantryAdded)
            assertEquals(0, again.groceriesAdded)
        } finally {
            other.close()
        }
    }

    @Test
    fun reimportingNeverRewritesAddedAt() = runBlocking {
        val id = db.recipeDao().upsert(recipe("https://example.com/a"), 50)
        val favorites = db.listDao().observeLists(ListDao.NO_RECIPE).first().single { it.isFavorites }.id
        db.listDao().addToList(RecipeListCrossRef(id, favorites, 7))
        val json = (repo(db).export() as BackupResult.Success<ExportedBackup>).value.json
            .replace("\"addedAt\": 7", "\"addedAt\": 999")

        repo(db).import(json)

        assertEquals(7L, db.recipeDao().crossRefsFor(id).single().addedAt)
    }

    @Test
    fun aFailedImportWritesNothing() = runBlocking {
        val other = open()
        try {
            db.recipeDao().upsert(recipe("https://example.com/a"), 50)
            db.listDao().create("Weeknight", ListDao.NO_RECIPE, now = 1)
            val json = (repo(db).export() as BackupResult.Success<ExportedBackup>).value.json
            // Refuse the first list insert, which comes after the recipe inserts.
            other.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER no_lists BEFORE INSERT ON lists BEGIN SELECT RAISE(ABORT, 'no'); END"
            )

            assertEquals(BackupResult.Failure(BackupError.SaveFailed), repo(other).import(json))
            assertEquals(null, other.recipeDao().findByUrl("https://example.com/a"))
        } finally {
            other.close()
        }
    }
}
