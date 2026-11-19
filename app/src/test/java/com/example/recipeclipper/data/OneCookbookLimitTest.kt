package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.SourceType
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeLibraryPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One cookbook (#345): the free tier's limit of 50 holds on every way a recipe comes in, through
 * [DefaultRecipeRepository]. At the limit a new recipe is shown but not saved, and nothing old is
 * ever removed to make room; a recipe already saved still opens and refreshes. The SQL's own
 * rules are `OneCookbookDaoTest`'s, the imports' are `OneCookbookImportTest`'s.
 */
class OneCookbookLimitTest {

    private val dao = InMemoryRecipeDao()
    private val library = FakeLibraryPolicy(LibraryLimit.Capped(50))
    private var fetched: Recipe = recipe("https://a.com/soup", "Soup")
    private val source = object : RecipeSource {
        override suspend fun fetch(url: String): ParseResult = ParseResult.Success(fetched)
    }
    private val repository = DefaultRecipeRepository(source, dao, Clock { 5_000 }, { _, _ -> }, library = library)

    private fun recipe(url: String, name: String, type: SourceType = SourceType.BLOG) = Recipe(
        name = name, image = null, ingredients = listOf("1 leek"), instructions = listOf("Simmer."),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = url, sourceType = type
    )

    /** Fifty recipes: parsed ones nobody listed, the very kind the old clean-up would have removed. */
    private suspend fun fill() = repeat(50) { n ->
        dao.upsert(
            RecipeEntity(
                sourceUrl = "https://old.com/$n", title = "Old $n", imageUrl = null, ingredients = listOf("x"),
                instructions = listOf("y"), prepTime = null, cookTime = null, totalTime = null, servings = null,
                sourceType = "BLOG", lastViewedAt = n.toLong()
            ),
            LibraryLimit.Unlimited
        )
    }

    private fun assertNothingWasRemoved() {
        assertEquals(50, dao.rows.size)
        assertTrue((0 until 50).all { n -> dao.rows.values.any { it.sourceUrl == "https://old.com/$n" } })
    }

    @Test fun `a shared link at the limit is shown, not saved, and nothing is removed`() = runTest {
        fill()
        val result = repository.importFromUrl("https://a.com/soup") as ParseResult.Success
        assertFalse(result.kept)
        assertEquals(0L, result.recipe.id)
        assertEquals("Soup", result.recipe.name)
        assertNothingWasRemoved()
    }

    @Test fun `a Reddit post at the limit is shown, not saved`() = runTest {
        fill()
        fetched = recipe("https://reddit.com/r/Cooking/comments/abc", "Post", SourceType.REDDIT)
        val result = repository.importFromUrl("https://reddit.com/r/Cooking/comments/abc") as ParseResult.Success
        assertFalse(result.kept)
        assertNothingWasRemoved()
    }

    @Test fun `a page passed through Cloudflare's check at the limit is not saved`() = runTest {
        fill()
        val html = """<html><script type="application/ld+json">
            {"@type":"Recipe","name":"Soup","recipeIngredient":["1 leek"],"recipeInstructions":["Simmer."]}
            </script></html>"""
        val result = repository.importPage("https://a.com/soup", html) as ParseResult.Success
        assertFalse(result.kept)
        assertNothingWasRemoved()
    }

    @Test fun `a clip at the limit is not saved`() = runTest {
        fill()
        val result = repository.saveClip(recipe("https://a.com/clip", "Clip")) as ParseResult.Success
        assertFalse(result.kept)
        assertNothingWasRemoved()
    }

    @Test fun `a typed recipe at the limit comes back unsaved`() = runTest {
        fill()
        assertEquals(0L, repository.addManual(RecipeDraft(name = "Toast", ingredientsText = "bread"))!!.id)
        assertNothingWasRemoved()
    }

    @Test fun `a scanned or photo-read recipe at the limit comes back unsaved`() = runTest {
        fill()
        val draft = RecipeDraft(name = "Gnocchi", ingredientsText = "500 g Kartoffeln")
        assertEquals(0L, repository.addManual(draft, language = "de")!!.id)
        assertNothingWasRemoved()
    }

    @Test fun `shared text at the limit comes back unsaved`() = runTest {
        fill()
        val draft = RecipeDraft(name = "Pancakes", ingredientsText = "2 eggs")
        assertEquals(0L, repository.addManual(draft, language = "en", notes = "Left over from the text")!!.id)
        assertNothingWasRemoved()
    }

    @Test fun `a recipe shown unsaved is kept once the cook unlocks`() = runTest {
        fill()
        val shown = (repository.importFromUrl("https://a.com/soup") as ParseResult.Success).recipe
        library.limit = LibraryLimit.Unlimited
        val kept = repository.keep(shown) as ParseResult.Success
        assertTrue(kept.kept)
        assertEquals(51, dao.rows.size)
    }

    @Test fun `deleting one of the fifty frees a place`() = runTest {
        fill()
        dao.delete(dao.rows.keys.first())
        val result = repository.importFromUrl("https://a.com/soup") as ParseResult.Success
        assertTrue(result.kept)
        assertEquals(50, dao.rows.size)
    }

    @Test fun `a recipe already saved still opens and refreshes at the limit`() = runTest {
        repository.importFromUrl("https://a.com/soup")
        repeat(49) { n ->
            dao.upsert(
                RecipeEntity(
                    sourceUrl = "https://old.com/$n", title = "Old $n", imageUrl = null, ingredients = listOf("x"),
                    instructions = listOf("y"), prepTime = null, cookTime = null, totalTime = null, servings = null,
                    sourceType = "BLOG", lastViewedAt = n.toLong()
                ),
                LibraryLimit.Unlimited
            )
        }
        assertEquals(50, dao.rows.size)
        fetched = recipe("https://a.com/soup", "Soup, improved")
        val again = repository.importFromUrl("https://a.com/soup") as ParseResult.Success
        assertTrue(again.kept)
        assertEquals("Soup, improved", again.recipe.name)
        assertEquals(50, dao.rows.size)
    }

    @Test fun `with the free tier off or unlocked nothing is removed past fifty either`() = runTest {
        library.limit = LibraryLimit.Unlimited
        repeat(60) { n ->
            fetched = recipe("https://a.com/$n", "R$n")
            assertTrue((repository.importFromUrl("https://a.com/$n") as ParseResult.Success).kept)
        }
        assertEquals(60, dao.rows.size)
    }
}
