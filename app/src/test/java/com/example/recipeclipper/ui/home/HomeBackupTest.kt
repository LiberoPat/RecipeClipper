package com.example.recipeclipper.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.AutoBackupFixture
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeRecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home's two backup offers (#150): "Restore from a backup file" on an empty library, and the
 * one-time "Keep a backup copy?" card once there is a recipe and no folder.
 */
@RunWith(AndroidJUnit4::class)
class HomeBackupTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()
    private val files = FakeBackupFiles()
    private val backup = AutoBackupFixture()

    private fun show(): HomeViewModel {
        val viewModel = HomeViewModel(repository, backup.repository, files, backup.autoBackup)
        compose.setContent {
            HomeScreen(
                onOpenUrl = {}, onOpenRecipe = {}, onOpenRecipes = {}, onOpenLists = {}, onOpenSettings = {},
                viewModel = viewModel
            )
        }
        return viewModel
    }

    private fun scrollTo(text: String) =
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))

    private fun aRecipe() {
        repository.recent.value = listOf(RecipeSummary(1, "Soup", null, null, 1, false))
    }

    @Test
    fun anEmptyLibraryOffersRestoreAndSaysHowItWent() {
        files.files["content://picked"] = "{}"
        backup.repository.importResult = BackupResult.Success(ImportSummary(3, 1, 0, 0))
        val viewModel = show()

        compose.onNodeWithText("Restore from a backup file").assertIsDisplayed()
        // The picker is the system's; the screen hands its answer to the ViewModel.
        compose.runOnIdle { viewModel.onRestorePicked("content://picked") }
        compose.waitForIdle()
        assertEquals(listOf("{}"), backup.repository.importedTexts)
        compose.onNodeWithText("Imported", substring = true).assertIsDisplayed()
    }

    @Test
    fun aLibraryWithRecipesOffersNoRestore() {
        backup.withFolder()
        aRecipe()
        show()

        compose.onNodeWithText("Soup").assertIsDisplayed()
        compose.onNodeWithText("Restore from a backup file").assertDoesNotExist()
    }

    /** The tour's sample alone (#151) is nothing of the user's: restore is still offered, no card. */
    @Test
    fun aLibraryHoldingOnlyTheSampleStillOffersRestoreAndNoFolderCard() {
        repository.sample = 7
        repository.recent.value = listOf(RecipeSummary(7, "Tomato and White Bean Soup", null, null, 1, false))
        show()

        compose.onNodeWithText("Tomato and White Bean Soup").assertIsDisplayed()
        compose.onNodeWithText("Restore from a backup file").assertIsDisplayed()
        compose.onNodeWithText("Keep a backup copy?").assertDoesNotExist()
    }

    @Test
    fun theFolderCardWaitsForARecipeAndNotNowPutsItAway() {
        show()
        compose.onNodeWithText("Keep a backup copy?").assertDoesNotExist()

        aRecipe()
        scrollTo("Keep a backup copy?")
        compose.onNodeWithText("Keep a backup copy?").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()

        compose.onNodeWithText("Keep a backup copy?").assertDoesNotExist()
        assertTrue(backup.store.record.value.folderPromptDone)
    }

    @Test
    fun choosingAFolderFromTheCardKeepsItAndCopies() {
        aRecipe()
        val viewModel = show()

        scrollTo("Keep a backup copy?")
        compose.runOnIdle { viewModel.onBackupFolderPicked(AutoBackupFixture.FOLDER) }
        compose.waitForIdle()
        compose.onNodeWithText("Keep a backup copy?").assertDoesNotExist()
        assertEquals(AutoBackupFixture.FOLDER, backup.store.record.value.folderUri)
        assertEquals(1, backup.scheduler.now)
    }
}
