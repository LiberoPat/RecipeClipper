package com.example.recipeclipper.ui.clip

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.ClipDraftStore
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The clip view opened for the cook to pass Cloudflare's check (#220): each page it settles on
 * is read through the repository; a recipe opens, the check keeps it waiting, and a page past it
 * with no recipe offers the clip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipHumanCheckTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val url = "https://recipes.example.test/lemon-drizzle-cake/"
    private val repository = FakeRecipeRepository()

    private fun viewModel(check: Boolean = true) = ClipViewModel(
        SavedStateHandle(mapOf(ClipViewModel.URL_ARG to url, ClipViewModel.CHECK_ARG to check)),
        repository,
        ClipDraftStore()
    )

    private fun recipe(id: Long) = Recipe(
        id = id, name = "Lemon drizzle cake", image = null, ingredients = listOf("4 eggs"),
        instructions = listOf("Bake."), prepTime = null, cookTime = null, totalTime = null, yield = null,
        sourceUrl = url
    )

    @Test fun `opened for the check it waits on it`() {
        assertEquals(ClipCheck.WAITING, viewModel().uiState.value.check)
        assertNull(viewModel(check = false).uiState.value.check)
    }

    @Test fun `the check still showing keeps it waiting`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.onPageLoaded("<html>check</html>")
        advanceUntilIdle()

        assertEquals(listOf(url to "<html>check</html>"), repository.importPageCalls)
        assertEquals(ClipCheck.WAITING, vm.uiState.value.check)
        assertNull(vm.uiState.value.savedRecipeId)
    }

    @Test fun `a page with a recipe opens it`() = runTest(mainDispatcherRule.dispatcher) {
        repository.importPageResults["<html>cake</html>"] = ParseResult.Success(recipe(7))
        val vm = viewModel()
        vm.onPageLoaded("<html>check</html>")
        vm.onPageLoaded("<html>cake</html>")
        advanceUntilIdle()

        assertEquals(7L, vm.uiState.value.savedRecipeId)
    }

    @Test fun `past the check with no recipe it offers the clip`() = runTest(mainDispatcherRule.dispatcher) {
        repository.importPageResults["<html>story</html>"] = ParseResult.Error(ParseError.NoRecipeFound)
        val vm = viewModel()
        vm.onPageLoaded("<html>story</html>")
        advanceUntilIdle()

        assertEquals(ClipCheck.NO_RECIPE, vm.uiState.value.check)
        // Once clipping, pages are no longer read.
        vm.onPageLoaded("<html>other</html>")
        advanceUntilIdle()
        assertEquals(1, repository.importPageCalls.size)
    }

    @Test fun `a full library shows the prompt, and nothing opens`() = runTest(mainDispatcherRule.dispatcher) {
        repository.importPageResults["<html>cake</html>"] = ParseResult.Success(recipe(0), kept = false)
        val vm = viewModel()
        vm.onPageLoaded("<html>cake</html>")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.libraryFull)
        assertNull(vm.uiState.value.savedRecipeId)
    }

    @Test fun `an ordinary clip never reads the page`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(check = false)
        vm.onPageLoaded("<html>cake</html>")
        advanceUntilIdle()

        assertTrue(repository.importPageCalls.isEmpty())
    }
}
