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
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The import error screen. "Report this site" sits beside "Try again" only for a page with no
 * recipe, and opens the prefilled issue link through the [UriHandler] (ACTION_VIEW on a
 * device). A recording handler stands in here, so the test sees the link without a browser.
 */
@RunWith(AndroidJUnit4::class)
class RecipeErrorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val link = "https://example.com/no-recipe"

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
            FakeAppInfo(),
            FakeTimerAlarmScheduler()
        )
        val saveViewModel = SaveToListViewModel(FakeListRepository())
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                RecipeScreen(
                    onBack = {},
                    viewModel = viewModel,
                    saveViewModel = saveViewModel
                )
            }
        }
        return opened
    }

    @Test
    fun aPageWithNoRecipeOffersReportThisSiteBesideTryAgain() {
        show(ParseError.NoRecipeFound)

        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Report this site").assertIsDisplayed()
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
    }

    @Test
    fun aBlockedSiteOffersNoReport() = assertNoReport(ParseError.Blocked(403))

    @Test
    fun beingOfflineOffersNoReport() = assertNoReport(ParseError.Offline)

    @Test
    fun aFailedFetchOffersNoReport() = assertNoReport(ParseError.FetchFailed("HTTP 400"))
}
