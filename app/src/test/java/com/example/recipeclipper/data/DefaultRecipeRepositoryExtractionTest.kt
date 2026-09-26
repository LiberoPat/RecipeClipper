package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.PageSelection
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.BlogRecipeSource
import com.example.recipeclipper.data.remote.FetchedPage
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakePageRecipeExtractor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recipe picked from the page's text when the page has no recipe data (#103), over a real
 * page (the shared fixture, through the real parsers), a fake model and an in-memory DAO.
 */
class DefaultRecipeRepositoryExtractionTest {

    private val url = "https://blog.example/banana-bread"
    private val html = javaClass.getResourceAsStream("/pages/blog-no-recipe-data.html")!!
        .bufferedReader().use { it.readText() }

    private object NoLog : ErrorLog {
        override fun error(message: String, cause: Throwable) = Unit
    }

    /** The fixture page as fetched: no recipe data, so NoRecipeFound with its text; [next] once set. */
    private inner class PageSource : RecipeSource {
        var next: ParseResult? = null
        override suspend fun fetch(url: String) = fetchPage(url).result
        override suspend fun fetchPage(url: String): FetchedPage =
            next?.let { FetchedPage(it) } ?: BlogRecipeSource.parsePage(html, url)
    }

    private val picks = PageSelection(
        name = "Grandma's Banana Bread",
        ingredients = listOf("3 very ripe bananas, mashed", "1/3 cup melted butter", "1 1/2 cups all-purpose flour"),
        steps = listOf("Mix in the flour.", "Bake for 55 to 65 minutes, until a tester comes out clean."),
        yield = "10 slices", prepTime = "15 minutes", cookTime = "1 hour"
    )

    private val source = PageSource()
    private val dao = InMemoryRecipeDao()
    private val model = FakePageRecipeExtractor(picks)
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
        .apply { set(Flag.LLM_EXTRACTION, true) }
    private val repository = DefaultRecipeRepository(source, dao, Clock { 1L }, NoLog, extractor = model, flags = flags)

    private suspend fun import(): ParseResult = repository.importFromUrl(url)

    @Test fun `what the model picks from the page is saved, in the page's own words`() = runTest {
        val recipe = (import() as ParseResult.Success).recipe
        assertEquals("Grandma’s Banana Bread", recipe.name)
        assertEquals(listOf("3 very ripe bananas, mashed", "⅓ cup melted butter", "1 ½ cups all-purpose flour"), recipe.ingredients)
        assertEquals(picks.steps, recipe.instructions)
        assertEquals("10 slices", recipe.yield)
        assertEquals("15m", recipe.prepTime)
        assertEquals("1h", recipe.cookTime)
        assertEquals("https://blog.example/wp-content/uploads/banana-bread.jpg", recipe.image)
        assertEquals("en-us", recipe.language)
        assertEquals(ContentOrigin.EXTRACTED, recipe.origin)
        assertEquals(listOf("EXTRACTED"), dao.rows.values.map { it.contentOrigin })
        assertTrue(model.asked.single().contains("Ingredients\n3 very ripe bananas, mashed"))
    }

    @Test fun `it asks in two parts, the ingredients and then the named recipe's steps`() = runTest {
        val recipe = (import() as ParseResult.Success).recipe
        assertEquals(1, model.asked.size)
        assertEquals(listOf("Grandma's Banana Bread"), model.askedSteps)
        assertEquals(picks.steps, recipe.instructions)
    }

    @Test fun `no name asks nothing more, and steps it can't answer are no recipe, never half of one`() = runTest {
        model.picks = picks.copy(name = null)
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), import())
        assertTrue(model.askedSteps.isEmpty())
        model.picks = picks
        model.answersSteps = false
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), import())
        assertEquals(1, model.askedSteps.size)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `lines the model made up are dropped, the rest kept`() = runTest {
        model.picks = picks.copy(
            ingredients = picks.ingredients + "2 cups chocolate chips" + "4 very ripe bananas, mashed",
            steps = listOf("Fold in the chocolate chips.", "Mix in the flour.")
        )
        val recipe = (import() as ParseResult.Success).recipe
        assertEquals(listOf("3 very ripe bananas, mashed", "⅓ cup melted butter", "1 ½ cups all-purpose flour"), recipe.ingredients)
        assertEquals(listOf("Mix in the flour."), recipe.instructions)
    }

    @Test fun `nothing verifiable is NoRecipeFound, as before`() = runTest {
        model.picks = picks.copy(name = "Easy Banana Loaf")
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), import())
        model.picks = picks.copy(ingredients = listOf("2 cups chocolate chips"), steps = listOf("Stir well."))
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), import())
        model.picks = null
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), import())
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `an unsupported language or phone, or the flag off, never asks the model`() = runTest {
        model.languages = setOf("de")
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), import())
        model.languages = setOf("en")
        flags.set(Flag.LLM_EXTRACTION, false)
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), import())
        assertTrue(model.asked.isEmpty())
    }

    @Test fun `only a page that loaded with no recipe data is read`() = runTest {
        source.next = ParseResult.Error(ParseError.Blocked(403))
        assertEquals(ParseResult.Error(ParseError.Blocked(403)), import())
        assertTrue(model.asked.isEmpty())
    }

    @Test fun `an extracted recipe is the source's, so a re-share refreshes it`() = runTest {
        import()
        val parsed = Recipe(
            name = "Banana Bread", image = null, ingredients = listOf("3 bananas"), instructions = listOf("Bake."),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = url
        )
        source.next = ParseResult.Success(parsed)
        val recipe = (import() as ParseResult.Success).recipe
        assertEquals("Banana Bread", recipe.name)
        assertEquals(ContentOrigin.PARSED, recipe.origin)
        assertEquals(1, dao.rows.size)
    }
}
