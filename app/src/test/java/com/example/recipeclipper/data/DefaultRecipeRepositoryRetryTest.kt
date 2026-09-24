package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.dao.RecipeSummaryRow
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.RecipeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single automatic retry in [DefaultRecipeRepository.importFromUrl]. Runs on the JVM over
 * a scripted [RecipeSource] and an in-memory [RecipeDao]: what is under test is the retry and
 * fallback decisions, not SQL (the DAO's own rules are covered on a device by RecipeDaoTest).
 * The pause is a coroutine `delay`, so `runTest`'s virtual time skips it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRecipeRepositoryRetryTest {

    private val url = "https://example.com/soup"

    private object NoLog : ErrorLog {
        override fun error(message: String, cause: Throwable) = Unit
    }

    /** Answers each fetch with the next staged result, the last one repeating. */
    private class ScriptedSource(vararg results: ParseResult) : RecipeSource {
        private val script = results.toList()
        var fetches = 0
            private set

        override suspend fun fetch(url: String): ParseResult =
            script[minOf(fetches++, script.lastIndex)]
    }

    /** Just enough of a RecipeDao for the import path, recording every write. */
    private class InMemoryRecipeDao : RecipeDao() {
        val rows = mutableMapOf<Long, RecipeEntity>()
        var writes = 0
            private set
        private var nextId = 1L

        override suspend fun get(id: Long) = rows[id]
        override suspend fun findByUrl(url: String) = rows.values.firstOrNull { it.sourceUrl == url }
        override suspend fun insert(recipe: RecipeEntity): Long {
            writes++
            val id = if (recipe.id != 0L) recipe.id else nextId++
            rows[id] = recipe.copy(id = id)
            return id
        }
        override suspend fun update(recipe: RecipeEntity) {
            writes++
            rows[recipe.id] = recipe
        }
        override suspend fun touch(id: Long, now: Long) {
            writes++
            rows[id]?.let { rows[id] = it.copy(lastViewedAt = now) }
        }
        override suspend fun setChecked(id: Long, checked: Set<Int>) {
            writes++
            rows[id]?.let { rows[id] = it.copy(checkedIngredients = checked) }
        }
        override suspend fun setNotes(id: Long, notes: String?) {
            writes++
            rows[id]?.let { rows[id] = it.copy(notes = notes) }
        }
        override suspend fun delete(id: Long) {
            writes++
            rows.remove(id)
        }
        override suspend fun crossRefsFor(recipeId: Long) = emptyList<RecipeListCrossRef>()
        override suspend fun insertCrossRefs(crossRefs: List<RecipeListCrossRef>) {}
        override fun observeHistory(): Flow<List<RecipeSummaryRow>> = emptyFlow()
        override fun observeHistory(query: String): Flow<List<RecipeSummaryRow>> = emptyFlow()
        override fun observeRecent(limit: Int): Flow<List<RecipeSummaryRow>> = emptyFlow()
        override suspend fun cullHistory(keep: Int) {}
    }

    private fun recipe(title: String = "Soup") = Recipe(
        name = title, image = null, ingredients = listOf("1 onion"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, yield = "4", sourceUrl = url
    )

    private val success = ParseResult.Success(recipe())
    private val blocked = ParseResult.Error(ParseError.Blocked(403))
    private val networkBlip = ParseResult.Error(ParseError.FetchFailed("Connection reset"))
    private val offline = ParseResult.Error(ParseError.Offline)
    private val timedOut = ParseResult.Error(ParseError.FetchFailed("Read timed out", timedOut = true))
    private val noRecipe = ParseResult.Error(ParseError.NoRecipeFound)

    @Test fun `blocked then success retries once after the pause and saves`() = runTest {
        val source = ScriptedSource(blocked, success)
        val dao = InMemoryRecipeDao()
        val repository = DefaultRecipeRepository(source, dao, Clock { currentTime }, NoLog)

        val result = repository.importFromUrl(url)

        assertEquals(2, source.fetches)
        assertEquals(DefaultRecipeRepository.RETRY_PAUSE_MS, currentTime)
        assertTrue(result is ParseResult.Success)
        assertEquals("Soup", (result as ParseResult.Success).recipe.name)
        assertEquals(listOf("Soup"), dao.rows.values.map { it.title })
    }

    @Test fun `blocked twice gives Blocked after exactly two fetches`() = runTest {
        val source = ScriptedSource(blocked, ParseResult.Error(ParseError.Blocked(429)))
        val dao = InMemoryRecipeDao()
        val repository = DefaultRecipeRepository(source, dao, Clock { currentTime }, NoLog)

        val result = repository.importFromUrl(url)

        assertEquals(2, source.fetches)
        assertEquals(ParseResult.Error(ParseError.Blocked(429)), result)
        assertEquals(0, dao.writes)
    }

    @Test fun `network failure then success saves the recipe`() = runTest {
        val source = ScriptedSource(networkBlip, success)
        val dao = InMemoryRecipeDao()
        val repository = DefaultRecipeRepository(source, dao, Clock { currentTime }, NoLog)

        val result = repository.importFromUrl(url)

        assertEquals(2, source.fetches)
        assertTrue(result is ParseResult.Success)
        assertEquals(1, dao.rows.size)
    }

    @Test fun `Offline fails at once, with one fetch and no pause`() = runTest {
        val source = ScriptedSource(offline, success)
        val dao = InMemoryRecipeDao()
        val repository = DefaultRecipeRepository(source, dao, Clock { currentTime }, NoLog)

        val result = repository.importFromUrl(url)

        assertEquals(1, source.fetches)
        assertEquals(0L, currentTime)
        assertEquals(offline, result)
        assertEquals(0, dao.writes)
    }

    @Test fun `a timeout is not retried, so a dead connection costs one timeout`() = runTest {
        val source = ScriptedSource(timedOut, success)
        val repository = DefaultRecipeRepository(source, InMemoryRecipeDao(), Clock { currentTime }, NoLog)

        val result = repository.importFromUrl(url)

        assertEquals(1, source.fetches)
        assertEquals(0L, currentTime)
        assertEquals(timedOut, result)
    }

    @Test fun `offline for a saved link opens the saved copy`() = runTest {
        val dao = InMemoryRecipeDao()
        val savedId = (DefaultRecipeRepository(ScriptedSource(success), dao, Clock { 1_000L }, NoLog)
            .importFromUrl(url) as ParseResult.Success).recipe.id

        val source = ScriptedSource(offline)
        val result = DefaultRecipeRepository(source, dao, Clock { 5_000L }, NoLog).importFromUrl(url)

        assertEquals(1, source.fetches)
        assertEquals(savedId, (result as ParseResult.Success).recipe.id)
        assertEquals(5_000L, dao.rows[savedId]?.lastViewedAt)
    }

    // Not a retry rule, but this is the JVM suite that runs the real upsert: a re-share
    // refreshes the content from the source and must leave the user's note alone.
    @Test fun `re-sharing a link keeps its note and refreshes the content`() = runTest {
        val dao = InMemoryRecipeDao()
        val first = DefaultRecipeRepository(ScriptedSource(success), dao, Clock { 1_000L }, NoLog)
        val id = (first.importFromUrl(url) as ParseResult.Success).recipe.id
        first.setNotes(id, "Used half the sugar")

        val refreshed = ParseResult.Success(recipe(title = "Better Soup"))
        val result = DefaultRecipeRepository(ScriptedSource(refreshed), dao, Clock { 2_000L }, NoLog)
            .importFromUrl(url) as ParseResult.Success

        assertEquals(id, result.recipe.id)
        assertEquals("Better Soup", result.recipe.name)
        assertEquals("Used half the sugar", result.recipe.notes)
    }

    @Test fun `a blank note is stored as no note`() = runTest {
        val dao = InMemoryRecipeDao()
        val repository = DefaultRecipeRepository(ScriptedSource(success), dao, Clock { 1_000L }, NoLog)
        val id = (repository.importFromUrl(url) as ParseResult.Success).recipe.id

        repository.setNotes(id, "Needs 10 more minutes")
        assertEquals("Needs 10 more minutes", dao.rows[id]?.notes)
        repository.setNotes(id, "  \n ")
        assertEquals(null, dao.rows[id]?.notes)
    }

    @Test fun `NoRecipeFound is fetched once and never retried`() = runTest {
        val source = ScriptedSource(noRecipe, success)
        val dao = InMemoryRecipeDao()
        val repository = DefaultRecipeRepository(source, dao, Clock { currentTime }, NoLog)

        val result = repository.importFromUrl(url)

        assertEquals(1, source.fetches)
        assertEquals(0L, currentTime) // no pause either
        assertEquals(noRecipe, result)
        assertEquals(0, dao.writes)
    }

    @Test fun `a success first time is fetched once with no pause`() = runTest {
        val source = ScriptedSource(success)
        val repository = DefaultRecipeRepository(source, InMemoryRecipeDao(), Clock { currentTime }, NoLog)

        repository.importFromUrl(url)

        assertEquals(1, source.fetches)
        assertEquals(0L, currentTime)
    }

    @Test fun `blocked twice for a saved link falls back to the cached copy`() = runTest {
        val dao = InMemoryRecipeDao()
        val saved = DefaultRecipeRepository(ScriptedSource(success), dao, Clock { 1_000L }, NoLog)
        val savedId = (saved.importFromUrl(url) as ParseResult.Success).recipe.id

        val source = ScriptedSource(blocked)
        val repository = DefaultRecipeRepository(source, dao, Clock { 7_000L + currentTime }, NoLog)
        val result = repository.importFromUrl(url)

        assertEquals(2, source.fetches)
        assertTrue(result is ParseResult.Success)
        val recipe = (result as ParseResult.Success).recipe
        assertEquals(savedId, recipe.id)
        assertEquals("Soup", recipe.name)
        assertEquals(7_000L + DefaultRecipeRepository.RETRY_PAUSE_MS, dao.rows[savedId]?.lastViewedAt)
    }

    @Test fun `cancelling during the pause writes nothing`() = runTest {
        val dao = InMemoryRecipeDao()
        // A saved copy exists, so a completed import would at least touch it.
        DefaultRecipeRepository(ScriptedSource(success), dao, Clock { 1_000L }, NoLog).importFromUrl(url)
        val writesBefore = dao.writes

        val source = ScriptedSource(blocked, success)
        val repository = DefaultRecipeRepository(source, dao, Clock { 9_000L }, NoLog)
        val import = async { repository.importFromUrl(url) }
        runCurrent()
        advanceTimeBy(DefaultRecipeRepository.RETRY_PAUSE_MS / 2)
        assertEquals(1, source.fetches)

        import.cancel()
        advanceUntilIdle()

        assertTrue(import.isCancelled)
        assertEquals(1, source.fetches)
        assertEquals(writesBefore, dao.writes)
        assertEquals(1_000L, dao.rows.values.single().lastViewedAt)
    }
}
