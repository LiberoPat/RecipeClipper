package com.example.recipeclipper.ui.clip

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.ClipDraftStore
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import com.example.recipeclipper.ui.recipe.RecipeScreen
import com.example.recipeclipper.ui.recipe.RecipeViewModel
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cloudflare's check (#220) on screen: the import hands the page to the clip view without an
 * error, the note says what to do, and there's nothing to clip until the check is past. The
 * page isn't loaded (no network, and Robolectric's WebView draws nothing).
 */
@RunWith(AndroidJUnit4::class)
class ClipHumanCheckNoteScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val url = "https://recipes.example.test/lemon-drizzle-cake/"
    private val note = "This site wants to check you're human. Tick the box and the recipe will open."
    private val noRecipeNote =
        "The check passed, but this page has no recipe the app can read, so it's open here: select the recipe."

    private fun showCheck(repository: FakeRecipeRepository = FakeRecipeRepository()): ClipViewModel {
        val viewModel = ClipViewModel(
            SavedStateHandle(mapOf(ClipViewModel.URL_ARG to url, ClipViewModel.CHECK_ARG to true)),
            repository,
            ClipDraftStore()
        )
        compose.setContent {
            ClipScreen(onCancel = {}, onSaved = {}, viewModel = viewModel, loadPage = { _, _ -> })
        }
        return viewModel
    }

    @Test
    fun theCheckSaysWhatToDoAndOffersNoClipping() {
        showCheck()

        compose.onNodeWithText(note).assertIsDisplayed()
        compose.onNodeWithText("Tap Name below", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Done").assertIsNotEnabled()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun pastTheCheckWithNoRecipeTheClipIsOffered() {
        val repository = FakeRecipeRepository().apply {
            importPageResults["<html>story</html>"] = ParseResult.Error(ParseError.NoRecipeFound)
        }
        val viewModel = showCheck(repository)

        compose.runOnIdle { viewModel.onPageLoaded("<html>story</html>") }
        compose.waitForIdle()

        compose.onNodeWithText(note).assertDoesNotExist()
        compose.onNodeWithText(noRecipeNote).assertIsDisplayed()
        compose.onNodeWithText("Tap Name below", substring = true).assertIsDisplayed()
    }

    @Test
    fun theImportHandsTheCheckToTheClipViewWithoutAnErrorScreen() {
        val checked = mutableListOf<String>()
        val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(ParseError.HumanCheck) }
        val viewModel = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to url)), repository, FakeAppPreferences(), { 0L },
            FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler()
        )
        val saveViewModel = SaveToListViewModel(FakeListRepository())
        compose.setContent {
            RecipeScreen(
                onBack = {},
                onHumanCheck = { checked += it },
                viewModel = viewModel,
                saveViewModel = saveViewModel
            )
        }
        compose.waitForIdle()

        assertEquals(listOf(url), checked)
        compose.onNodeWithText("check you're human", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
}
