package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.local.CookStateJson
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A recipe's tips (#432) through [DefaultRecipeRepository], over an in-memory DAO: with
 * `recipeTips` on they are stored apart from the steps; off, a fresh parse's tips are its last
 * steps, as before. A recipe saved with its tips as steps simply refreshes on a re-share. iOS:
 * `RecipeTipsDataTests`.
 */
class DefaultRecipeRepositoryTipsTest {

    private val url = "https://www.kingarthurbaking.com/recipes/mini-apple-pies-recipe"

    private object NoLog : ErrorLog {
        override fun error(message: String, cause: Throwable) = Unit
    }

    private class StagedSource(var result: ParseResult) : RecipeSource {
        override suspend fun fetch(url: String): ParseResult = result
    }

    private val pies = Recipe(
        name = "Mini Apple Pies", image = null, ingredients = listOf("1 apple"), instructions = listOf("Bake.", "Cool."),
        prepTime = null, cookTime = null, totalTime = null, yield = "24", sourceUrl = url,
        tips = listOf("Use a baking stone.")
    )

    private val dao = InMemoryRecipeDao()
    private val source = StagedSource(ParseResult.Success(pies))

    private fun repository(tipsOn: Boolean) = DefaultRecipeRepository(
        source, dao, Clock { 1_000L }, NoLog,
        flags = FeatureFlags(FakeFeatureFlagStore(mapOf(Flag.RECIPE_TIPS.key to tipsOn)), FlagRegistry.definitions, isDebug = false)
    )

    private suspend fun DefaultRecipeRepository.imported(): Recipe =
        (importFromUrl(url) as ParseResult.Success).recipe

    @Test fun `with recipeTips on, the tips are stored apart from the steps`() = runTest {
        val saved = repository(tipsOn = true).imported()
        assertEquals(listOf("Bake.", "Cool."), saved.instructions)
        assertEquals(listOf("Use a baking stone."), saved.tips)
        assertEquals(listOf("Use a baking stone."), dao.rows.values.single().tips)
    }

    @Test fun `with recipeTips off, a fresh parse's tips are its last steps, as before`() = runTest {
        val saved = repository(tipsOn = false).imported()
        assertEquals(listOf("Bake.", "Cool.", "Use a baking stone."), saved.instructions)
        assertEquals(emptyList<String>(), saved.tips)
    }

    @Test fun `a recipe saved with its tips as steps refreshes on a re-share, its progress going with the old steps`() = runTest {
        val old = repository(tipsOn = false).imported()
        dao.setCookState(old.id, CookStateJson.encode(CookProgress(active = true, currentStep = 2)))
        assertNotNull(dao.get(old.id)!!.cookState)

        val refreshed = repository(tipsOn = true).imported()

        assertEquals(old.id, refreshed.id)
        assertEquals(listOf("Bake.", "Cool."), refreshed.instructions)
        assertEquals(listOf("Use a baking stone."), refreshed.tips)
        assertNull("the steps changed, so the step indexes no longer mean the same steps", dao.get(old.id)!!.cookState)
    }

    @Test fun `cook progress stays when only the tips change`() = runTest {
        val repository = repository(tipsOn = true)
        val first = repository.imported()
        dao.setCookState(first.id, CookStateJson.encode(CookProgress(active = true, currentStep = 1)))
        source.result = ParseResult.Success(pies.copy(tips = listOf("Use a baking stone.", "Or a steel.")))

        val again = repository.imported()

        assertEquals(listOf("Use a baking stone.", "Or a steel."), again.tips)
        assertNotNull(dao.get(first.id)!!.cookState)
    }

    @Test fun `an edit keeps the tips as the tips box holds them`() = runTest {
        val repository = repository(tipsOn = true)
        val id = repository.imported().id
        val draft = RecipeDraft.of(repository.open(id)!!)
        assertEquals("Use a baking stone.", draft.tipsText)

        val kept = repository.saveEdit(id, draft.copy(name = "My pies"))!!
        assertEquals(listOf("Use a baking stone."), kept.tips)

        val cleared = repository.saveEdit(id, draft.copy(tipsText = "  \n"))!!
        assertEquals(emptyList<String>(), cleared.tips)
    }
}
