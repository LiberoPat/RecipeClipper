package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.ManualRecipe
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.remote.RecipeSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editing and typing in recipes (#29) through [DefaultRecipeRepository], over an in-memory
 * [com.example.recipeclipper.data.local.dao.RecipeDao]: the user's version is never refreshed
 * by a re-share, "Update from source" replaces it explicitly, and a manual recipe gets a
 * `manual:` link. The SQL itself is covered on a device by RecipeDaoTest.
 */
class DefaultRecipeRepositoryEditTest {

    private val url = "https://example.com/soup"

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

    private fun parsed(title: String = "Soup", ingredients: List<String> = listOf("1 onion")) =
        ParseResult.Success(
            Recipe(
                name = title, image = null, ingredients = ingredients, instructions = listOf("Cook."),
                prepTime = null, cookTime = null, totalTime = null, yield = "4", sourceUrl = url
            )
        )

    private var now = 1_000L
    private val source = StagedSource(parsed())
    private val dao = InMemoryRecipeDao()
    private val repository = DefaultRecipeRepository(source, dao, Clock { now }, NoLog)

    private suspend fun imported(): Recipe =
        (repository.importFromUrl(url) as ParseResult.Success).recipe

    private val edit = RecipeDraft(
        name = "Grandma's soup",
        ingredientsText = "1 onion\n\n  2 carrots  \n",
        instructionsText = "Chop.\nSimmer."
    )

    @Test fun `saving an edit makes a parsed recipe EDITED, stamped, with the new content`() = runTest {
        val id = imported().id
        now = 2_000L

        val saved = repository.saveEdit(id, edit)

        assertNotNull(saved)
        assertEquals(ContentOrigin.EDITED, saved!!.origin)
        assertEquals(2_000L, saved.editedAt)
        assertEquals("Grandma's soup", saved.name)
        assertEquals(listOf("1 onion", "2 carrots"), saved.ingredients)
        assertEquals(listOf("Chop.", "Simmer."), saved.instructions)
        assertEquals(url, saved.sourceUrl)
        assertNull("a blank optional field is absent", saved.yield)
    }

    @Test fun `an edit keeps ticks only if the ingredients are unchanged`() = runTest {
        val id = imported().id
        repository.setChecked(id, setOf(0))

        repository.saveEdit(id, RecipeDraft(name = "Soup", ingredientsText = "1 onion", instructionsText = "Stir."))
        assertEquals(setOf(0), dao.rows[id]!!.checkedIngredients)

        repository.saveEdit(id, RecipeDraft(name = "Soup", ingredientsText = "2 onions"))
        assertEquals(emptySet<Int>(), dao.rows[id]!!.checkedIngredients)
    }

    @Test fun `an edit that is not a recipe is refused and changes nothing`() = runTest {
        val id = imported().id

        assertNull(repository.saveEdit(id, RecipeDraft(name = "Soup")))
        assertNull(repository.saveEdit(id, RecipeDraft(ingredientsText = "1 onion")))
        assertEquals(ContentOrigin.PARSED.name, dao.rows[id]!!.contentOrigin)
    }

    @Test fun `re-sharing an edited recipe opens it without fetching and keeps the edit`() = runTest {
        val id = imported().id
        repository.saveEdit(id, edit)
        source.result = parsed(title = "Soup, updated by the site")
        now = 5_000L

        val result = repository.importFromUrl("$url?utm_source=x")

        assertEquals(1, source.fetches)
        val recipe = (result as ParseResult.Success).recipe
        assertEquals("Grandma's soup", recipe.name)
        assertEquals(5_000L, recipe.lastViewedAt)
        assertEquals(5_000L, dao.rows[id]!!.lastViewedAt)
    }

    @Test fun `re-sharing an unedited recipe still refreshes it`() = runTest {
        val id = imported().id
        source.result = parsed(title = "Soup, updated by the site")

        repository.importFromUrl(url)

        assertEquals("Soup, updated by the site", dao.rows[id]!!.title)
    }

    @Test fun `update from source replaces the edit, keeps id and note, and is PARSED again`() = runTest {
        val id = imported().id
        repository.setNotes(id, "Less salt")
        repository.saveEdit(id, edit)
        source.result = parsed(title = "Soup, updated by the site")

        val result = repository.updateFromSource(id)

        val recipe = (result as ParseResult.Success).recipe
        assertEquals(id, recipe.id)
        assertEquals("Soup, updated by the site", recipe.name)
        assertEquals(ContentOrigin.PARSED, recipe.origin)
        assertNull(recipe.editedAt)
        assertEquals("Less salt", recipe.notes)
    }

    @Test fun `a failed update from source keeps the edit`() = runTest {
        val id = imported().id
        repository.saveEdit(id, edit)
        source.result = ParseResult.Error(ParseError.Offline)

        val result = repository.updateFromSource(id)

        assertEquals(ParseResult.Error(ParseError.Offline), result)
        assertEquals("Grandma's soup", dao.rows[id]!!.title)
        assertEquals(ContentOrigin.EDITED.name, dao.rows[id]!!.contentOrigin)
    }

    @Test fun `a manual recipe gets a manual link, is MANUAL, and is never fetched`() = runTest {
        val recipe = repository.addManual(edit)

        assertNotNull(recipe)
        assertTrue(ManualRecipe.isManual(recipe!!.sourceUrl))
        assertEquals(ContentOrigin.MANUAL, recipe.origin)
        assertEquals(now, recipe.editedAt)
        assertFalse(recipe.canUpdateFromSource)
        assertEquals(ParseResult.Error(ParseError.NothingToShow), repository.updateFromSource(recipe.id))
        assertEquals(0, source.fetches)
    }

    @Test fun `two manual recipes are two rows`() = runTest {
        val a = repository.addManual(edit)!!
        val b = repository.addManual(edit)!!

        assertTrue(a.id != b.id)
        assertTrue(a.sourceUrl != b.sourceUrl)
    }

    @Test fun `editing a manual recipe keeps it MANUAL`() = runTest {
        val id = repository.addManual(edit)!!.id
        now = 9_000L

        val saved = repository.saveEdit(id, edit.copy(name = "Grandma's soup, v2"))

        assertEquals(ContentOrigin.MANUAL, saved!!.origin)
        assertEquals(9_000L, saved.editedAt)
    }
}
