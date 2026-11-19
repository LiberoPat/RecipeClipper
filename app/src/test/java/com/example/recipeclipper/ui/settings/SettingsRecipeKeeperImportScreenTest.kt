package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.PaprikaResult
import com.example.recipeclipper.data.backup.PaprikaSummary
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.remote.PaprikaError
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakePaprikaImportRepository
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Import from Recipe Keeper row and its result on screen (#390), over a real ViewModel and a fake importer (Robolectric). */
@RunWith(AndroidJUnit4::class)
class SettingsRecipeKeeperImportScreenTest {

    @get:Rule val compose = createComposeRule()

    private val importer = FakePaprikaImportRepository()

    private fun flags(keeper: Boolean = true, paprika: Boolean = true) = FeatureFlags(
        FakeFeatureFlagStore(mapOf(Flag.RECIPE_KEEPER_IMPORT.key to keeper, Flag.PAPRIKA_IMPORT.key to paprika)),
        FlagRegistry.definitions, isDebug = false
    )

    private fun viewModel(flags: FeatureFlags = flags()) =
        SettingsViewModel(FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags, paprika = importer)

    private fun show(viewModel: SettingsViewModel) {
        compose.setContent { SettingsSectionScreen(SettingsSection.YOUR_RECIPES, onBack = {}, viewModel = viewModel) }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test fun `the row sits beside Import from Paprika, with the one filing switch`() {
        show(viewModel())
        compose.waitForIdle()
        scrollTo("Import from Recipe Keeper")
        compose.onNodeWithText("Import from Recipe Keeper").assertIsDisplayed()
        compose.onNodeWithText("Import from Paprika").assertIsDisplayed()
        compose.onNodeWithText("Put recipes in lists by their categories").assertIsDisplayed()
    }

    @Test fun `with its flag off there is no row, with both off no switch either`() {
        show(viewModel(flags(keeper = false)))
        compose.waitForIdle()
        scrollTo("Import from Paprika")
        compose.onNodeWithText("Import from Recipe Keeper").assertDoesNotExist()
        compose.onNodeWithText("Put recipes in lists by their categories").assertIsDisplayed()
    }

    @Test fun `with both flags off neither row nor switch shows`() {
        show(viewModel(flags(keeper = false, paprika = false)))
        compose.waitForIdle()
        compose.onNodeWithText("Import recipes").assertIsDisplayed()
        compose.onNodeWithText("Import from Recipe Keeper").assertDoesNotExist()
        compose.onNodeWithText("Import from Paprika").assertDoesNotExist()
        compose.onNodeWithText("Put recipes in lists by their categories").assertDoesNotExist()
    }

    @Test fun `the result says each count that isn't zero, in Recipe Keeper's words`() {
        importer.result = PaprikaResult.Success(
            PaprikaSummary(
                imported = 7, alreadyHere = 1, skippedForLimit = 0, notRecipes = 2, unreadable = 0,
                photosLeftOut = 1, freeLimit = null, listsMade = 4
            )
        )
        val vm = viewModel()
        vm.onRecipeKeeperPicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("Imported 7 recipes from Recipe Keeper.")
        compose.onNodeWithText(
            "Imported 7 recipes from Recipe Keeper. 1 recipe was already here. " +
                "2 entries with no name, or with no ingredients or steps, were left out. " +
                "1 picture couldn't be read and was left out. 4 new lists were made from the categories."
        ).assertIsDisplayed()
    }

    @Test fun `a refused file shows its cause in words`() {
        importer.result = PaprikaResult.Failure(PaprikaError.NOT_A_RECIPE_KEEPER_FILE)
        val vm = viewModel()
        vm.onRecipeKeeperPicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("isn't a Recipe Keeper export")
        compose.onNodeWithText("That isn't a Recipe Keeper export. Choose the .zip file Recipe Keeper made.").assertIsDisplayed()
    }

    @Test fun `a file far too large says so in Recipe Keeper's words`() {
        importer.result = PaprikaResult.Failure(PaprikaError.TOO_LARGE)
        val vm = viewModel()
        vm.onRecipeKeeperPicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("Recipe Keeper export should be")
        compose.onNodeWithText("That file is far larger than a Recipe Keeper export should be. Nothing was imported.").assertIsDisplayed()
    }

    @Test fun `tapping the row opens the picker without touching the importer`() {
        show(viewModel())
        compose.waitForIdle()
        scrollTo("Import from Recipe Keeper")
        compose.onNodeWithText("Import from Recipe Keeper").performClick()
        assertTrue(importer.imported.isEmpty())
    }
}
