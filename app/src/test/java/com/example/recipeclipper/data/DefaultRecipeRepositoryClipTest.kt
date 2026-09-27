package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.RecipeSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saving a hand-clipped recipe (#37) through [DefaultRecipeRepository], over an in-memory DAO:
 * it is CLIPPED, keyed on the cleaned link, a re-share opens it without a fetch, and "Update
 * from source" replaces it only when the page now has recipe data.
 */
class DefaultRecipeRepositoryClipTest {

    private val url = "https://example.com/cookies"

    private object NoLog : ErrorLog {
        override fun error(message: String, cause: Throwable) = Unit
    }

    private class StagedSource(var result: ParseResult) : RecipeSource {
        var fetches = 0
            private set

        override suspend fun fetch(url: String): ParseResult {
            fetches++
            return result
        }
    }

    private var now = 1_000L
    private val source = StagedSource(ParseResult.Error(ParseError.NoRecipeFound))
    private val dao = InMemoryRecipeDao()
    private val repository = DefaultRecipeRepository(source, dao, Clock { now }, NoLog)

    private fun clip(name: String = "Oat Cookies", link: String = "$url?utm_source=x#jump") = Recipe(
        name = name, image = "https://img.example/c.jpg", ingredients = listOf("1 cup oats"),
        instructions = listOf("Bake."), prepTime = null, cookTime = null, totalTime = null,
        yield = null, sourceUrl = link
    )

    private suspend fun saved(recipe: Recipe = clip()): Recipe =
        (repository.saveClip(recipe) as ParseResult.Success).recipe

    @Test fun `a clip is saved CLIPPED under the cleaned link, as a view`() = runTest {
        val recipe = saved()

        assertEquals(ContentOrigin.CLIPPED, recipe.origin)
        assertNull(recipe.editedAt)
        assertEquals(url, recipe.sourceUrl)
        assertEquals(1_000L, recipe.lastViewedAt)
        assertEquals("Oat Cookies", dao.rows[recipe.id]!!.title)
    }

    @Test fun `re-sharing a clipped link opens the clip without a fetch`() = runTest {
        val id = saved().id
        now = 5_000L

        val result = repository.importFromUrl(url)

        assertEquals(0, source.fetches)
        val recipe = (result as ParseResult.Success).recipe
        assertEquals(id, recipe.id)
        assertEquals("Oat Cookies", recipe.name)
        assertEquals(5_000L, dao.rows[id]!!.lastViewedAt)
    }

    @Test fun `clipping the same link again replaces the clip in place, note kept`() = runTest {
        val id = saved().id
        repository.setNotes(id, "Less sugar")

        val again = saved(clip(name = "Brown Butter Oat Cookies"))

        assertEquals(id, again.id)
        assertEquals("Brown Butter Oat Cookies", again.name)
        assertEquals("Less sugar", again.notes)
        assertEquals(1, dao.rows.size)
    }

    @Test fun `a clip can be updated from source, and a failure leaves it as it is`() = runTest {
        val recipe = saved()
        assertTrue(recipe.canUpdateFromSource)

        val failed = repository.updateFromSource(recipe.id)
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), failed)
        assertEquals(ContentOrigin.CLIPPED.name, dao.rows[recipe.id]!!.contentOrigin)
        assertEquals("Oat Cookies", dao.rows[recipe.id]!!.title)

        source.result = ParseResult.Success(clip(name = "Cookies, from the site", link = url))
        val updated = (repository.updateFromSource(recipe.id) as ParseResult.Success).recipe
        assertEquals(ContentOrigin.PARSED, updated.origin)
        assertEquals("Cookies, from the site", updated.name)
    }
}
