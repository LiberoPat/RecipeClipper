package com.example.recipeclipper.ui.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeCookedPhotoRepository
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.edit.PastedTextFixtures
import com.example.recipeclipper.ui.home.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library first (#375): Paste a link, from the + 's sheet and from the empty library's tile, opens
 * the usual dialog and a link goes on to be imported. Apart from [LibraryFirstScreenTest] because a
 * dialog never goes idle under that class's tall `@Config` window, in Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class LibraryFirstPasteScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()
    private var openedUrl: String? = null

    private fun show() {
        val fixtures = PastedTextFixtures()
        val library = RecipesViewModel(repository, FakeAppPreferences(), lists = FakeListRepository(), pasted = fixtures.router)
        val home = HomeViewModel(
            repository, null, null, null, FakeListRepository(), Clock { 1_699_948_800_000L }, fixtures.router,
            FakeMealPlanRepository(), FakeCookedPhotoRepository(today = 0)
        )
        compose.setContent {
            RecipesScreen(
                onBack = {},
                onOpenRecipe = {},
                onOpenUrl = { openedUrl = it },
                viewModel = library,
                scanEnabled = false,
                askAiEnabled = false,
                libraryRoot = LibraryRoot(home, onOpenSettings = {}, onResumeCooking = {}, onOpenPlanned = { _, _, _ -> })
            )
        }
    }

    private fun pasteAndGo() {
        compose.onNodeWithText("Link or recipe text").performTextInput("example.com/chicken")
        compose.onNodeWithText("Go").performClick()
        assertEquals("https://example.com/chicken", openedUrl)
    }

    @Test
    fun pasteALinkFromTheSheetClosesItAndOpensTheDialogThatImports() {
        repository.history.value = listOf(RecipeSummary(1, "Adobo", null, null, 1, isSaved = false))
        repository.recent.value = repository.history.value
        show()

        compose.onNodeWithTag("libraryAdd").performClick()
        compose.onNodeWithTag("addPasteLink").performClick()

        compose.onNodeWithTag("addRecipeSheet").assertDoesNotExist()
        pasteAndGo()
    }

    @Test
    fun theEmptyLibrarysPasteTileOpensTheSameDialog() {
        show()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Your recipes go here").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("emptyPasteLink").assertIsDisplayed().performClick()
        pasteAndGo()
    }
}
