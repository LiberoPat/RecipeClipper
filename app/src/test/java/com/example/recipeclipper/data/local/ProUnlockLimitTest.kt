package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultBackupRepository
import com.example.recipeclipper.data.DefaultLibraryPolicy
import com.example.recipeclipper.data.DefaultRecipeRepository
import com.example.recipeclipper.data.DefaultShareFileRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.RecipeRepository.RestoreOutcome
import com.example.recipeclipper.data.backup.BackupPackage
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.backup.ShareChoice
import com.example.recipeclipper.data.backup.decodeOrFail
import com.example.recipeclipper.data.backup.fixture
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.SourceType
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pro (#413): with `proUnlock` on the free tier's limit is gone on every way in, even with
 * `freeTier` on and nothing bought; with it off the 50 of #345 comes back exactly. Each way in
 * runs over the real [DefaultLibraryPolicy] (flags, a locked store), real SQLite and the real
 * repositories, at a library already holding 50.
 */
@RunWith(AndroidJUnit4::class)
class ProUnlockLimitTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
        .addCallback(RecipeDatabase.SeedBuiltInLists).allowMainThreadQueries().build()
    private val dao = db.recipeDao()
    private val log = ErrorLog { _, e -> throw e }

    // The free tier on and locked: before #413, the 51st recipe would not be saved.
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
        .also { it.set(Flag.FREE_TIER, true) }
    private val entitlements = FakeEntitlements()
    private val policy = DefaultLibraryPolicy(flags, entitlements)

    private val source = object : RecipeSource {
        override suspend fun fetch(url: String): ParseResult = ParseResult.Success(recipe(url, "Soup"))
    }
    private val repository = DefaultRecipeRepository(source, dao, Clock { 5_000 }, { _, _ -> }, library = policy)

    @After fun close() = db.close()

    private fun recipe(url: String, name: String) = Recipe(
        name = name, image = null, ingredients = listOf("1 leek"), instructions = listOf("Simmer."),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = url, sourceType = SourceType.BLOG
    )

    private fun entity(url: String, viewed: Long) = RecipeEntity(
        sourceUrl = url, title = "Old $url", imageUrl = null, ingredients = listOf("x"), instructions = listOf("y"),
        prepTime = null, cookTime = null, totalTime = null, servings = null, sourceType = "BLOG", lastViewedAt = viewed
    )

    private suspend fun fill(n: Int = 50) = (0 until n).map { dao.upsert(entity("https://old.com/$it", it.toLong()), LibraryLimit.Unlimited) }

    private fun proUnlock(on: Boolean) = flags.set(Flag.PRO_UNLOCK, on)

    private fun success(result: BackupResult<ImportSummary>): ImportSummary =
        (result as? BackupResult.Success)?.value ?: throw AssertionError("failed: $result")

    @Test fun `the flag is on by default, and the policy then has no limit`() = runBlocking {
        assertTrue(flags.isOn(Flag.PRO_UNLOCK))
        assertEquals(LibraryLimit.Unlimited, policy.limit)
        assertEquals(LibraryLimit.Unlimited, policy.limits.first())
        proUnlock(false)
        assertEquals(LibraryLimit.Capped(50), policy.limit)
        assertEquals(LibraryLimit.Capped(50), policy.limits.first())
    }

    @Test fun `a shared link past 50 is saved, and with the flag off it is shown unsaved`() = runBlocking {
        fill()
        assertTrue((repository.importFromUrl("https://a.com/soup") as ParseResult.Success).kept)
        assertEquals(51, dao.count())
        proUnlock(false)
        assertFalse((repository.importFromUrl("https://a.com/stew") as ParseResult.Success).kept)
        assertEquals(51, dao.count())
    }

    @Test fun `a page passed through Cloudflare's check past 50 is saved, and not with the flag off`() = runBlocking {
        fill()
        fun page(name: String) = """<html><script type="application/ld+json">
            {"@type":"Recipe","name":"$name","recipeIngredient":["1 leek"],"recipeInstructions":["Simmer."]}
            </script></html>"""
        assertTrue((repository.importPage("https://a.com/soup", page("Soup")) as ParseResult.Success).kept)
        proUnlock(false)
        assertFalse((repository.importPage("https://a.com/stew", page("Stew")) as ParseResult.Success).kept)
    }

    @Test fun `a clip past 50 is saved, and not with the flag off`() = runBlocking {
        fill()
        assertTrue((repository.saveClip(recipe("https://a.com/clip", "Clip")) as ParseResult.Success).kept)
        proUnlock(false)
        assertFalse((repository.saveClip(recipe("https://a.com/clip2", "Clip 2")) as ParseResult.Success).kept)
    }

    @Test fun `typed, scanned, photo-read and shared-text recipes past 50 are saved, and not with the flag off`() = runBlocking {
        fill()
        assertTrue(repository.addManual(RecipeDraft(name = "Toast", ingredientsText = "bread"))!!.id > 0)
        assertTrue(repository.addManual(RecipeDraft(name = "Gnocchi", ingredientsText = "500 g Kartoffeln"), language = "de")!!.id > 0)
        assertEquals(52, dao.count())
        proUnlock(false)
        assertEquals(0L, repository.addManual(RecipeDraft(name = "Pancakes", ingredientsText = "2 eggs"))!!.id)
        assertEquals(52, dao.count())
    }

    @Test fun `a backup imports everything past 50, and stops at 50 with the flag off`() = runBlocking {
        val from = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists).allowMainThreadQueries().build()
        repeat(10) { from.recipeDao().upsert(entity("https://from.com/$it", it.toLong()), LibraryLimit.Unlimited) }
        val json = (DefaultBackupRepository(from.backupDao(), { 9 }, log).export() as BackupResult.Success).value.json
        from.close()
        fill(47)
        val importer = DefaultBackupRepository(db.backupDao(), { 9 }, log, policy)

        proUnlock(false)
        val capped = success(importer.import(BackupPackage(json)))
        assertEquals(3, capped.recipesAdded)
        assertEquals(7, capped.recipesSkipped)
        assertEquals(50, dao.count())

        proUnlock(true)
        val unlimited = success(importer.import(BackupPackage(json)))
        assertEquals(7, unlimited.recipesAdded)
        assertEquals(0, unlimited.recipesSkipped)
        assertEquals(57, dao.count())
    }

    @Test fun `a sent file comes in past 50, and not with the flag off`() = runBlocking {
        fill()
        val file = decodeOrFail(fixture("share-v1.recipeclipper"))
        val repo = DefaultShareFileRepository(db, { 9 }, log, policy)

        proUnlock(false)
        assertEquals(1, success(repo.receive(file, ShareChoice(setOf("r-chicken"), emptySet()))).recipesSkipped)
        assertNull(dao.findByUrl("https://example.com/sheet-pan-chicken"))

        proUnlock(true)
        assertEquals(1, success(repo.receive(file, ShareChoice(setOf("r-chicken"), emptySet()))).recipesAdded)
        assertNotNull(dao.findByUrl("https://example.com/sheet-pan-chicken"))
    }

    @Test fun `Undo restores past 50 (no free-tier undo rule), and refuses with the flag off`() = runBlocking {
        val ids = fill()
        val gone = repository.delete(ids[0])!!
        dao.upsert(entity("https://a.com/new", 9_000), policy.limit)
        assertEquals(50, dao.count())
        assertEquals(RestoreOutcome.RESTORED, repository.restore(gone))
        assertEquals(51, dao.count())

        proUnlock(false)
        val again = repository.delete(ids[1])!!
        dao.upsert(entity("https://a.com/other", 9_001), LibraryLimit.Unlimited)
        assertEquals(51, dao.count())
        assertEquals(RestoreOutcome.LIBRARY_FULL, repository.restore(again))
        assertNull(dao.get(ids[1]))
    }

    @Test fun `with oneCookbook off, the flag leaves the old clean-up of 50, never the free 20`() = runBlocking {
        flags.set(Flag.ONE_COOKBOOK, false)
        assertEquals(LibraryLimit.History(50), policy.limit)
        proUnlock(false)
        assertEquals(LibraryLimit.Free(20), policy.limit)
    }
}
