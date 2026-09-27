package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.ManualRecipe
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Update from source" on the recipe screen (#29). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeUpdateFromSourceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun recipe(
        name: String = "Grandma's soup",
        origin: ContentOrigin = ContentOrigin.EDITED,
        sourceUrl: String = "https://example.com/soup"
    ) = Recipe(
        name = name, image = null, ingredients = listOf("1 onion"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, yield = "4", sourceUrl = sourceUrl,
        id = 1L, origin = origin, editedAt = if (origin == ContentOrigin.PARSED) null else 5L
    )

    private fun TestScope.open(repository: FakeRecipeRepository) = RecipeViewModel(
        SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to 1L)), repository, FakeAppPreferences(),
        Clock { testScheduler.currentTime }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler()
    ).also { advanceUntilIdle() }

    private val RecipeViewModel.shown: Recipe
        get() = (uiState.value.content as RecipeContent.Success).recipe

    @Test fun `offered only for the user's version of a linked recipe`() {
        assertTrue(recipe(origin = ContentOrigin.EDITED).canUpdateFromSource)
        assertTrue(recipe(origin = ContentOrigin.CLIPPED).canUpdateFromSource)
        assertFalse(recipe(origin = ContentOrigin.PARSED).canUpdateFromSource)
        assertFalse(
            recipe(origin = ContentOrigin.MANUAL, sourceUrl = ManualRecipe.newSourceUrl("x")).canUpdateFromSource
        )
    }

    @Test fun `success shows the site's version`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            openResult = recipe()
            updateFromSourceResult = ParseResult.Success(recipe(name = "Soup", origin = ContentOrigin.PARSED))
        }
        val vm = open(repository)

        vm.onUpdateFromSource()
        advanceUntilIdle()

        assertEquals(listOf(1L), repository.updateFromSourceCalls)
        assertEquals("Soup", vm.shown.name)
        assertFalse(vm.uiState.value.updatingFromSource)
        assertNull(vm.uiState.value.updateError)
    }

    @Test fun `failure keeps the user's version and reports why, once`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply {
            openResult = recipe()
            updateFromSourceResult = ParseResult.Error(ParseError.Offline)
        }
        val vm = open(repository)

        vm.onUpdateFromSource()
        advanceUntilIdle()

        assertEquals("Grandma's soup", vm.shown.name)
        assertEquals(ParseError.Offline, vm.uiState.value.updateError)
        vm.onUpdateErrorShown()
        assertNull(vm.uiState.value.updateError)
    }

    @Test fun `does nothing for a parsed recipe`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { openResult = recipe(origin = ContentOrigin.PARSED) }
        val vm = open(repository)

        vm.onUpdateFromSource()
        advanceUntilIdle()

        assertTrue(repository.updateFromSourceCalls.isEmpty())
    }
}
