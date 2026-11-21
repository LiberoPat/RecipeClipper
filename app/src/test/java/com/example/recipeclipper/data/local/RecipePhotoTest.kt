package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultBackupRepository
import com.example.recipeclipper.data.DefaultCookedPhotoRepository
import com.example.recipeclipper.data.DefaultRecipeRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.backup.BackupArchive
import com.example.recipeclipper.data.backup.BackupPackage
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ExportedBackup
import com.example.recipeclipper.data.local.entity.MealPlanEntryEntity
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.fake.FakeLibraryPolicy
import com.example.recipeclipper.fake.FakePhotoStore
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

private fun RecipeEntity.toDomainForTest() = com.example.recipeclipper.data.model.Recipe(
    name = title, image = imageUrl, ingredients = ingredients, instructions = instructions, prepTime = null,
    cookTime = null, totalTime = null, yield = null, sourceUrl = sourceUrl, photoFile = photoFile
)

/**
 * A recipe's own stored picture (#386) over real Room: shown in place of its link wherever the
 * recipe's picture shows, kept by a re-share and an edit, restored by an Undo and deleted when the
 * delete stands, swept only when nothing names it, never a cooking, and carried by an export.
 */
@RunWith(AndroidJUnit4::class)
class RecipePhotoTest {

    private fun open() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
        .addCallback(RecipeDatabase.SeedBuiltInLists).allowMainThreadQueries().build()

    private val log = ErrorLog { _, e -> throw e }
    private val db = open()
    private val other = open()
    private val store = FakePhotoStore()
    private val otherStore = FakePhotoStore()

    @After fun close() {
        db.close()
        other.close()
    }

