package com.example.recipeclipper.ui.home

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.collectEagerly
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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

    @Test fun `onGo returns the normalised url and clears the input`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = HomeViewModel(repository)
        collectEagerly(vm.uiState)
        advanceUntilIdle()

        vm.onUrlChange("example.com/recipe")
        advanceUntilIdle()
        val result = vm.onGo()
        advanceUntilIdle()

        assertEquals("https://example.com/recipe", result)
        assertEquals("", vm.uiState.value.urlInput)
        assertFalse(vm.uiState.value.urlError)
    }

    @Test fun `onGo returns null and sets urlError on rubbish input`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = HomeViewModel(repository)
        collectEagerly(vm.uiState)
        advanceUntilIdle()

        vm.onUrlChange("not a url")
        advanceUntilIdle()
        val result = vm.onGo()
        advanceUntilIdle()

        assertNull(result)
        assertTrue(vm.uiState.value.urlError)
    }

    @Test fun `onUrlChange clears a previous error`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = HomeViewModel(repository)
        collectEagerly(vm.uiState)
        advanceUntilIdle()

        vm.onUrlChange("not a url")
        advanceUntilIdle()
        vm.onGo()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.urlError)

        vm.onUrlChange("something else")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.urlError)
    }
}
