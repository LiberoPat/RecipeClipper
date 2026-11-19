package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultRecipeRepository
import com.example.recipeclipper.data.RecipeRepository.RestoreOutcome
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.SampleRecipe
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeLibraryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One cookbook (#345): Undo of a delete takes the same limit as every other way in. On the free
 * tier (locked), a restore that would take the library past 50 restores nothing, so delete, add
 * one, Undo can't grow it by a recipe each time; a burst is all or nothing. Unlocked, or with the
 * free tier off, Undo works as it always did. Real SQLite, through [DefaultRecipeRepository].
 */
@RunWith(AndroidJUnit4::class)
class OneCookbookUndoTest {

    private lateinit var db: RecipeDatabase
    private lateinit var dao: RecipeDao
    private val library = FakeLibraryPolicy(LibraryLimit.Capped(50))
    private lateinit var repository: DefaultRecipeRepository

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
            .addCallback(RecipeDatabase.SeedBuiltInLists)
            .allowMainThreadQueries()
            .build()
        dao = db.recipeDao()
        val source = object : RecipeSource {
            override suspend fun fetch(url: String): ParseResult = error("no fetching here")
        }
        repository = DefaultRecipeRepository(source, dao, Clock { 5_000 }, { _, _ -> }, library = library)
    }

    @After
    fun close() = db.close()

    private fun recipe(url: String, viewedAt: Long = 1, origin: String = "PARSED") = RecipeEntity(
        sourceUrl = url, title = "Recipe $url", imageUrl = null, ingredients = listOf("1 cup flour"),
        instructions = listOf("Bake."), prepTime = null, cookTime = null, totalTime = null,
        servings = "4", sourceType = "BLOG", lastViewedAt = viewedAt, contentOrigin = origin
    )

    private suspend fun fill(n: Int) = (0 until n).map {
        dao.upsert(recipe("https://old.com/$it", viewedAt = it.toLong()), LibraryLimit.Unlimited)
    }

    /** What a free-tier user does: adds one under the limit as it stands. */
    private suspend fun add(url: String) = dao.upsert(recipe(url, viewedAt = 9_000), library.limit)

    @Test fun `delete one at the limit, add one, then Undo does not restore (the loophole)`() = runBlocking {
        val ids = fill(50)
        val gone = repository.delete(ids[0])!!
        assertEquals(49, dao.count())
        assertNotNull(add("https://a.com/new"))
        assertEquals(50, dao.count())

        assertEquals(RestoreOutcome.LIBRARY_FULL, repository.restore(gone))

        assertEquals(50, dao.count())
        assertNull("the deleted recipe stays deleted", dao.get(ids[0]))
    }

    @Test fun `Undo straight away at the limit restores it, the library being back to its size`() = runBlocking {
        val ids = fill(50)
        val gone = repository.delete(ids[0])!!
        assertEquals(RestoreOutcome.RESTORED, repository.restore(gone))
        assertEquals(50, dao.count())
        assertNotNull(dao.get(ids[0]))
    }

    @Test fun `Undo under the limit restores, with its list membership`() = runBlocking {
        val ids = fill(10)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO recipe_list_cross_ref (recipeId, listId, addedAt) VALUES (?, 1, 1)", arrayOf<Any>(ids[3])
        )
        val gone = repository.delete(ids[3])!!
        assertEquals(RestoreOutcome.RESTORED, repository.restore(gone))
        assertEquals(10, dao.count())
        assertEquals(1, dao.crossRefsFor(ids[3]).size)
    }

    @Test fun `a burst that fits is restored whole`() = runBlocking {
        val ids = fill(50)
        val burst = ids.take(3).map { repository.delete(it)!! }
        assertEquals(47, dao.count())
        add("https://a.com/new")
        assertEquals(48, dao.count())
        // 48 + 3 = 51 would not fit; free one more place and it does: 47 + 3 = 50.
        repository.delete(dao.findByUrl("https://a.com/new")!!.id)

        assertEquals(RestoreOutcome.RESTORED, repository.restoreAll(burst))
        assertEquals(50, dao.count())
    }

    @Test fun `a burst that would not fit restores none of it`() = runBlocking {
        val ids = fill(50)
        val burst = ids.take(3).map { repository.delete(it)!! }
        add("https://a.com/one")
        add("https://a.com/two")
        assertEquals(49, dao.count())

        assertEquals(RestoreOutcome.LIBRARY_FULL, repository.restoreAll(burst))

        assertEquals("all or nothing: not even the first", 49, dao.count())
        ids.take(3).forEach { assertNull(dao.get(it)) }
    }

    @Test fun `unlocked, Undo restores past the limit as it always did`() = runBlocking {
        val ids = fill(50)
        val gone = repository.delete(ids[0])!!
        add("https://a.com/new")
        library.limit = LibraryLimit.Unlimited // the free tier off, or the unlock bought

        assertEquals(RestoreOutcome.RESTORED, repository.restore(gone))
        assertEquals(51, dao.count())
    }

    @Test fun `with oneCookbook off the old rules are untouched and Undo restores`() = runBlocking {
        library.limit = LibraryLimit.Free(20)
        val ids = fill(20)
        val gone = repository.delete(ids[0])!!
        dao.upsert(recipe("https://a.com/new", viewedAt = 9_000), LibraryLimit.Unlimited)

        assertEquals(RestoreOutcome.RESTORED, repository.restore(gone))
        assertEquals(21, dao.count())
    }

    @Test fun `the tour's sample counts toward nothing, deleted or not`() = runBlocking {
        val sample = dao.upsert(recipe(SampleRecipe.SOURCE_URL, origin = "MANUAL"), LibraryLimit.Unlimited)
        fill(49)
        val gone = repository.delete(sample)!!
        assertEquals(49, dao.count())
        add("https://a.com/new")
        assertEquals(50, dao.count())

        assertEquals("the sample takes no place, so it comes back", RestoreOutcome.RESTORED, repository.restore(gone))
        assertEquals(50, dao.count())
    }
}
