package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.DefaultBackupRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.backup.BackupPackage
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.StepPhotos
import com.example.recipeclipper.data.toDomain
import com.example.recipeclipper.data.toEntity
import com.example.recipeclipper.fake.FakeLibraryPolicy
import com.example.recipeclipper.fake.FakePhotoStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step photos (#433) over real Room: stored as JSON beside the steps, refreshed by a re-share as
 * content, kept by the user's version and carried through an edit, and through an export and import.
 */
@RunWith(AndroidJUnit4::class)
class RecipeStepPhotosDbTest {

    private fun open() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
        .addCallback(RecipeDatabase.SeedBuiltInLists).allowMainThreadQueries().build()

    private val log = ErrorLog { _, e -> throw e }
    private val db = open()
    private val other = open()

    @After fun close() {
        db.close()
        other.close()
    }

    private val a = "https://img.example/a.jpg"
    private val b = "https://img.example/b.jpg"
    private val c = "https://img.example/c.jpg"

    private fun recipe(photos: List<List<String>>, steps: List<String> = listOf("Mix (1).", "Bake (2).")) = RecipeEntity(
        sourceUrl = "https://www.cookist.it/torta", title = "Torta", imageUrl = null, ingredients = listOf("1 uovo"),
        instructions = steps, prepTime = null, cookTime = null, totalTime = null, servings = null, sourceType = "BLOG",
        lastViewedAt = 1, stepPhotos = StepPhotos.encode(photos)
    )

    private suspend fun photosOf(id: Long) = db.recipeDao().get(id)!!.toDomain().stepPhotos

    @Test fun storedAndReadBackLinedUpWithTheSteps_andNoneIsNull() = runBlocking {
        val id = db.recipeDao().upsert(recipe(listOf(listOf(a), listOf(b, c))), LibraryLimit.Unlimited)
        assertEquals(listOf(listOf(a), listOf(b, c)), photosOf(id))
        val plain = db.recipeDao().upsert(recipe(emptyList()).copy(sourceUrl = "https://x/plain"), LibraryLimit.Unlimited)
        assertNull(db.recipeDao().get(plain)!!.stepPhotos)
        assertEquals(emptyList<List<String>>(), photosOf(plain))
    }

    @Test fun aReShareRefreshesThemWithTheContent() = runBlocking {
        val id = db.recipeDao().upsert(recipe(listOf(listOf(a), listOf(b))), LibraryLimit.Unlimited)
        db.recipeDao().upsert(recipe(listOf(listOf(c), emptyList())), LibraryLimit.Unlimited)
        assertEquals(listOf(listOf(c), emptyList()), photosOf(id))
        // A site that stopped giving them: gone with the old content.
        db.recipeDao().upsert(recipe(emptyList()), LibraryLimit.Unlimited)
        assertEquals(emptyList<List<String>>(), photosOf(id))
    }

    @Test fun theUsersVersionKeepsWhatItHas_andAnEditCarriesThemWithTheirSteps() = runBlocking {
        val id = db.recipeDao().upsert(recipe(listOf(listOf(a), listOf(b))), LibraryLimit.Unlimited)
        val stored = db.recipeDao().get(id)!!.toDomain()
        // The cook reorders the steps and rewords one.
        val edited = RecipeDraft.of(stored).copy(instructionsText = "Bake it (2).\nMix (1).").applyTo(stored)
        assertTrue(db.recipeDao().saveEdit(id, edited.toEntity(viewedAt = 1), "EDITED", 9))
        assertEquals(listOf(listOf(b), listOf(a)), photosOf(id))

        // A re-share of the user's version leaves it as it is.
        db.recipeDao().upsert(recipe(listOf(listOf(c), listOf(c))), LibraryLimit.Unlimited)
        assertEquals(listOf(listOf(b), listOf(a)), photosOf(id))
        // "Update from source" is the way back to the source's.
        db.recipeDao().upsert(recipe(listOf(listOf(c), emptyList())), LibraryLimit.Unlimited, replaceUsersVersion = true)
        assertEquals(listOf(listOf(c), emptyList()), photosOf(id))
    }

    @Test fun withTipsTooAReShareRefreshesBothAndAnExportCarriesBoth() = runBlocking {
        val id = db.recipeDao().upsert(recipe(listOf(listOf(a), listOf(b))).copy(tips = listOf("Old tip.")), LibraryLimit.Unlimited)
        db.recipeDao().upsert(recipe(listOf(listOf(c), emptyList())).copy(tips = listOf("New tip.")), LibraryLimit.Unlimited)
        val refreshed = db.recipeDao().get(id)!!.toDomain()
        assertEquals(listOf(listOf(c), emptyList()), refreshed.stepPhotos)
        assertEquals(listOf("New tip."), refreshed.tips)

        val exported = (DefaultBackupRepository(db.backupDao(), { 9 }, log, photos = FakePhotoStore()).export() as BackupResult.Success).value
        val into = DefaultBackupRepository(other.backupDao(), { 9 }, log, FakeLibraryPolicy(LibraryLimit.Unlimited), FakePhotoStore())
        assertEquals(1, (into.import(BackupPackage(exported.json)) as BackupResult.Success).value.recipesAdded)
        val copy = other.recipeDao().findByUrl("https://www.cookist.it/torta")!!.toDomain()
        assertEquals(listOf(listOf(c), emptyList()), copy.stepPhotos)
        assertEquals(listOf("New tip."), copy.tips)
    }

    @Test fun anExportCarriesThemAndAnImportBringsThemBack() = runBlocking {
        db.recipeDao().upsert(recipe(listOf(listOf(a), listOf(b, c))), LibraryLimit.Unlimited)
        db.recipeDao().upsert(recipe(emptyList()).copy(sourceUrl = "https://x/plain"), LibraryLimit.Unlimited)

        val exported = (DefaultBackupRepository(db.backupDao(), { 9 }, log, photos = FakePhotoStore()).export() as BackupResult.Success).value
        val recipes = org.json.JSONObject(exported.json).getJSONArray("recipes")
        val withKey = (0 until recipes.length()).map { recipes.getJSONObject(it).has("stepPhotos") }
        assertEquals(listOf(true, false), withKey.sortedDescending())

        val into = DefaultBackupRepository(other.backupDao(), { 9 }, log, FakeLibraryPolicy(LibraryLimit.Unlimited), FakePhotoStore())
        assertEquals(2, (into.import(BackupPackage(exported.json)) as BackupResult.Success).value.recipesAdded)
        assertEquals(listOf(listOf(a), listOf(b, c)), other.recipeDao().findByUrl("https://www.cookist.it/torta")!!.toDomain().stepPhotos)
        assertFalse(other.recipeDao().findByUrl("https://x/plain")!!.stepPhotos != null)
    }
}
