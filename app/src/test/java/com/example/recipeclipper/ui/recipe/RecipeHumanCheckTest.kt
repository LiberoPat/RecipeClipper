package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Cloudflare's check wanting a person (#220): the import opens the page for the cook to pass it
 * instead of an error screen. Every other outcome keeps its own screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeHumanCheckTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val url = "https://recipes.example.test/lemon-drizzle-cake/"

    private fun TestScope.importing(error: ParseError): RecipeUiState {
        val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(error) }
        val vm = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to url)), repository, FakeAppPreferences(),
            Clock { testScheduler.currentTime }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler()
        )
        advanceUntilIdle()
        return vm.uiState.value
    }

    @Test fun `a check that wants a person opens the page instead of an error`() = runTest(mainDispatcherRule.dispatcher) {
        val state = importing(ParseError.HumanCheck)

        assertEquals(url, state.humanCheckPage)
        assertEquals(RecipeContent.Loading, state.content)
        assertNull(state.clipUrl)
        assertNull(state.clipBlockedPost)
    }

    @Test fun `an ordinary block, offline and a timeout keep their screens`() = runTest(mainDispatcherRule.dispatcher) {
        listOf(ParseError.Blocked(403), ParseError.Offline, ParseError.FetchFailed("timeout", timedOut = true))
            .forEach { error ->
                val state = importing(error)
                assertEquals(RecipeContent.Error(error), state.content)
                assertNull(state.humanCheckPage)
            }
    }

    @Test fun `a page past the check with no recipe offers the clip as before`() = runTest(mainDispatcherRule.dispatcher) {
        val state = importing(ParseError.NoRecipeFound)

        assertEquals(url, state.clipUrl)
        assertNull(state.humanCheckPage)
    }
}
