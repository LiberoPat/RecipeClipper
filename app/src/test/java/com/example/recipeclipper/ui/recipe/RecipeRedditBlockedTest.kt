package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultRecipeRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.InMemoryRecipeDao
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeFeatureFlagStore
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
 * Reddit's block (#213): an import of a Reddit post that ends Blocked, after the repository's one
 * retry and with no saved copy, opens "Clip it yourself" on the post instead of an error screen.
 * Every other outcome keeps its own screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeRedditBlockedTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val post = "https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo/"
    private val shareLink = "https://www.reddit.com/r/recipes/s/AbCd123"

    private fun TestScope.viewModel(
        url: String,
        repository: RecipeRepository,
        flags: FeatureFlags? = null
    ) = RecipeViewModel(
        SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to url)), repository, FakeAppPreferences(),
        Clock { testScheduler.currentTime }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler(),
        featureFlags = flags
    )

    private fun TestScope.importing(url: String, error: ParseError, flags: FeatureFlags? = null): RecipeUiState {
        val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(error) }
        val vm = viewModel(url, repository, flags)
        advanceUntilIdle()
        return vm.uiState.value
    }

    private fun recipe(url: String) = Recipe(
        name = "Lemon orzo", image = null, ingredients = listOf("1 cup orzo"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, yield = "2", sourceUrl = url
    )

    @Test fun `a blocked reddit post opens the clip instead of an error`() = runTest(mainDispatcherRule.dispatcher) {
        val state = importing(post, ParseError.Blocked(403))

        assertEquals(post, state.clipBlockedPost)
        // No error screen shows meanwhile, and nothing on it is offered.
        assertEquals(RecipeContent.Loading, state.content)
        assertNull(state.clipUrl)
        assertNull(state.reportSiteUrl)
    }

    @Test fun `a share link is handed to the clip as it is`() = runTest(mainDispatcherRule.dispatcher) {
        assertEquals(shareLink, importing(shareLink, ParseError.Blocked(429)).clipBlockedPost)
    }

    @Test fun `a post saved before opens from the saved copy`() = runTest(mainDispatcherRule.dispatcher) {
        // The real repository: its one retry, then its fallback to the saved copy.
        val dao = InMemoryRecipeDao()
        var answer: ParseResult = ParseResult.Success(recipe(post))
        val source = object : RecipeSource {
            override suspend fun fetch(url: String): ParseResult = answer
        }
        val log = object : ErrorLog {
            override fun error(message: String, cause: Throwable) = Unit
        }
        val repository = DefaultRecipeRepository(source, dao, Clock { testScheduler.currentTime }, log)
        val savedId = (repository.importFromUrl(post) as ParseResult.Success).recipe.id

        answer = ParseResult.Error(ParseError.Blocked(403))
        val vm = viewModel(post, repository)
        advanceUntilIdle()

        val content = vm.uiState.value.content as RecipeContent.Success
        assertEquals(savedId, content.recipe.id)
        assertNull(vm.uiState.value.clipBlockedPost)
    }

    @Test fun `offline still shows the offline screen`() = runTest(mainDispatcherRule.dispatcher) {
        val state = importing(post, ParseError.Offline)
        assertEquals(RecipeContent.Error(ParseError.Offline), state.content)
        assertNull(state.clipBlockedPost)
    }

    @Test fun `a failed or timed out fetch keeps its error screen`() = runTest(mainDispatcherRule.dispatcher) {
        listOf(ParseError.FetchFailed("Connection reset"), ParseError.FetchFailed("timeout", timedOut = true))
            .forEach { error ->
                val state = importing(post, error)
                assertEquals(RecipeContent.Error(error), state.content)
                assertNull(state.clipBlockedPost)
            }
    }

    @Test fun `a post with no recipe text still offers to read its photo`() = runTest(mainDispatcherRule.dispatcher) {
        val error = ParseError.NoTranscription("Aunt June's cookies", "https://preview.redd.it/front.jpg")
        val state = importing(post, error)

        assertEquals(RecipeContent.Error(error), state.content)
        assertEquals(post, state.photoPost?.url)
        assertNull(state.clipBlockedPost)
    }

    @Test fun `another site's block is still an error screen`() = runTest(mainDispatcherRule.dispatcher) {
        val state = importing("https://example.com/pie", ParseError.Blocked(403))
        assertEquals(RecipeContent.Error(ParseError.Blocked(403)), state.content)
        assertNull(state.clipBlockedPost)
    }

    @Test fun `with the reddit flag off a blocked post is an error screen`() = runTest(mainDispatcherRule.dispatcher) {
        val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
            .apply { set(Flag.REDDIT, false) }
        val state = importing(post, ParseError.Blocked(403), flags)

        assertEquals(RecipeContent.Error(ParseError.Blocked(403)), state.content)
        assertNull(state.clipBlockedPost)
    }
}
