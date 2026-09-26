package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultCookedPhotoRepository
import com.example.recipeclipper.data.DefaultRecipeRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.PhotoStore
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakePhotoStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "I made this" (#116) against real SQLite: photos newest cook first, edits, a recipe with
 * photos kept from the cull and the free tier's removal, the cascade on delete with Undo, and
 * files going only when a delete stands or nothing names them.
 */
@RunWith(AndroidJUnit4::class)
class CookedPhotoDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
        .allowMainThreadQueries().build()
    private val log = ErrorLog { _, e -> throw e }
    private var now = 1_000_000_000L
    private val store = FakePhotoStore()
    private val photos = DefaultCookedPhotoRepository(db.cookedPhotoDao(), store, Clock { now }, log)
    private val source = object : RecipeSource { override suspend fun fetch(url: String) = ParseResult.Error(com.example.recipeclipper.data.model.ParseError.NoRecipeFound) }
    private val recipes = DefaultRecipeRepository(source, db.recipeDao(), Clock { now }, log, photos = store)

    @After fun close() = db.close()

    private fun recipe(n: Int) = RecipeEntity(
        sourceUrl = "https://example.com/$n", title = "R$n", imageUrl = null, ingredients = listOf("1 egg"),
        instructions = listOf("Cook."), prepTime = null, cookTime = null, totalTime = null, servings = null,
        sourceType = "BLOG", lastViewedAt = n.toLong()
    )

    private suspend fun insert(n: Int) = db.recipeDao().upsert(recipe(n), LibraryLimit.Unlimited)

    @Test fun addedPhotosAreCookedTodayNewestFirstAndEditable() = runBlocking {
        val id = insert(1)
        val first = photos.add(id, listOf("one")).single()
        now += 1_000
        val second = photos.add(id, listOf("two", "bad")).single()
        assertEquals(PlanDays.today(now), first.day)
        assertEquals(listOf(second.id, first.id), photos.observe(id).first().map { it.id })
        assertEquals("one", store.bytes(first.fileName))

        photos.edit(first.id, first.day + 1, "  Less sugar  ")
        val edited = photos.observe(id).first().first()
        assertEquals(first.id, edited.id)
        assertEquals("Less sugar", edited.note)
        assertEquals(now, edited.updatedAt)
    }

    @Test fun aRecipeWithPhotosIsNeverCulledOrRemovedForRoom() = runBlocking {
        val cooked = insert(1)
        photos.add(cooked, listOf("pic"))
        insert(2)
        insert(3)
        assertEquals(2L, db.recipeDao().oldestCullable())
        db.recipeDao().cullHistory(keep = 0)
        assertNotNull(db.recipeDao().get(cooked))
        assertNull(db.recipeDao().get(2))
        // The Recipes screen's Recently cooked sort reads this.
        assertEquals(PlanDays.today(now), db.recipeDao().observeHistory("").first().single().lastCookedDay)
    }

    @Test fun deletingARecipeTakesItsPhotosAndUndoBringsThemBack() = runBlocking {
        val id = insert(1)
        val photo = photos.add(id, listOf("pic")).single()
        val deleted = recipes.delete(id)!!
        assertTrue(photos.observe(id).first().isEmpty())
        assertTrue(store.files().containsKey(photo.fileName))

        recipes.restore(deleted)
        assertEquals(listOf(photo.uid), photos.observe(id).first().map { it.uid })

        recipes.forget(recipes.delete(id)!!)
        assertTrue(store.files().isEmpty())
    }

    @Test fun aDeletedPhotoKeepsItsFileUntilForgotten() = runBlocking {
        val id = insert(1)
        val photo = photos.add(id, listOf("pic")).single()
        val removed = photos.delete(photo.id)!!
        photos.restore(removed)
        assertEquals(1, photos.observe(id).first().size)
        photos.forget(listOf(photos.delete(photo.id)!!))
        assertTrue(store.files().isEmpty())
    }

    @Test fun theSweepRemovesOnlyOldFilesNoPhotoNames() = runBlocking {
        val id = insert(1)
        val kept = photos.add(id, listOf("kept")).single()
        val orphan = store.importPicture("orphan")!!
        val young = store.importPicture("young")!!
        store.modified(kept.fileName, now - PhotoStore.SWEEP_GRACE_MILLIS * 2)
        store.modified(orphan, now - PhotoStore.SWEEP_GRACE_MILLIS * 2)
        store.modified(young, now)
        photos.sweep()
        assertEquals(setOf(kept.fileName, young), store.files().keys)
    }
}