    private fun recipe(url: String, image: String? = "https://img.example/a.jpg", photo: String? = null) = RecipeEntity(
        sourceUrl = url, title = "R", imageUrl = image, ingredients = listOf("1 egg"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, servings = null, sourceType = "BLOG", lastViewedAt = 1,
        photoFile = photo
    )

    private fun repository() = DefaultRecipeRepository(NoSource, db.recipeDao(), Clock { 5 }, log, photos = store)

    @Test fun everyListOfRecipesShowsTheStoredPhotoInPlaceOfTheLink() = runBlocking {
        val withPhoto = db.recipeDao().upsert(recipe("https://x/a", photo = "mine.jpg"), LibraryLimit.Unlimited)
        val onlyPhoto = db.recipeDao().upsert(recipe("https://x/b", image = null, photo = "only.jpg"), LibraryLimit.Unlimited)
        val onlyLink = db.recipeDao().upsert(recipe("https://x/c"), LibraryLimit.Unlimited)
        val none = db.recipeDao().upsert(recipe("https://x/d", image = null), LibraryLimit.Unlimited)

        val history = db.recipeDao().observeHistory("", RecipeDaoQueryDefaults.ANY, 0, 0, 0).first().associate { it.id to it.imageUrl }
        // The photo, with the link behind it for a file that isn't there.
        assertEquals("photo://mine.jpg#https://img.example/a.jpg", history[withPhoto])
        assertEquals("photo://only.jpg", history[onlyPhoto])
        assertEquals("https://img.example/a.jpg", history[onlyLink])
        assertNull(history[none])

        // A list's rows and its cover, and a planned meal's picture.
        val list = db.listDao().observeLists(-1).first().first { it.isFavorites }.id
        listOf(withPhoto, onlyPhoto, onlyLink, none).forEachIndexed { i, id -> db.listDao().addToList(RecipeListCrossRef(id, list, i.toLong())) }
        assertEquals(
            listOf("photo://only.jpg", "photo://mine.jpg#https://img.example/a.jpg").sorted(),
            db.listDao().observeCoverPhotos().first().filter { it.imageUrl.startsWith("photo://") }.map { it.imageUrl }.sorted()
        )
        assertEquals(
            "photo://only.jpg",
            db.listDao().observeRecipesIn(list).first().first { it.id == onlyPhoto }.imageUrl
        )
        val dinner = db.mealPlanDao().observeMealTypes().first().first().id
        db.mealPlanDao().add(MealPlanEntryEntity(day = 20000, mealTypeId = dinner, recipeId = withPhoto, servings = null, note = null, sortOrder = 0, updatedAt = 0))
        assertEquals(
            "photo://mine.jpg#https://img.example/a.jpg",
            db.mealPlanDao().observeDays(20000, 20000).first().single().imageUrl
        )
    }

    @Test fun theLinkBehindThePhotoIsTheLinkWithoutItsFragmentAndNothingForABlankOne() = runBlocking {
        val fragment = db.recipeDao().upsert(recipe("https://x/a", image = "https://img.example/a.jpg#top", photo = "p1.jpg"), LibraryLimit.Unlimited)
        val blank = db.recipeDao().upsert(recipe("https://x/b", image = "", photo = "p2.jpg"), LibraryLimit.Unlimited)
        val none = db.recipeDao().upsert(recipe("https://x/c", image = null, photo = "p3.jpg"), LibraryLimit.Unlimited)
        val plain = db.recipeDao().upsert(recipe("https://x/d", image = "https://img.example/d.jpg#top"), LibraryLimit.Unlimited)

        val images = db.recipeDao().observeHistory("", RecipeDaoQueryDefaults.ANY, 0, 0, 0).first().associate { it.id to it.imageUrl }
        assertEquals("photo://p1.jpg#https://img.example/a.jpg", images[fragment])
        assertEquals("photo://p2.jpg", images[blank])
        assertEquals("photo://p3.jpg", images[none])
        // No photo: the link as it is, as before.
        assertEquals("https://img.example/d.jpg#top", images[plain])
        // And the model the domain builds for the recipe screen is the same string.
        assertEquals(images[fragment], db.recipeDao().get(fragment)!!.toDomainForTest().displayImage)
    }

    @Test fun aReShareAndAnEditKeepThePhoto() = runBlocking {
        val id = db.recipeDao().upsert(recipe("https://x/a", photo = "mine.jpg"), LibraryLimit.Unlimited)
        // A re-share refreshes the content with a fresh parse, which has no photo of the user's.
        db.recipeDao().upsert(recipe("https://x/a").copy(title = "Refreshed"), LibraryLimit.Unlimited)
        assertEquals("Refreshed", db.recipeDao().get(id)!!.title)
        assertEquals("mine.jpg", db.recipeDao().get(id)!!.photoFile)
        // "Update from source" replaces the content, never the picture.
        db.recipeDao().upsert(recipe("https://x/a").copy(title = "From source"), LibraryLimit.Unlimited, replaceUsersVersion = true)
        assertEquals("mine.jpg", db.recipeDao().get(id)!!.photoFile)
        // And the editor's save.
        db.recipeDao().saveEdit(id, recipe("https://x/a").copy(title = "Edited"), "EDITED", 9)
        assertEquals("mine.jpg", db.recipeDao().get(id)!!.photoFile)
    }

    @Test fun anUndoBringsTheRowAndItsFileBack_andTheDeleteThatStandsRemovesTheFile() = runBlocking {
        val name = store.importBytes("a photo".toByteArray())!!
        val id = db.recipeDao().upsert(recipe("https://x/a", photo = name), LibraryLimit.Unlimited)
        val repository = repository()

        val deleted = repository.delete(id)!!
        assertNull(db.recipeDao().get(id))
        // The file stays while the Undo may still come…
        assertTrue(store.exists(name))
        repository.restore(deleted)
        assertEquals(name, db.recipeDao().get(id)!!.photoFile)
        assertTrue(store.exists(name))

        // …and goes once the delete stands.
        repository.forget(repository.delete(id)!!)
        assertFalse(store.exists(name))
    }

    @Test fun theSweepKeepsARecipesPhotoAndRemovesWhatNothingNames() = runBlocking {
        val kept = store.importBytes("kept".toByteArray())!!
        val orphan = store.importBytes("orphan".toByteArray())!!
        db.recipeDao().upsert(recipe("https://x/a", photo = kept), LibraryLimit.Unlimited)
        store.modified(kept, 0)
        store.modified(orphan, 0)

        DefaultCookedPhotoRepository(db.cookedPhotoDao(), store, Clock { 1_000_000_000L }, log).sweep()

        assertTrue(store.exists(kept))
        assertFalse(store.exists(orphan))
    }

    @Test fun aRecipesOwnPhotoIsNeverACooking() = runBlocking {
        val id = db.recipeDao().upsert(recipe("https://x/a", photo = "mine.jpg"), LibraryLimit.Unlimited)
        assertTrue(db.cookedPhotoDao().photosFor(id).isEmpty())
        val row = db.recipeDao().observeHistory("", RecipeDaoQueryDefaults.ANY, 1, 0, 0).first()
        assertTrue("the Cooked view is only cookings", row.isEmpty())
        assertNull(db.recipeDao().observeHistory("", RecipeDaoQueryDefaults.ANY, 0, 0, 0).first().single().lastCookedDay)
    }

    // ---- export and import

    private fun zip(exported: ExportedBackup): ByteArray = ByteArrayOutputStream().also {
        BackupArchive.write(it, exported.json, exported.photos.mapValues { (_, path) -> File(path) })
    }.toByteArray()

    @Test fun anExportCarriesTheRecipesOwnPhotoAndAnImportBringsItBack() = runBlocking {
        val name = store.importBytes("PICTURE".toByteArray())!!
        db.recipeDao().upsert(recipe("https://x/a", photo = name), LibraryLimit.Unlimited)
        db.recipeDao().upsert(recipe("https://x/b"), LibraryLimit.Unlimited)

        val exported = (DefaultBackupRepository(db.backupDao(), { 9 }, log, photos = store).export() as BackupResult.Success).value
        assertEquals(1, exported.photos.size)
        val root = org.json.JSONObject(exported.json).getJSONArray("recipes")
        val photoPaths = (0 until root.length()).map { root.getJSONObject(it).opt("photo") }
        assertEquals(1, photoPaths.count { it is String })

        val bytes = zip(exported)
        assertTrue(BackupArchive.isZip(bytes.copyOf(4)))
        val pkg = (BackupArchive.read(ByteArrayInputStream(bytes), Files.createTempDirectory("import").toFile(), 1_000_000) as BackupResult.Success).value
        val into = DefaultBackupRepository(other.backupDao(), { 9 }, log, FakeLibraryPolicy(LibraryLimit.Unlimited), otherStore)
        assertEquals(2, (into.import(pkg) as BackupResult.Success).value.recipesAdded)

        val copy = other.recipeDao().findByUrl("https://x/a")!!
        assertEquals("PICTURE", otherStore.bytes(copy.photoFile!!))
        assertNull(other.recipeDao().findByUrl("https://x/b")!!.photoFile)
        // Never a cooking.
        assertTrue(other.cookedPhotoDao().photosFor(copy.id).isEmpty())
    }

    @Test fun aPlainJsonExportAndASharedFileCarryNoPhoto_andAPhotoWithoutItsPictureStaysOut() = runBlocking {
        val name = store.importBytes("PICTURE".toByteArray())!!
        db.recipeDao().upsert(recipe("https://x/a", photo = name), LibraryLimit.Unlimited)
        val exported = (DefaultBackupRepository(db.backupDao(), { 9 }, log, photos = store).export() as BackupResult.Success).value
        // Only the JSON, as if the pictures had been lost on the way: the recipe comes, its photo doesn't.
        val into = DefaultBackupRepository(other.backupDao(), { 9 }, log, FakeLibraryPolicy(LibraryLimit.Unlimited), otherStore)
        assertEquals(1, (into.import(BackupPackage(exported.json)) as BackupResult.Success).value.recipesAdded)
        assertNull(other.recipeDao().findByUrl("https://x/a")!!.photoFile)
        assertTrue(otherStore.files().isEmpty())
    }

    @Test fun aRecipeHereWithNoPhotoGainsTheFilesAndOneWithAPhotoKeepsItsOwn() = runBlocking {
        val name = store.importBytes("FROM THE FILE".toByteArray())!!
        db.recipeDao().upsert(recipe("https://x/a", photo = name), LibraryLimit.Unlimited)
        db.recipeDao().upsert(recipe("https://x/b", photo = store.importBytes("OTHER".toByteArray())!!), LibraryLimit.Unlimited)
        val exported = (DefaultBackupRepository(db.backupDao(), { 9 }, log, photos = store).export() as BackupResult.Success).value
        val pkg = (BackupArchive.read(ByteArrayInputStream(zip(exported)), Files.createTempDirectory("import").toFile(), 1_000_000) as BackupResult.Success).value

        other.recipeDao().upsert(recipe("https://x/a"), LibraryLimit.Unlimited)
        val mine = otherStore.importBytes("MINE".toByteArray())!!
        other.recipeDao().upsert(recipe("https://x/b", photo = mine), LibraryLimit.Unlimited)
        val into = DefaultBackupRepository(other.backupDao(), { 9 }, log, FakeLibraryPolicy(LibraryLimit.Unlimited), otherStore)
        assertEquals(2, (into.import(pkg) as BackupResult.Success).value.recipesAlreadyHere)

        assertEquals("FROM THE FILE", otherStore.bytes(other.recipeDao().findByUrl("https://x/a")!!.photoFile!!))
        assertEquals(mine, other.recipeDao().findByUrl("https://x/b")!!.photoFile)
    }
}

private object NoSource : RecipeSource {
    override suspend fun fetch(url: String) = ParseResult.Error(ParseError.NoRecipeFound)
}

/** The query's `listed` argument for "any", spelled once. */
private object RecipeDaoQueryDefaults {
    const val ANY = com.example.recipeclipper.data.local.dao.RecipeDao.LISTED_ANY
}
