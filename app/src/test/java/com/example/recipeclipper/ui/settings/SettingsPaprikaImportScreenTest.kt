package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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

/** The Import from Paprika row and its result on screen (#382), over a real ViewModel and a fake importer (Robolectric). */
@RunWith(AndroidJUnit4::class)
class SettingsPaprikaImportScreenTest {

    @get:Rule val compose = createComposeRule()

    private val paprika = FakePaprikaImportRepository()

    private fun flags(on: Boolean = true) = FeatureFlags(
        FakeFeatureFlagStore(mapOf(Flag.PAPRIKA_IMPORT.key to on)), FlagRegistry.definitions, isDebug = false
    )

    private fun viewModel(flags: FeatureFlags = flags()) =
        SettingsViewModel(FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags, paprika = paprika)

    private val summary = PaprikaSummary(
        imported = 12, alreadyHere = 2, skippedForLimit = 0, notRecipes = 1, unreadable = 0,
        photosLeftOut = 2, freeLimit = null, listsMade = 3
    )

    private fun show(viewModel: SettingsViewModel) {
        compose.setContent { SettingsSectionScreen(SettingsSection.YOUR_RECIPES, onBack = {}, viewModel = viewModel) }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test fun `the row sits beside Export and Import, and is gone with the flag off`() {
        show(viewModel())
        compose.waitForIdle()
        scrollTo("Import from Paprika")
        compose.onNodeWithText("Import from Paprika").assertIsDisplayed()
        compose.onNodeWithText("Import recipes").assertIsDisplayed()
    }

    @Test fun `the filing switch is under the row, on by default, and turns off`() {
        show(viewModel())
        compose.waitForIdle()
        scrollTo("Put recipes in lists by their categories")
        compose.onNodeWithText("Put recipes in lists by their categories").assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.isToggleable() and hasText("Put recipes in lists", substring = true))
            .assertIsOn()
            .performClick()
            .assertIsOff()
    }

    @Test fun `with the flag off there is no row`() {
        show(viewModel(flags(on = false)))
        compose.waitForIdle()
        compose.onNodeWithText("Import recipes").assertIsDisplayed()
        compose.onNodeWithText("Import from Paprika").assertDoesNotExist()
    }

    @Test fun `the result says each count that isn't zero, and the pictures`() {
        paprika.result = PaprikaResult.Success(summary)
        val vm = viewModel()
        vm.onPaprikaPicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("Imported 12 recipes from Paprika.")
        compose.onNodeWithText(
            "Imported 12 recipes from Paprika. 2 recipes were already here. " +
                "1 entry with no name, or with no ingredients or steps, was left out. " +
                "2 pictures couldn't be read and were left out. 3 new lists were made from the categories."
        ).assertIsDisplayed()
    }

    @Test fun `a full free library says how many were left out`() {
        paprika.result = PaprikaResult.Success(
            PaprikaSummary(3, 0, skippedForLimit = 40, notRecipes = 0, unreadable = 2, photosLeftOut = 0, freeLimit = 50)
        )
        val vm = viewModel()
        vm.onPaprikaPicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("Imported 3 recipes")
        compose.onNodeWithText(
            "Imported 3 recipes from Paprika. 40 recipes weren't added: the free app keeps 50 recipes. 2 entries couldn't be read."
        ).assertIsDisplayed()
    }

    @Test fun `nothing new, nothing found and a refused file each have their own sentence`() {
        paprika.result = PaprikaResult.Success(PaprikaSummary(0, 5, 0, 0, 0, 0, null))
        val vm = viewModel()
        vm.onPaprikaPicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("Nothing new")
        compose.onNodeWithText("Nothing new: every recipe in that file is already here.").assertIsDisplayed()
    }

    @Test fun `a refused file shows its cause in words`() {
        paprika.result = PaprikaResult.Failure(PaprikaError.NOT_A_PAPRIKA_FILE)
        val vm = viewModel()
        vm.onPaprikaPicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("isn't a Paprika export")
        compose.onNodeWithText("That isn't a Paprika export. Choose a .paprikarecipes file.").assertIsDisplayed()
    }

    @Test fun `tapping the row opens the picker without touching the importer`() {
        show(viewModel())
        compose.waitForIdle()
        scrollTo("Import from Paprika")
        compose.onNodeWithText("Import from Paprika").performClick()
        assertTrue(paprika.imported.isEmpty())
    }
}
