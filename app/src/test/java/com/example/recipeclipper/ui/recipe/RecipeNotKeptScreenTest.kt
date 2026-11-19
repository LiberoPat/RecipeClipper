package com.example.recipeclipper.ui.recipe

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagValues
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A shared recipe the full free library couldn't keep (#107): shown, with Unlock, no bookmark. */
@RunWith(AndroidJUnit4::class)
class RecipeNotKeptScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theRecipeShowsWithUnlockAndNoBookmarkAndUnlockKeepsIt() {
        val shown = Recipe(
            name = "Soup", image = null, ingredients = listOf("1 leek"), instructions = listOf("Simmer."),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "https://a.com/soup"
        )
        val repository = FakeRecipeRepository().apply { importResult = ParseResult.Success(shown, kept = false) }
        val viewModel = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to "https://a.com/soup")), repository, FakeAppPreferences(),
            { 0L }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler(),
            entitlements = FakeEntitlements()
        )
        val saveViewModel = SaveToListViewModel(FakeListRepository())
        compose.setContent {
            RecipeScreen(onBack = {}, viewModel = viewModel, saveViewModel = saveViewModel)
        }

        compose.onNodeWithText("Soup").assertIsDisplayed()
        compose.onNodeWithText("Not saved: the free app keeps 20 recipes.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save to a list").assertDoesNotExist()

        compose.onNodeWithText("Unlock").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Soup"), repository.keepCalls.map { it.name })
        compose.onNodeWithContentDescription("Save to a list").assertIsDisplayed()
    }

    /** One cookbook (#345): the limit is 50, and the bar says the free app keeps 50 recipes. */
    @Test
    fun withOneCookbookTheBarSaysFifty() {
        val shown = Recipe(
            name = "Soup", image = null, ingredients = listOf("1 leek"), instructions = listOf("Simmer."),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "https://a.com/soup"
        )
        val repository = FakeRecipeRepository().apply { importResult = ParseResult.Success(shown, kept = false) }
        val viewModel = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to "https://a.com/soup")), repository, FakeAppPreferences(),
            { 0L }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler(),
            entitlements = FakeEntitlements()
        )
        val saveViewModel = SaveToListViewModel(FakeListRepository())
        compose.setContent {
            CompositionLocalProvider(LocalFlagValues provides FlagValues(setOf(Flag.ONE_COOKBOOK))) {
                RecipeScreen(onBack = {}, viewModel = viewModel, saveViewModel = saveViewModel)
            }
        }

        compose.onNodeWithText("Soup").assertIsDisplayed()
        compose.onNodeWithText("Not saved: the free app keeps 50 recipes.").assertIsDisplayed()
        compose.onNodeWithText("Unlock").assertIsDisplayed()
    }
}
