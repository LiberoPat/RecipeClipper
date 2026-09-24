package com.example.recipeclipper.ui.recipe

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.SiteReportLink
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The import error screen. "Clip it yourself" (#37) and "Report this site" sit under "Try again"
 * only for a page with no recipe. Report opens the prefilled issue link through the [UriHandler] (ACTION_VIEW on a
 * device). A recording handler stands in here, so the test sees the link without a browser.
 */
@RunWith(AndroidJUnit4::class)
class RecipeErrorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val link = "https://example.com/no-recipe"

    private val clipped = mutableListOf<String>()

    private fun show(error: ParseError): MutableList<String> {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        val repository = FakeRecipeRepository().apply { importResult = ParseResult.Error(error) }
        val viewModel = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to link)),
            repository,
            FakeAppPreferences(),
            { 0L },
            FakeConnectivity(),
            FakeAppInfo()
        )
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                RecipeScreen(
                    onBack = {},
                    onClip = { clipped += it },
                    viewModel = viewModel,
                    saveViewModel = SaveToListViewModel(FakeListRepository())
                )
            }
        }
        return opened
    }

    @Test
    fun aPageWithNoRecipeOffersReportThisSiteUnderTryAgain() {
        show(ParseError.NoRecipeFound)

        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Report this site").assertIsDisplayed()
    }

    @Test
    fun aPageWithNoRecipeOffersToClipItByHand() {
        show(ParseError.NoRecipeFound)

        compose.onNodeWithText("Clip it yourself").performClick()
        compose.waitForIdle()

        assertEquals(listOf(link), clipped)
    }

    @Test
    fun reportThisSiteOpensThePrefilledIssue() {
        val opened = show(ParseError.NoRecipeFound)

        compose.onNodeWithText("Report this site").performClick()
        compose.waitForIdle()

        assertEquals(listOf(SiteReportLink.issueUrl(link, "Android 14 (API 34)", "1.0 (1)")), opened)
    }

    // One setContent per test, so one error each.
    private fun assertNoReport(error: ParseError) {
        show(error)
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Report this site").assertDoesNotExist()
        compose.onNodeWithText("Clip it yourself").assertDoesNotExist()
    }

    @Test
    fun aBlockedSiteOffersNoReport() = assertNoReport(ParseError.Blocked(403))

    @Test
    fun beingOfflineOffersNoReport() = assertNoReport(ParseError.Offline)

    @Test
    fun aFailedFetchOffersNoReport() = assertNoReport(ParseError.FetchFailed("HTTP 400"))
}
