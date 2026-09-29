package com.example.recipeclipper.ui.clip

import androidx.compose.ui.test.assertIsDisplayed
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
 * Reddit's block (#213) on screen: the import hands the post to "Clip it yourself" without
 * showing an error, and the clip says why it opened. The page isn't loaded here (no network,
 * and Robolectric's WebView draws nothing); the device's ClipScreenTest covers the page.
 */
@RunWith(AndroidJUnit4::class)
class ClipBlockedNoteScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val post = "https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo/"
    private val note = "Reddit didn't let the app read this post, so it's open here: select the recipe."

    private fun showClip(blocked: Boolean) {
        val viewModel = ClipViewModel(
            SavedStateHandle(mapOf(ClipViewModel.URL_ARG to post, ClipViewModel.BLOCKED_ARG to blocked)),
            FakeRecipeRepository(),
            ClipDraftStore()
        )
        compose.setContent {
            ClipScreen(onCancel = {}, onSaved = {}, viewModel = viewModel, loadPage = { _, _ -> })
        }
    }

    @Test
    fun aClipOpenedByRedditsBlockSaysWhy() {
        showClip(blocked = true)

        compose.onNodeWithText(note).assertIsDisplayed()
        // No way to try the read again from here: the block isn't one that lifts.
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Select text on the page", substring = true).assertIsDisplayed()
    }

    @Test
    fun aClipOpenedFromTheErrorScreenHasNoNote() {
        showClip(blocked = false)

        compose.onNodeWithText(note).assertDoesNotExist()
    }

    @Test
    fun theImportHandsABlockedPostToTheClipWithoutAnErrorScreen() {
        val clipped = mutableListOf<String>()
        val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(ParseError.Blocked(403)) }
        val viewModel = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to post)), repository, FakeAppPreferences(), { 0L },
            FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler()
        )
        val saveViewModel = SaveToListViewModel(FakeListRepository())
        compose.setContent {
            RecipeScreen(
                onBack = {},
                onClipBlocked = { clipped += it },
                viewModel = viewModel,
                saveViewModel = saveViewModel
            )
        }
        compose.waitForIdle()

        assertEquals(listOf(post), clipped)
        compose.onNodeWithText("didn't let the app in", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
}
