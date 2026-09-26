package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's one settings surface (#31): the three [UnitSystem] radio rows, "Also convert
 * liquids" shown for Ounces only, the three [TemperatureUnit] radio rows (independent of unit
 * system), "Dark while cooking", the "Your recipes" export/import rows, and the version's
 * hidden 7-tap gesture into Developer settings (#87). Every control writes straight through to
 * [FakeAppPreferences], the same fake `SettingsViewModelTest` uses over the real ViewModel.
 *
 * "As written" is both a unit and an oven-temperature label, so those two rows are told apart
 * by their (also unique) description text rather than by title alone.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Taps {
        var developerSettingsOpened = 0
        var tourShown = 0
    }

    private fun show(
        preferences: FakeAppPreferences = FakeAppPreferences(),
        appInfo: FakeAppInfo = FakeAppInfo()
    ): Taps {
        val taps = Taps()
        // Built outside setContent, over the shared fakes, like every other screen's tests.
        val viewModel = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), appInfo)
        compose.setContent {
            SettingsScreen(
                onBack = {},
                onOpenDeveloperSettings = { taps.developerSettingsOpened++ },
                onShowTour = { taps.tourShown++ },
                viewModel = viewModel
            )
        }
        return taps
    }

    /** The list is lazy: a row below the fold isn't composed until the list scrolls to it. */
    private fun scrollTo(text: String) =
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

    private fun asWrittenUnitRow() =
        compose.onNode(hasText("As written") and hasText("Exactly the units the recipe uses"))

    private fun asWrittenOvenRow() =
        compose.onNode(hasText("As written") and hasText("Exactly the temperature the recipe uses"))

    // --- Units ---

    @Test
    fun unitSystemDefaultsToAsWritten() {
        show()

        asWrittenUnitRow().assertIsSelected()
        compose.onNodeWithText("Metric").assertIsNotSelected()
        compose.onNodeWithText("Ounces").assertIsNotSelected()
    }

    @Test
    fun tappingMetricSelectsItAndWritesThrough() {
        val preferences = FakeAppPreferences()
        show(preferences)

        compose.onNodeWithText("Metric").performClick()

        compose.onNodeWithText("Metric").assertIsSelected()
        asWrittenUnitRow().assertIsNotSelected()
        assertEquals(UnitSystem.METRIC, preferences.unitSystem)
    }

    @Test
    fun tappingOuncesSelectsItAndWritesThrough() {
        val preferences = FakeAppPreferences()
        show(preferences)

        compose.onNodeWithText("Ounces").performClick()

        compose.onNodeWithText("Ounces").assertIsSelected()
        assertEquals(UnitSystem.OUNCES, preferences.unitSystem)
    }

    @Test
    fun alsoConvertLiquidsShowsOnlyForOunces() {
        show()
        compose.onNodeWithText("Also convert liquids").assertDoesNotExist()

        compose.onNodeWithText("Metric").performClick()
        compose.onNodeWithText("Also convert liquids").assertDoesNotExist()

        compose.onNodeWithText("Ounces").performClick()
        compose.onNodeWithText("Also convert liquids").assertIsDisplayed()
    }

    @Test
    fun convertLiquidsSwitchWritesThrough() {
        val preferences = FakeAppPreferences(unitSystem = UnitSystem.OUNCES)
        show(preferences)
        val switchRow = compose.onNodeWithText("Also convert liquids")
        switchRow.assertIsOff()

        switchRow.performClick()

        switchRow.assertIsOn()
        assertTrue(preferences.convertLiquids)
    }

    // --- Oven temperature ---

    @Test
    fun ovenTemperatureDefaultsToAsWritten() {
        show()

        asWrittenOvenRow().assertIsSelected()
        compose.onNodeWithText("Celsius (°C)").assertIsNotSelected()
        compose.onNodeWithText("Fahrenheit (°F)").assertIsNotSelected()
    }

    @Test
    fun tappingCelsiusSelectsItAndWritesThroughIndependentlyOfUnitSystem() {
        val preferences = FakeAppPreferences()
        show(preferences)

        compose.onNodeWithText("Celsius (°C)").performClick()

        compose.onNodeWithText("Celsius (°C)").assertIsSelected()
        assertEquals(TemperatureUnit.CELSIUS, preferences.temperatureUnit)
        // Independent: choosing an oven temperature must not touch the unit system.
        assertEquals(UnitSystem.AS_WRITTEN, preferences.unitSystem)
        asWrittenUnitRow().assertIsSelected()
    }

    @Test
    fun tappingFahrenheitSelectsItAndWritesThrough() {
        val preferences = FakeAppPreferences()
        show(preferences)

        compose.onNodeWithText("Fahrenheit (°F)").performClick()

        compose.onNodeWithText("Fahrenheit (°F)").assertIsSelected()
        assertEquals(TemperatureUnit.FAHRENHEIT, preferences.temperatureUnit)
    }

    // --- Appearance ---

    @Test
    fun darkWhileCookingSwitchTogglesAndWritesThrough() {
        val preferences = FakeAppPreferences()
        show(preferences)
        val switchRow = compose.onNodeWithText("Dark while cooking")
        switchRow.assertIsOff()

        switchRow.performClick()

        switchRow.assertIsOn()
        assertTrue(preferences.darkWhileCooking)
    }

    // --- Your recipes ---

    @Test
    fun yourRecipesSectionShowsExportAndImportRows() {
        show()

        scrollTo("Export recipes")
        compose.onNodeWithText("Export recipes").assertIsDisplayed()
        scrollTo("Import recipes")
        compose.onNodeWithText("Import recipes").assertIsDisplayed()
    }

    // --- Developer settings (#87) ---

    /** "Show the tour again" (#151): an action row under Help. */
    @Test
    fun showTheTourAgainIsAnActionUnderHelp() {
        val taps = show()
        scrollTo("Show the tour again")
        compose.onNodeWithText("Help").assertExists()
        compose.onNodeWithText("The welcome cards, and a tip on each screen once more.").assertExists()
        compose.onNodeWithText("Show the tour again").performClick()
        assertEquals(1, taps.tourShown)
    }

    @Test
    fun theVersionShowsAndItsSeventhTapOpensDeveloperSettingsThenCountsAgain() {
        val taps = show(appInfo = FakeAppInfo(appVersion = "2.3 (7)"))
        // At the foot of the list; scroll to it before the first assertion or interaction.
        scrollTo("Version 2.3 (7)")
        val version = compose.onNodeWithText("Version 2.3 (7)")
        version.assertIsDisplayed()

        repeat(SettingsViewModel.DEVELOPER_TAPS - 1) { version.performClick() }
        assertEquals(0, taps.developerSettingsOpened)

        version.performClick()
        assertEquals(1, taps.developerSettingsOpened)

        // The count starts over: six more taps do nothing, the seventh opens it again.
        repeat(SettingsViewModel.DEVELOPER_TAPS - 1) { version.performClick() }
        assertEquals(1, taps.developerSettingsOpened)

        version.performClick()
        assertEquals(2, taps.developerSettingsOpened)
    }
}
