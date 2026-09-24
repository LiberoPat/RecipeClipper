package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.CookStateRow
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.dao.RecipeSummaryRow
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.local.entity.RecipeListCrossRef
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.SavedTimer
import com.example.recipeclipper.data.model.StepAlarm
import com.example.recipeclipper.data.remote.RecipeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.example.recipeclipper.fake.FakeRenderedPageSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
 * The single automatic retry in [DefaultRecipeRepository.importFromUrl], and the off-screen
 * browser fallback after it. Runs on the JVM over a scripted [RecipeSource], a
 * [FakeRenderedPageSource] and an in-memory [RecipeDao]: what is under test is the retry and
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

    // The same rule as RecipeDaoTest on a device, here over the in-memory DAO: cook progress
    // holds step indexes, so it survives a re-share only if the steps are unchanged; the
    // chosen servings survive either way.
    @Test fun `re-sharing keeps cook progress only while the steps are unchanged`() = runTest {
        val dao = InMemoryRecipeDao()
        val first = DefaultRecipeRepository(ScriptedSource(success), dao, Clock { 1_000L }, NoLog)
        val id = (first.importFromUrl(url) as ParseResult.Success).recipe.id
        val progress = CookProgress(active = true, doneSteps = setOf(0))
        first.setCookProgress(id, progress)
        first.setServingsTarget(id, 8)

        val sameSteps = DefaultRecipeRepository(
            ScriptedSource(ParseResult.Success(recipe(title = "Better Soup"))), dao, Clock { 2_000L }, NoLog
        ).importFromUrl(url) as ParseResult.Success
        assertEquals(progress, sameSteps.recipe.cook)
        assertEquals(8, sameSteps.recipe.servingsTarget)

        val newSteps = recipe().copy(instructions = listOf("Chop.", "Cook."))
        val changed = DefaultRecipeRepository(
            ScriptedSource(ParseResult.Success(newSteps)), dao, Clock { 3_000L }, NoLog
        ).importFromUrl(url) as ParseResult.Success
        assertEquals(CookProgress(), changed.recipe.cook)
        assertEquals(8, changed.recipe.servingsTarget)
    }

    @Test fun `running timers are the saved timers that have a deadline`() = runTest {
        val dao = InMemoryRecipeDao()
        val repository = DefaultRecipeRepository(ScriptedSource(success), dao, Clock { 1_000L }, NoLog)
        val id = (repository.importFromUrl(url) as ParseResult.Success).recipe.id
        repository.setCookProgress(
            id,
            CookProgress(
                active = true,
                timers = mapOf(
                    0 to SavedTimer(totalSeconds = 60, remainingSeconds = 60, endsAt = 61_000L),
                    1 to SavedTimer(totalSeconds = 60, remainingSeconds = 30, endsAt = null)
                )
            )
        )

        assertEquals(listOf(StepAlarm(id, "Soup", 0, 61_000L)), repository.runningTimers())

        repository.setCookProgress(id, CookProgress())
        assertEquals(null, dao.rows[id]?.cookState) // empty progress is stored as none
        assertEquals(emptyList<StepAlarm>(), repository.runningTimers())
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

    // --- The off-screen browser fallback (RenderedPageSource), after the retry ---

    /** A page as a browser would hand it back once its scripts have run. */
    private val renderedRecipePage = """
        <html><head><script type="application/ld+json">
        {"@type":"Recipe","name":"Rendered Soup","recipeIngredient":["1 leek"],"recipeInstructions":["Simmer."]}
        </script></head><body></body></html>
    """.trimIndent()
    private val renderedStoryPage = "<html><body><p>A long story, and no recipe.</p></body></html>"

    private fun repository(source: RecipeSource, dao: RecipeDao, rendered: FakeRenderedPageSource, clock: Clock) =
        DefaultRecipeRepository(source, dao, clock, NoLog, rendered)

    @Test fun `blocked twice renders once, after the retry, and saves what it finds`() = runTest {
        val source = ScriptedSource(blocked)
        val dao = InMemoryRecipeDao()
        var fetchesBeforeRender = -1
        var timeOfRender = -1L
        val rendered = FakeRenderedPageSource {
            fetchesBeforeRender = source.fetches
            timeOfRender = currentTime
            renderedRecipePage
        }

        val result = repository(source, dao, rendered, Clock { currentTime }).importFromUrl(url)

        assertEquals(listOf(url), rendered.requests)
        assertEquals(2, fetchesBeforeRender)
        assertEquals(DefaultRecipeRepository.RETRY_PAUSE_MS, timeOfRender)
        assertEquals("Rendered Soup", (result as ParseResult.Success).recipe.name)
        assertEquals(url, result.recipe.sourceUrl)
        assertEquals(listOf("Rendered Soup"), dao.rows.values.map { it.title })
    }

    @Test fun `NoRecipeFound renders once, with no retry and no pause`() = runTest {
        val source = ScriptedSource(noRecipe)
        val rendered = FakeRenderedPageSource { renderedRecipePage }

        val result = repository(source, InMemoryRecipeDao(), rendered, Clock { currentTime }).importFromUrl(url)

        assertEquals(1, source.fetches)
        assertEquals(0L, currentTime)
        assertEquals(1, rendered.requests.size)
        assertTrue(result is ParseResult.Success)
    }

    @Test fun `the tracking-free link is what gets rendered`() = runTest {
        val rendered = FakeRenderedPageSource { null }

        repository(ScriptedSource(noRecipe), InMemoryRecipeDao(), rendered, Clock { currentTime })
            .importFromUrl("$url?utm_source=share#jump")

        assertEquals(listOf(url), rendered.requests)
    }

    @Test fun `never rendered for Offline, a timeout, a network failure or a success`() = runTest {
        val retryTimedOut = ScriptedSource(blocked, timedOut) // the retry itself timed out
        for (source in listOf(
            ScriptedSource(offline),
            ScriptedSource(timedOut),
            ScriptedSource(networkBlip),
            retryTimedOut,
            ScriptedSource(success)
        )) {
            val rendered = FakeRenderedPageSource { renderedRecipePage }
            repository(source, InMemoryRecipeDao(), rendered, Clock { currentTime }).importFromUrl(url)
            assertEquals(emptyList<String>(), rendered.requests)
        }
    }

    @Test fun `a rendered page with no recipe keeps the direct fetch's cause`() = runTest {
        val dao = InMemoryRecipeDao()
        val rendered = FakeRenderedPageSource { renderedStoryPage }
        val source = ScriptedSource(blocked, ParseResult.Error(ParseError.Blocked(429)))

        val result = repository(source, dao, rendered, Clock { currentTime }).importFromUrl(url)

        assertEquals(1, rendered.requests.size)
        assertEquals(ParseResult.Error(ParseError.Blocked(429)), result)
        assertEquals(0, dao.writes)
    }

    @Test fun `a page that fails to render keeps NoRecipeFound`() = runTest {
        val rendered = FakeRenderedPageSource { null }

        val result = repository(ScriptedSource(noRecipe), InMemoryRecipeDao(), rendered, Clock { currentTime })
            .importFromUrl(url)

        assertEquals(1, rendered.requests.size)
        assertEquals(noRecipe, result)
    }

    @Test fun `a render that never settles is cut off at the cap, keeping the cause`() = runTest {
        val rendered = FakeRenderedPageSource { awaitCancellation() }

        val result = repository(ScriptedSource(noRecipe), InMemoryRecipeDao(), rendered, Clock { currentTime })
            .importFromUrl(url)

        assertEquals(noRecipe, result)
        assertEquals(DefaultRecipeRepository.RENDER_TIMEOUT_MS, currentTime)
    }

    @Test fun `still blocked after rendering, a saved link opens the saved copy`() = runTest {
        val dao = InMemoryRecipeDao()
        val savedId = (DefaultRecipeRepository(ScriptedSource(success), dao, Clock { 1_000L }, NoLog)
            .importFromUrl(url) as ParseResult.Success).recipe.id
        val rendered = FakeRenderedPageSource { renderedStoryPage }

        val result = repository(ScriptedSource(blocked), dao, rendered, Clock { 8_000L }).importFromUrl(url)

        assertEquals(1, rendered.requests.size)
        assertEquals(savedId, (result as ParseResult.Success).recipe.id)
        assertEquals("Soup", result.recipe.name)
    }

    @Test fun `cancelling during the render writes nothing`() = runTest {
        val dao = InMemoryRecipeDao()
        // A saved copy exists, so a completed import would at least touch it.
        DefaultRecipeRepository(ScriptedSource(success), dao, Clock { 1_000L }, NoLog).importFromUrl(url)
        val writesBefore = dao.writes
        var renderCancelled = false
        val rendered = FakeRenderedPageSource {
            try {
                awaitCancellation()
            } finally {
                renderCancelled = true
            }
        }

        val import = async { repository(ScriptedSource(noRecipe), dao, rendered, Clock { 9_000L }).importFromUrl(url) }
        runCurrent()
        assertEquals(1, rendered.requests.size)

        import.cancel()
        advanceUntilIdle()

        assertTrue(import.isCancelled)
        assertTrue(renderCancelled)
        assertEquals(writesBefore, dao.writes)
        assertEquals(1_000L, dao.rows.values.single().lastViewedAt)
    }
}
