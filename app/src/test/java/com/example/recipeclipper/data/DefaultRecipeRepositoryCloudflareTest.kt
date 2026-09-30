package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.FetchedPage
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeClearedHosts
import com.example.recipeclipper.fake.FakeRenderedPageSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloudflare's check (#220) in [DefaultRecipeRepository]: a challenged plain fetch goes straight
 * to the browser, a check that passes by itself gets a longer cap, one that wants a person is
 * [ParseError.HumanCheck], and a host that passed is rendered first next time. Virtual time, a
 * [FakeRenderedPageSource] standing in for the web view, and an in-memory DAO.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRecipeRepositoryCloudflareTest {

    private val url = "https://recipes.example.test/lemon-drizzle-cake/"
    private val host = "recipes.example.test"

    private object NoLog : ErrorLog {
        override fun error(message: String, cause: Throwable) = Unit
    }

    private fun page(name: String) =
        javaClass.getResourceAsStream("/cloudflare/$name.html")!!.bufferedReader().use { it.readText() }

    private val challengePage = page("interactive-turnstile")
    private val recipePage = page("recipe-with-jsd")
    private val storyPage = "<html><body><p>A long story, and no recipe.</p></body></html>"

    /** Refuses every fetch with a 403, marked as Cloudflare's check when [challenge]. */
    private class Refusing(private val challenge: Boolean, private val status: Int = 403) : RecipeSource {
        var fetches = 0
            private set

        override suspend fun fetch(url: String): ParseResult = fetchPage(url).result

        override suspend fun fetchPage(url: String): FetchedPage {
            fetches++
            return FetchedPage(ParseResult.Error(ParseError.Blocked(status)), challenge = challenge)
        }
    }

    private fun repository(
        source: RecipeSource,
        rendered: FakeRenderedPageSource,
        hosts: FakeClearedHosts = FakeClearedHosts(),
        dao: InMemoryRecipeDao = InMemoryRecipeDao(),
        clock: Clock = Clock { 0L }
    ) = DefaultRecipeRepository(source, dao, clock, NoLog, rendered, clearedHosts = hosts)

    /** Cloudflare's check for [ms], then the page it lets through. */
    private fun checkFor(ms: Long): suspend (() -> Unit) -> Unit = { onChallenge ->
        onChallenge()
        delay(ms)
    }

    @Test fun `a challenged plain fetch skips the retry and renders at once`() = runTest {
        val source = Refusing(challenge = true)
        var renderedAt = -1L
        val rendered = FakeRenderedPageSource { renderedAt = currentTime; recipePage }

        val result = repository(source, rendered).importFromUrl(url)

        assertEquals(1, source.fetches)
        assertEquals(0L, renderedAt)
        assertEquals("Lemon drizzle cake", (result as ParseResult.Success).recipe.name)
    }

    @Test fun `an ordinary block is still retried once before the render`() = runTest {
        val source = Refusing(challenge = false)
        val result = repository(source, FakeRenderedPageSource { recipePage }).importFromUrl(url)

        assertEquals(2, source.fetches)
        assertTrue(result is ParseResult.Success)
    }

    @Test fun `a check that passes by itself gets past the 20 s cap, and the host is remembered`() = runTest {
        val hosts = FakeClearedHosts()
        val dao = InMemoryRecipeDao()
        val rendered = FakeRenderedPageSource(before = checkFor(25_000)) { recipePage }

        val result = repository(Refusing(challenge = true), rendered, hosts, dao).importFromUrl(url)

        assertEquals("Lemon drizzle cake", (result as ParseResult.Success).recipe.name)
        assertEquals(listOf("Lemon drizzle cake"), dao.rows.values.map { it.title })
        assertEquals(listOf(host), hosts.recorded)
    }

    @Test fun `a check still showing at 30 s wants a person, and nothing is saved`() = runTest {
        val hosts = FakeClearedHosts()
        val dao = InMemoryRecipeDao()
        val rendered = FakeRenderedPageSource(before = { onChallenge -> onChallenge(); awaitCancellation() })

        val result = repository(Refusing(challenge = true), rendered, hosts, dao).importFromUrl(url)

        assertEquals(ParseResult.Error(ParseError.HumanCheck), result)
        assertEquals(DefaultRecipeRepository.CHALLENGE_TIMEOUT_MS, currentTime)
        assertTrue(dao.rows.isEmpty())
        assertTrue(hosts.recorded.isEmpty())
    }

    @Test fun `a render with no check is still cut off at 20 s and keeps the block`() = runTest {
        val rendered = FakeRenderedPageSource { awaitCancellation() }

        val result = repository(Refusing(challenge = true), rendered).importFromUrl(url)

        assertEquals(ParseResult.Error(ParseError.Blocked(403)), result)
        assertEquals(DefaultRecipeRepository.RENDER_TIMEOUT_MS, currentTime)
    }

    @Test fun `a browser that hands back the check itself hasn't passed it either`() = runTest {
        val result = repository(Refusing(challenge = true), FakeRenderedPageSource { challengePage }).importFromUrl(url)

        assertEquals(ParseResult.Error(ParseError.HumanCheck), result)
    }

    @Test fun `past the check with no recipe data is the page's own NoRecipeFound, not the block`() = runTest {
        val hosts = FakeClearedHosts()
        val rendered = FakeRenderedPageSource(before = checkFor(3_000)) { storyPage }

        val result = repository(Refusing(challenge = true), rendered, hosts).importFromUrl(url)

        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), result)
        assertEquals(listOf(host), hosts.recorded)
    }

    @Test fun `a page saved before opens from the saved copy instead of the check`() = runTest {
        val dao = InMemoryRecipeDao()
        val saved = Recipe(
            name = "Saved cake", image = null, ingredients = listOf("1 egg"), instructions = listOf("Bake."),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = url
        )
        repository(object : RecipeSource {
            override suspend fun fetch(url: String): ParseResult = ParseResult.Success(saved)
        }, FakeRenderedPageSource(), dao = dao).importFromUrl(url)
        val rendered = FakeRenderedPageSource(before = { onChallenge -> onChallenge(); awaitCancellation() })

        val result = repository(Refusing(challenge = true), rendered, dao = dao).importFromUrl(url)

        assertEquals("Saved cake", (result as ParseResult.Success).recipe.name)
    }

    @Test fun `a host that passed lately is rendered first, without a plain fetch`() = runTest {
        val source = Refusing(challenge = true)
        val hosts = FakeClearedHosts(host)

        val result = repository(source, FakeRenderedPageSource { recipePage }, hosts).importFromUrl(url)

        assertTrue(result is ParseResult.Success)
        assertEquals(0, source.fetches)
        assertEquals(listOf(host), hosts.recorded) // the pass is remembered afresh
    }

    @Test fun `a cleared host whose render doesn't load falls back to the plain fetch, rendering once`() = runTest {
        val source = Refusing(challenge = true)
        val rendered = FakeRenderedPageSource { null }

        val result = repository(source, rendered, FakeClearedHosts(host)).importFromUrl(url)

        assertEquals(ParseResult.Error(ParseError.Blocked(403)), result)
        assertEquals(1, source.fetches)
        assertEquals(1, rendered.requests.size)
    }

    @Test fun `a cleared host whose check came back wants a person again`() = runTest {
        val source = Refusing(challenge = true)
        val rendered = FakeRenderedPageSource(before = { onChallenge -> onChallenge(); awaitCancellation() })

        val result = repository(source, rendered, FakeClearedHosts(host)).importFromUrl(url)

        assertEquals(ParseResult.Error(ParseError.HumanCheck), result)
        assertEquals(0, source.fetches)
    }

    // --- importPage: the page the cook got past the check in the visible browser ---

    @Test fun `importPage on the check itself is HumanCheck and remembers nothing`() = runTest {
        val hosts = FakeClearedHosts()
        val result = repository(Refusing(true), FakeRenderedPageSource(), hosts).importPage(url, challengePage)

        assertEquals(ParseResult.Error(ParseError.HumanCheck), result)
        assertTrue(hosts.recorded.isEmpty())
    }

    @Test fun `importPage past the check saves the recipe like an import and remembers the host`() = runTest {
        val hosts = FakeClearedHosts()
        val dao = InMemoryRecipeDao()

        val result = repository(Refusing(true), FakeRenderedPageSource(), hosts, dao)
            .importPage("$url?utm_source=share", recipePage)

        val recipe = (result as ParseResult.Success).recipe
        assertEquals("Lemon drizzle cake", recipe.name)
        assertEquals(url, recipe.sourceUrl)
        assertEquals(listOf(url), dao.rows.values.map { it.sourceUrl })
        assertEquals(listOf(host), hosts.recorded)
    }

    @Test fun `importPage past the check with no recipe data is NoRecipeFound`() = runTest {
        val hosts = FakeClearedHosts()
        val result = repository(Refusing(true), FakeRenderedPageSource(), hosts).importPage(url, storyPage)

        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), result)
        assertEquals(listOf(host), hosts.recorded)
    }
}
