package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeLibraryPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free tier (#107) through [DefaultRecipeRepository]: a full library whose recipes are all
 * protected shows a shared recipe without keeping it, won't take a typed one, and keeps it once
 * unlocked. The removal rules themselves are `FreeTierDaoTest`'s, against real SQLite.
 */
class DefaultRecipeRepositoryLimitTest {

    private val dao = InMemoryRecipeDao()
    private val library = FakeLibraryPolicy(LibraryLimit.Free(20))
    private val parsed = Recipe(
        name = "Soup", image = null, ingredients = listOf("1 leek"), instructions = listOf("Simmer."),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "https://a.com/soup"
    )
    private val source = object : RecipeSource {
        override suspend fun fetch(url: String): ParseResult = ParseResult.Success(parsed)
    }
    private val repository = DefaultRecipeRepository(source, dao, Clock { 5_000 }, { _, _ -> }, library = library)

    /** Twenty typed-in recipes: every one protected, so nothing can make room. */
    private suspend fun fillWithProtected() = repeat(20) { n ->
        dao.upsert(
            RecipeEntity(
                sourceUrl = "manual:$n", title = "Typed $n", imageUrl = null, ingredients = listOf("x"),
                instructions = listOf("y"), prepTime = null, cookTime = null, totalTime = null, servings = null,
                sourceType = "BLOG", lastViewedAt = n.toLong(), contentOrigin = "MANUAL"
            ),
            LibraryLimit.Unlimited
        )
    }

    @Test fun `a shared recipe on a full, protected library is shown but not kept`() = runTest {
        fillWithProtected()
        val result = repository.importFromUrl("https://a.com/soup") as ParseResult.Success
        assertFalse(result.kept)
        assertEquals(0L, result.recipe.id)
        assertEquals("Soup", result.recipe.name)
        assertEquals(20, dao.rows.size)
    }

    @Test fun `keep saves it once there is room`() = runTest {
        fillWithProtected()
        val shown = (repository.importFromUrl("https://a.com/soup") as ParseResult.Success).recipe
        library.limit = LibraryLimit.Unlimited
        val kept = repository.keep(shown) as ParseResult.Success
        assertTrue(kept.kept)
        assertTrue(kept.recipe.id > 0)
        assertEquals(21, dao.rows.size)
    }

    @Test fun `a typed recipe on a full, protected library comes back unsaved`() = runTest {
        fillWithProtected()
        val draft = RecipeDraft(name = "Toast", ingredientsText = "bread")
        assertEquals(0L, repository.addManual(draft)!!.id)
        assertEquals(20, dao.rows.size)
    }

    @Test fun `with room, a shared recipe is kept as before`() = runTest {
        val result = repository.importFromUrl("https://a.com/soup") as ParseResult.Success
        assertTrue(result.kept)
        assertEquals(1, dao.rows.size)
    }
}
