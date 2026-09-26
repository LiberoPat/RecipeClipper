package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
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
        compose.onNodeWithText("Not saved: your 20 recipes are all in lists, plans or typed in.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save to a list").assertDoesNotExist()

        compose.onNodeWithText("Unlock").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Soup"), repository.keepCalls.map { it.name })
        compose.onNodeWithContentDescription("Save to a list").assertIsDisplayed()
    }
}
