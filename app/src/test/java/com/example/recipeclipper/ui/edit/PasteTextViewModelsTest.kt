package com.example.recipeclipper.ui.edit

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.askai.AskAiViewModel
import com.example.recipeclipper.ui.askai.PasteProblem
import com.example.recipeclipper.ui.home.HomeViewModel
import com.example.recipeclipper.ui.recipes.RecipesViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The three ways to paste (#372), each into the shared-text route: Home's and Recipes' "Paste a
 * link" dialogs and the Ask an AI sheet's "Paste the answer". A link, a JSON recipe, a week, a
 * recipe written out, and nothing. Nothing is saved by any of them (the editor's Save does that).
 * iOS: `PasteTextViewModelsTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasteTextViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fixtures = PastedTextFixtures()

    /** One way to paste: types or hands [text] over, and returns where it led (null: nowhere). */
    private fun interface Paster {
        fun paste(scope: TestScope, text: String): PasteTarget?
    }

    private val home = Paster { scope, text -> with(scope) {
        val repository = FakeRecipeRepository()
        val vm = HomeViewModel(repository, pasted = fixtures.router)
        collectEagerly(vm.uiState)
        vm.onPasteLink()
        vm.onLinkChange(text)
        var target: PasteTarget? = null
        vm.onOpenLink { target = it }
        advanceUntilIdle()
        assertEquals("the dialog closes only on a target", target == null, vm.uiState.value.linkInput != null)
        assertNothingSaved(repository)
        target
    } }

    private val recipes = Paster { scope, text -> with(scope) {
        val repository = FakeRecipeRepository()
        val vm = RecipesViewModel(repository, FakeAppPreferences(), pasted = fixtures.router)
        collectEagerly(vm.uiState)
        vm.onPasteLink()
        vm.onLinkChange(text)
        var target: PasteTarget? = null
        vm.onOpenLink { target = it }
        advanceUntilIdle()
        assertEquals("the dialog closes only on a target", target == null, vm.uiState.value.linkInput != null)
        assertNothingSaved(repository)
        target
    } }

    private fun assertNothingSaved(repository: FakeRecipeRepository) = assertTrue(
        "nothing saved",
        repository.addManualCalls.isEmpty() && repository.saveClipCalls.isEmpty() && repository.importCalls == 0
    )

    private val askAi = Paster { scope, text -> with(scope) {
        val vm = askAiViewModel()
        collectEagerly(vm.uiState)
        var target: PasteTarget? = null
        vm.onPasteAnswer(text) { target = it }
        advanceUntilIdle()
        target
    } }

    private fun askAiViewModel() = AskAiViewModel(
        FakePantryRepository(), FakeListRepository(), FakeRecipeRepository(), FakeAppPreferences(),
        Clock { 1_800_000_000_000L }, fixtures.router
    )

    private val pasters = listOf("Home" to home, "Recipes" to recipes, "Ask an AI" to askAi)

    private fun forEachPaster(check: TestScope.(Paster) -> Unit) = runTest(mainDispatcherRule.dispatcher) {
        pasters.forEach { (_, paster) -> check(paster) }
    }

    private fun TestScope.paste(paster: Paster, text: String): PasteTarget? = paster.paste(this, text)

    @Test fun `a link imports`() = forEachPaster { paster ->
        assertEquals(PasteTarget.Link("https://example.com/soup"), paste(paster, "https://example.com/soup"))
    }

    @Test fun `a JSON recipe opens the editor`() = forEachPaster { paster ->
        val target = paste(paster, PastedTextFixtures.json) as PasteTarget.Review
        assertEquals("Lemon Pasta", fixtures.inbox.take(target.key)!!.reading.name)
    }

    @Test fun `a week of recipes opens the meal-plan review`() = forEachPaster { paster ->
        val target = paste(paster, PastedTextFixtures.plan) as PasteTarget.Plan
        assertEquals(2, fixtures.inbox.takePlan(target.key)!!.plan.items.size)
    }

    @Test fun `a recipe written out opens the editor`() = forEachPaster { paster ->
        val target = paste(paster, PastedTextFixtures.prose) as PasteTarget.Review
        assertTrue(fixtures.inbox.take(target.key)!!.reading.sorted)
    }

    @Test fun `an answer cut off before its first recipe ended opens the cut-off screen`() = forEachPaster { paster ->
        val cut = "```json\n[\n  {\n    \"@type\": \"Recipe\",\n    \"name\": \"Mon\",\n" +
            "    \"day\": \"2026-11-19\",\n    \"recipeInstructions\": [\n      { \"@type\": \"HowToStep\","
        assertEquals(PasteTarget.CutOff, paste(paster, cut))
    }

    @Test fun `empty text goes nowhere`() = forEachPaster { paster ->
        assertNull(paste(paster, ""))
        assertNull(paste(paster, "   \n"))
    }

    // What each says when there is nothing to open.

    @Test fun `the dialog's Go is off for blank text, and says so for text too long to read`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = HomeViewModel(FakeRecipeRepository(), pasted = fixtures.router)
            collectEagerly(vm.uiState)
            vm.onPasteLink()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.canOpenLink)

            vm.onLinkChange("  ")
            advanceUntilIdle()
            assertFalse(vm.uiState.value.canOpenLink)

            vm.onLinkChange("x".repeat(60_000))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.canOpenLink)
            var target: PasteTarget? = null
            vm.onOpenLink { target = it }
            advanceUntilIdle()
            assertNull(target)
            assertTrue(vm.uiState.value.linkNoRecipe)
        }

    @Test fun `Paste the answer says an empty clipboard is empty, and clears when anything changes`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = askAiViewModel()
            collectEagerly(vm.uiState)
            var target: PasteTarget? = null

            vm.onPasteAnswer(null) { target = it }
            advanceUntilIdle()
            assertEquals(PasteProblem.EMPTY, vm.uiState.value.pasteProblem)
            vm.onPasteAnswer("  ") { target = it }
            advanceUntilIdle()
            assertEquals(PasteProblem.EMPTY, vm.uiState.value.pasteProblem)

            vm.onRequestChange("soup")
            advanceUntilIdle()
            assertNull(vm.uiState.value.pasteProblem)

            vm.onPasteAnswer("x".repeat(60_000)) { target = it }
            advanceUntilIdle()
            assertEquals(PasteProblem.NO_RECIPE, vm.uiState.value.pasteProblem)
            assertNull(target)
        }
}
