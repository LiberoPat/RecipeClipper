package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.DefaultBackupRepository
import com.example.recipeclipper.data.DefaultShareFileRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.backup.BackupPackage
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.backup.ShareChoice
import com.example.recipeclipper.data.backup.decodeOrFail
import com.example.recipeclipper.data.backup.fixture
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.fake.FakeLibraryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One cookbook (#345): a backup or a sent file can't take the free app past its limit either.
 * The import stops at the limit, says how many weren't added, and removes nothing.
 */
@RunWith(AndroidJUnit4::class)
class OneCookbookImportTest {

    private fun open() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
        .addCallback(RecipeDatabase.SeedBuiltInLists).allowMainThreadQueries().build()

    private val log = ErrorLog { _, e -> throw e }
    private val from = open()
    private val to = open()
    private val capped = FakeLibraryPolicy(LibraryLimit.Capped(50))

    @After fun close() {
        from.close()
        to.close()
    }

    private fun recipe(url: String, viewed: Long = 1) = RecipeEntity(
        sourceUrl = url, title = "R $url", imageUrl = null, ingredients = listOf("1 egg"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, servings = null, sourceType = "BLOG", lastViewedAt = viewed
    )

    private suspend fun fill(db: RecipeDatabase, n: Int, prefix: String) = repeat(n) {
        db.recipeDao().upsert(recipe("https://$prefix.com/$it", it.toLong()), LibraryLimit.Unlimited)
    }

    private fun success(result: BackupResult<ImportSummary>): ImportSummary =
        (result as? BackupResult.Success)?.value ?: throw AssertionError("failed: $result")

    private fun importer(db: RecipeDatabase) = DefaultBackupRepository(db.backupDao(), { 9 }, log, capped)

    @Test fun `a backup stops at the limit and says how many were left out`() = runBlocking {
        fill(from, 10, "from")
        fill(to, 47, "here")
        val json = (DefaultBackupRepository(from.backupDao(), { 9 }, log).export() as BackupResult.Success).value.json

        val summary = success(importer(to).import(BackupPackage(json)))

        assertEquals(3, summary.recipesAdded)
        assertEquals(7, summary.recipesSkipped)
        assertEquals(50, summary.freeLimit)
        assertEquals(50, to.recipeDao().count())
        // Importing again adds nothing: the three are here, the rest still don't fit.
        assertEquals(0, success(importer(to).import(BackupPackage(json))).recipesAdded)
        assertEquals(50, to.recipeDao().count())
    }

    @Test fun `a listed recipe is limited too, and its list stays out with it`() = runBlocking {
        val id = from.recipeDao().upsert(recipe("https://from.com/listed"), LibraryLimit.Unlimited)
        from.openHelper.writableDatabase.execSQL(
            "INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (?, 1, 1)", arrayOf<Any>(id)
        )
        fill(to, 50, "here")
        val json = (DefaultBackupRepository(from.backupDao(), { 9 }, log).export() as BackupResult.Success).value.json

        val summary = success(importer(to).import(BackupPackage(json)))

        assertEquals(0, summary.recipesAdded)
        assertEquals(1, summary.recipesSkipped)
        assertNull(to.recipeDao().findByUrl("https://from.com/listed"))
        assertEquals(50, to.recipeDao().count())
    }

    @Test fun `the listed ones take the free places first`() = runBlocking {
        fill(from, 5, "from")
        val listed = from.recipeDao().findByUrl("https://from.com/0")!!.id // the oldest viewed
        from.openHelper.writableDatabase.execSQL(
            "INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (?, 1, 1)", arrayOf<Any>(listed)
        )
        fill(to, 49, "here")
        val json = (DefaultBackupRepository(from.backupDao(), { 9 }, log).export() as BackupResult.Success).value.json

        success(importer(to).import(BackupPackage(json)))

        assertNotNull(to.recipeDao().findByUrl("https://from.com/0"))
        assertEquals(50, to.recipeDao().count())
    }

    @Test fun `a sent file stops at the limit too`() = runBlocking {
        fill(to, 50, "here")
        val file = decodeOrFail(fixture("share-v1.recipeclipper"))
        val repo = DefaultShareFileRepository(to, { 9 }, log, capped)

        val summary = success(repo.receive(file, ShareChoice(setOf("r-chicken"), emptySet())))

        assertEquals(0, summary.recipesAdded)
        assertEquals(1, summary.recipesSkipped)
        assertEquals(50, summary.freeLimit)
        assertNull(to.recipeDao().findByUrl("https://example.com/sheet-pan-chicken"))
        assertEquals(50, to.recipeDao().count())
    }

    @Test fun `with room, a sent file comes in as before`() = runBlocking {
        fill(to, 49, "here")
        val file = decodeOrFail(fixture("share-v1.recipeclipper"))
        val repo = DefaultShareFileRepository(to, { 9 }, log, capped)

        assertEquals(1, success(repo.receive(file, ShareChoice(setOf("r-chicken"), emptySet()))).recipesAdded)
        assertNotNull(to.recipeDao().findByUrl("https://example.com/sheet-pan-chicken"))
    }
}
