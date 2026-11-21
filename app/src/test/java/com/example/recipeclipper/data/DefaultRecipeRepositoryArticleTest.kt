package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.BlogRecipeSource
import com.example.recipeclipper.data.remote.FetchedPage
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recipes written inside an article (#435), over the shared pages through the real parsers and an
 * in-memory DAO: offered for the cook to check, never saved by the import, each under its own link.
 * iOS: `DataArticleTests`.
 */
class DefaultRecipeRepositoryArticleTest {

    private val url = "https://recetas.elperiodico.com/articulo-mejillones-79292.html"
    private fun page(name: String) = javaClass.getResourceAsStream("/pages/$name")!!.bufferedReader().use { it.readText() }
    private val html = page("article-elperiodico-mejillones.html")

    private object NoLog : ErrorLog {
        override fun error(message: String, cause: Throwable) = Unit
    }

    private inner class PageSource : RecipeSource {
        var next: ParseResult? = null
        var page = html
        var fetches = 0
        override suspend fun fetch(url: String) = fetchPage(url).result
        override suspend fun fetchPage(url: String): FetchedPage {
            fetches++
            return next?.let { FetchedPage(it) } ?: BlogRecipeSource.parsePage(page, url)
        }
    }

    private val source = PageSource()
    private val dao = InMemoryRecipeDao()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
        .apply { set(Flag.ARTICLE_RECIPES, true) }
    private fun repository() = DefaultRecipeRepository(source, dao, Clock { 1L }, NoLog, flags = flags)

    private fun ParseResult.articles() = ((this as ParseResult.Error).error as ParseError.InArticle).recipes

    @Test fun `the page's article recipe is offered, and nothing is saved`() = runTest {
        val recipe = repository().importFromUrl("$url?utm_source=x").articles().single()
        assertEquals(6, recipe.ingredients.size)
        assertEquals(6, recipe.steps.size)
        assertEquals(url, recipe.sourceUrl)
        assertEquals("es", recipe.language)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `several are each offered under their own link, the first under the page's`() = runTest {
        source.page = page("article-two-recipes.html")
        val recipes = repository().importFromUrl(url).articles()
        assertEquals(listOf(url, "$url?pith_recipe=2"), recipes.map { it.sourceUrl })
    }

    @Test fun `the editor gets what the import found without fetching again`() = runTest {
        val repository = repository()
        repository.importFromUrl(url)
        val fetches = source.fetches
        assertEquals(6, repository.articleRecipes("$url?utm_source=x").single().steps.size)
        assertEquals(fetches, source.fetches)
    }

    @Test fun `after process death the editor reads the page again`() = runTest {
        assertEquals(6, repository().articleRecipes(url).single().ingredients.size)
        assertEquals(1, source.fetches)
    }

    @Test fun `a page that no longer holds one gives the editor nothing`() = runTest {
        source.next = ParseResult.Error(ParseError.NoRecipeFound)
        assertTrue(repository().articleRecipes(url).isEmpty())
    }

    @Test fun `the flag off is No recipe found, as before`() = runTest {
        flags.set(Flag.ARTICLE_RECIPES, false)
        val repository = repository()
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), repository.importFromUrl(url))
        assertTrue(repository.articleRecipes(url).isEmpty())
    }

    @Test fun `a saved one is found by its link, for the chooser`() = runTest {
        source.page = page("article-two-recipes.html")
        val repository = repository()
        val second = repository.importFromUrl(url).articles()[1]
        val saved = repository.saveClip(
            Recipe(
                name = second.name, image = null, ingredients = second.ingredients, instructions = second.steps,
                prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = second.sourceUrl
            )
        ) as ParseResult.Success
        assertEquals(mapOf(second.sourceUrl to saved.recipe.id), repository.savedIds(listOf(url, second.sourceUrl)))
    }

    // Past Cloudflare's check in the visible view (#220): the page it already has, read the same way.

    @Test fun `past the check, one recipe in the article is offered`() = runTest {
        assertEquals(1, repository().importPage(url, html).articles().size)
        assertEquals(0, source.fetches)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `past the check, several are offered`() = runTest {
        val recipes = repository().importPage(url, page("article-two-recipes.html")).articles()
        assertEquals(listOf("Smoky hummus", "Herb yogurt dip"), recipes.map { it.name })
        assertEquals(0, source.fetches)
    }

    @Test fun `past the check, none is No recipe found, as before`() = runTest {
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), repository().importPage(url, page("article-prose.html")))
    }
}
