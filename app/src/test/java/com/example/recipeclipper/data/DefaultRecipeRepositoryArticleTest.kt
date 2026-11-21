package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.remote.BlogRecipeSource
import com.example.recipeclipper.data.remote.FetchedPage
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recipe written inside an article (#435), over the shared El Periódico page through the real
 * parsers and an in-memory DAO: offered for the cook to check, never saved by the import.
 */
class DefaultRecipeRepositoryArticleTest {

    private val url = "https://recetas.elperiodico.com/articulo-mejillones-79292.html"
    private val html = javaClass.getResourceAsStream("/pages/article-elperiodico-mejillones.html")!!
        .bufferedReader().use { it.readText() }

    private object NoLog : ErrorLog {
        override fun error(message: String, cause: Throwable) = Unit
    }

    private inner class PageSource : RecipeSource {
        var next: ParseResult? = null
        var fetches = 0
        override suspend fun fetch(url: String) = fetchPage(url).result
        override suspend fun fetchPage(url: String): FetchedPage {
            fetches++
            return next?.let { FetchedPage(it) } ?: BlogRecipeSource.parsePage(html, url)
        }
    }

    private val source = PageSource()
    private val dao = InMemoryRecipeDao()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
        .apply { set(Flag.ARTICLE_RECIPES, true) }
    private fun repository() = DefaultRecipeRepository(source, dao, Clock { 1L }, NoLog, flags = flags)

    @Test fun `the page's article recipe is offered, and nothing is saved`() = runTest {
        val result = repository().importFromUrl("$url?utm_source=x")
        val recipe = ((result as ParseResult.Error).error as ParseError.InArticle).recipe
        assertEquals(6, recipe.ingredients.size)
        assertEquals(6, recipe.steps.size)
        assertEquals(url, recipe.sourceUrl)
        assertEquals("es", recipe.language)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `the editor gets what the import found without fetching again`() = runTest {
        val repository = repository()
        repository.importFromUrl(url)
        val fetches = source.fetches
        assertEquals(6, repository.articleRecipe("$url?utm_source=x")?.steps?.size)
        assertEquals(fetches, source.fetches)
    }

    @Test fun `after process death the editor reads the page again`() = runTest {
        val recipe = repository().articleRecipe(url)
        assertEquals(6, recipe?.ingredients?.size)
        assertEquals(1, source.fetches)
    }

    @Test fun `a page that no longer holds one gives the editor nothing`() = runTest {
        source.next = ParseResult.Error(ParseError.NoRecipeFound)
        assertNull(repository().articleRecipe(url))
    }

    @Test fun `the flag off is No recipe found, as before`() = runTest {
        flags.set(Flag.ARTICLE_RECIPES, false)
        val repository = repository()
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), repository.importFromUrl(url))
        assertNull(repository.articleRecipe(url))
    }
}
