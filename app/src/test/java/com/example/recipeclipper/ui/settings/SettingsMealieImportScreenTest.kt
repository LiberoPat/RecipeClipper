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
import com.example.recipeclipper.data.MealieResult
import com.example.recipeclipper.data.backup.MealieSummary
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.remote.MealieError
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeMealieImportRepository
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Import from Mealie row and its result on screen (#391), over a real ViewModel and a fake importer (Robolectric). */
@RunWith(AndroidJUnit4::class)
class SettingsMealieImportScreenTest {

    @get:Rule val compose = createComposeRule()

    private val mealie = FakeMealieImportRepository()

    private fun flags(on: Boolean = true) = FeatureFlags(
        FakeFeatureFlagStore(mapOf(Flag.MEALIE_IMPORT.key to on)), FlagRegistry.definitions, isDebug = false
    )

    private fun viewModel(flags: FeatureFlags = flags()) =
        SettingsViewModel(FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags, mealie = mealie)

    private val summary = MealieSummary(
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
        scrollTo("Import from Mealie")
        compose.onNodeWithText("Import from Mealie").assertIsDisplayed()
        compose.onNodeWithText("Import recipes").assertIsDisplayed()
    }

    @Test fun `the one filing switch is under the rows, on by default, and turns off`() {
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
        compose.onNodeWithText("Import from Mealie").assertDoesNotExist()
    }

    @Test fun `the result says each count that isn't zero, and the pictures`() {
        mealie.result = MealieResult.Success(summary)
        val vm = viewModel()
        vm.onMealiePicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("Imported 12 recipes from Mealie.")
        compose.onNodeWithText(
            "Imported 12 recipes from Mealie. 2 recipes were already here. " +
                "1 entry that isn't a Mealie recipe, or has no name, ingredients or steps, was left out. " +
                "2 pictures couldn't be read and were left out. 3 new lists were made from the categories and tags."
        ).assertIsDisplayed()
    }

    @Test fun `a full free library says how many were left out`() {
        mealie.result = MealieResult.Success(
            MealieSummary(3, 0, skippedForLimit = 40, notRecipes = 0, unreadable = 2, photosLeftOut = 0, freeLimit = 50)
        )
        val vm = viewModel()
        vm.onMealiePicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("Imported 3 recipes")
        compose.onNodeWithText(
            "Imported 3 recipes from Mealie. 40 recipes weren't added: the free app keeps 50 recipes. 2 entries couldn't be read."
        ).assertIsDisplayed()
    }

    @Test fun `nothing new, nothing found and a refused file each have their own sentence`() {
        mealie.result = MealieResult.Success(MealieSummary(0, 5, 0, 0, 0, 0, null))
        val vm = viewModel()
        vm.onMealiePicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("Nothing new")
        compose.onNodeWithText("Nothing new: every recipe in that file is already here.").assertIsDisplayed()
    }

    @Test fun `a refused file shows its cause in words`() {
        mealie.result = MealieResult.Failure(MealieError.NOT_A_MEALIE_FILE)
        val vm = viewModel()
        vm.onMealiePicked("content://picked")
        show(vm)
        compose.waitForIdle()
        scrollTo("isn't a Mealie export")
        compose.onNodeWithText("That isn't a Mealie export. Choose the .zip Mealie exported, or a recipe's .json. To bring in a recipe from a Mealie page, share its link.").assertIsDisplayed()
    }

    @Test fun `tapping the row opens the picker without touching the importer`() {
        show(viewModel())
        compose.waitForIdle()
        scrollTo("Import from Mealie")
        compose.onNodeWithText("Import from Mealie").performClick()
        assertTrue(mealie.imported.isEmpty())
    }
}
