package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.local.CookStateJson
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.SavedTimer
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
 * steps, as before. A recipe saved with its tips as steps refreshes on a re-share and keeps its
 * cook progress, clamped to the steps left; any other change of steps still drops it. iOS:
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

    private val timer = SavedTimer(totalSeconds = 600, remainingSeconds = 300, endsAt = null)

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

    @Test fun `a recipe saved with its tips as steps refreshes on a re-share, keeping its progress`() = runTest {
        val old = repository(tipsOn = false).imported()
        val progress = CookProgress(active = true, currentStep = 1, doneSteps = setOf(0), timers = mapOf(1 to timer))
        dao.setCookState(old.id, CookStateJson.encode(progress))

        val refreshed = repository(tipsOn = true).imported()

        assertEquals(old.id, refreshed.id)
        assertEquals(listOf("Bake.", "Cool."), refreshed.instructions)
        assertEquals(listOf("Use a baking stone."), refreshed.tips)
        assertEquals("only the last steps became tips: the indexes still mean the same steps",
            progress, CookStateJson.decode(dao.get(old.id)!!.cookState))
    }

    @Test fun `progress that pointed at a former tip row is clamped to the steps left`() = runTest {
        val old = repository(tipsOn = false).imported()
        dao.setCookState(old.id, CookStateJson.encode(
            CookProgress(active = true, currentStep = 2, doneSteps = setOf(0, 1, 2), timers = mapOf(0 to timer, 2 to timer))
        ))

        repository(tipsOn = true).imported()

        assertEquals(
            CookProgress(active = true, currentStep = 1, doneSteps = setOf(0, 1), timers = mapOf(0 to timer)),
            CookStateJson.decode(dao.get(old.id)!!.cookState)
        )
    }

    @Test fun `progress still goes when a real step changed as well`() = runTest {
        val old = repository(tipsOn = false).imported()
        dao.setCookState(old.id, CookStateJson.encode(CookProgress(active = true, currentStep = 1)))
        source.result = ParseResult.Success(pies.copy(instructions = listOf("Bake well.", "Cool.")))

        repository(tipsOn = true).imported()

        assertNull("the steps changed, so the step indexes no longer mean the same steps", dao.get(old.id)!!.cookState)
    }

    @Test fun `with no tips, any change of steps still drops the progress`() = runTest {
        source.result = ParseResult.Success(pies.copy(tips = emptyList()))
        val repository = repository(tipsOn = true)
        val first = repository.imported()
        dao.setCookState(first.id, CookStateJson.encode(CookProgress(active = true, currentStep = 1)))
        source.result = ParseResult.Success(pies.copy(instructions = listOf("Bake."), tips = emptyList()))

        repository.imported()

        assertNull(dao.get(first.id)!!.cookState)
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
