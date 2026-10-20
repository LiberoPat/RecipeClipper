package com.example.recipeclipper.ui.home

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.SavedTimer
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun summary(id: Long, title: String = "Recipe $id") =
        RecipeSummary(id = id, title = title, imageUrl = null, totalTime = null, lastViewedAt = id, isSaved = false)

    @Test fun `continueCooking is the most recent, recent is the five before it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            // Newest first, as the repository's real query would order it.
            val summaries = (6 downTo 1).map { summary(it.toLong()) }
            repository.recent.value = summaries
            val vm = HomeViewModel(repository)
            collectEagerly(vm.uiState)
            advanceUntilIdle()

            assertEquals(summaries[0], vm.uiState.value.continueCooking)
            assertEquals(summaries.drop(1), vm.uiState.value.recent)
        }

    @Test fun `loaded stays false until the repository answers`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = HomeViewModel(repository)

        assertFalse(vm.uiState.value.loaded)

        collectEagerly(vm.uiState)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.loaded)
    }

    // "Paste a link" (#286): the Recipes screen's dialog, opened from Home's tile.

    @Test fun `the paste tile opens the dialog empty, and Go returns the normalised link`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = HomeViewModel(FakeRecipeRepository())
            collectEagerly(vm.uiState)
            advanceUntilIdle()
            assertNull(vm.uiState.value.linkInput)

            vm.onPasteLink()
            advanceUntilIdle()
            assertEquals("", vm.uiState.value.linkInput)
            assertFalse(vm.uiState.value.canOpenLink)

            vm.onLinkChange("example.com/recipe")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.canOpenLink)
            assertEquals("https://example.com/recipe", vm.onOpenLink())
            advanceUntilIdle()
            assertNull("the dialog closes", vm.uiState.value.linkInput)
        }

    @Test fun `what isn't a link can't be opened, and the dialog stays`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(FakeRecipeRepository())
        collectEagerly(vm.uiState)
        vm.onPasteLink()
        vm.onLinkChange("not a link")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canOpenLink)
        assertNull(vm.onOpenLink())
        advanceUntilIdle()
        assertEquals("not a link", vm.uiState.value.linkInput)

        vm.onLinkDismiss()
        advanceUntilIdle()
        assertNull(vm.uiState.value.linkInput)
    }

    // The Recipes and Lists rows' counts (#286).

    @Test fun `the counts are what Recipes lists and how many lists there are`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            repository.history.value = (1L..3L).map { summary(it) }
            val lists = FakeListRepository()
            lists.lists.value = (1L..6L).map { RecipeList(it, "List $it", true, it == 1L, recipeCount = 0) }
            val vm = HomeViewModel(repository, lists = lists)
            collectEagerly(vm.uiState)
            advanceUntilIdle()

            assertEquals(3, vm.uiState.value.recipeCount)
            assertEquals(6, vm.uiState.value.listCount)
        }

    @Test fun `with no list repository there is no list count`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = HomeViewModel(FakeRecipeRepository())
        collectEagerly(vm.uiState)
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.recipeCount)
        assertNull(vm.uiState.value.listCount)
    }

    // The resume card (#286): the step stopped on and a running timer, only while it's true.

    private fun recipe(id: Long, cook: CookProgress, steps: Int = 4) = Recipe(
        name = "Recipe $id", image = null, ingredients = listOf("1 egg"),
        instructions = (1..steps).map { "Step $it" }, prepTime = null, cookTime = null,
        totalTime = null, yield = null, sourceUrl = "https://example.com/$id", id = id, cook = cook
    )

    /** Recipe 1 is Continue cooking, saved with [cook]; the clock is the test's virtual time. */
    private fun TestScope.cooking(cook: CookProgress, repository: FakeRecipeRepository = FakeRecipeRepository()): HomeViewModel {
        repository.recent.value = listOf(summary(1), summary(2))
        repository.recipes.value = mapOf(1L to recipe(1, cook))
        val vm = HomeViewModel(repository, clock = Clock { testScheduler.currentTime })
        collectEagerly(vm.uiState)
        return vm
    }

    @Test fun `a recipe that isn't being cooked has no place to resume`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = cooking(CookProgress(currentStep = 2, doneSteps = setOf(0, 1)))
        advanceUntilIdle()

        assertEquals(1L, vm.uiState.value.continueCooking?.id)
        assertNull(vm.uiState.value.resume)
    }

    @Test fun `cook mode left on resumes at its step, with no timer`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = cooking(CookProgress(active = true, currentStep = 1, doneSteps = setOf(0)))
        advanceUntilIdle()

        assertEquals(CookResume(1, step = 2, stepCount = 4, timerSecondsLeft = null), vm.uiState.value.resume)
    }

    @Test fun `a running timer counts down from its deadline, then goes`() = runTest(mainDispatcherRule.dispatcher) {
        val timer = SavedTimer(totalSeconds = 600, remainingSeconds = 600, endsAt = 90_000)
        val repository = FakeRecipeRepository()
        val vm = cooking(CookProgress(active = true, currentStep = 1, timers = mapOf(1 to timer)), repository)
        runCurrent()
        assertEquals(90, vm.uiState.value.resume?.timerSecondsLeft)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(60, vm.uiState.value.resume?.timerSecondsLeft)

        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(CookResume(1, 2, 4, timerSecondsLeft = null), vm.uiState.value.resume)
        assertEquals("nothing is written", emptyList<Any>(), repository.setCookProgressCalls)
    }

    @Test fun `a timer still running out of cook mode is cooking too`() = runTest(mainDispatcherRule.dispatcher) {
        val timer = SavedTimer(totalSeconds = 600, remainingSeconds = 600, endsAt = 120_000)
        val vm = cooking(CookProgress(active = false, currentStep = 3, timers = mapOf(3 to timer)))
        runCurrent()

        assertEquals(CookResume(1, 4, 4, timerSecondsLeft = 120), vm.uiState.value.resume)
    }

    @Test fun `the card follows the cook's progress as it is saved`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = cooking(CookProgress(), repository)
        advanceUntilIdle()
        assertNull(vm.uiState.value.resume)

        repository.recipes.value = mapOf(1L to recipe(1, CookProgress(active = true, currentStep = 2)))
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.resume?.step)
    }

    @Test fun `a newer recipe never shows the last one's place`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = cooking(CookProgress(active = true), repository)
        advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.resume?.recipeId)

        repository.recent.value = listOf(summary(2), summary(1))
        advanceUntilIdle()

        assertEquals(2L, vm.uiState.value.continueCooking?.id)
        assertNull(vm.uiState.value.resume)
    }

}
