package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
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
 * Settings (#31): the index (#286), one row per section with its current value, opening the
 * section's page, and the version's hidden 7-tap gesture into Developer settings (#87); then each
 * page's controls: the three [UnitSystem] radio rows, "Also convert liquids" for Ounces only, the
 * three [TemperatureUnit] radio rows (independent of unit system), "Dark while cooking", the
 * "Your recipes" export/import rows and Help's "Show tips again". Every control writes straight
 * through to [FakeAppPreferences], the same fake `SettingsViewModelTest` uses over the real ViewModel.
 *
 * "As written" is both a unit and an oven-temperature label, so those rows are told apart by
 * their description (on a page) or their section's name (on the index).
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Taps {
        var developerSettingsOpened = 0
        var tipsShown = 0
        val opened = mutableListOf<SettingsSection>()
    }

    private fun viewModel(preferences: FakeAppPreferences, appInfo: FakeAppInfo = FakeAppInfo()) =
        // Built outside setContent, over the shared fakes, like every other screen's tests.
        SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), appInfo)

    private fun showIndex(
        preferences: FakeAppPreferences = FakeAppPreferences(),
        appInfo: FakeAppInfo = FakeAppInfo()
    ): Taps {
        val taps = Taps()
        val viewModel = viewModel(preferences, appInfo)
        compose.setContent {
            SettingsScreen(
                onBack = {},
                onOpenSection = { taps.opened += it },
                onOpenDeveloperSettings = { taps.developerSettingsOpened++ },
                viewModel = viewModel
            )
        }
        return taps
    }

    private fun showPage(section: SettingsSection, preferences: FakeAppPreferences = FakeAppPreferences()): Taps {
        val taps = Taps()
        val viewModel = viewModel(preferences)
        compose.setContent {
            SettingsSectionScreen(section, onBack = {}, onShowTips = { taps.tipsShown++ }, viewModel = viewModel)
        }
        return taps
    }

    /** The lists are lazy: a row below the fold isn't composed until the list scrolls to it. */
    private fun scrollTo(text: String) =
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

    /** An index row: the section's name and its value, one button. */
    private fun indexRow(name: String, value: String) =
        compose.onNode(hasText(name) and hasText(value) and hasClickAction())

    private fun asWrittenUnitRow() =
        compose.onNode(hasText("As written") and hasText("Exactly the units the recipe uses"))

    private fun asWrittenOvenRow() =
        compose.onNode(hasText("As written") and hasText("Exactly the temperature the recipe uses"))

    // --- The index (#286) ---

    @Test
    fun theIndexShowsEachSectionWithItsValue() {
        showIndex()

        indexRow("Units", "As written").assertIsDisplayed()
        indexRow("Oven temperature", "As written").assertIsDisplayed()
        // Dark mode is the system's call; Appearance's one choice is cook mode's.
        indexRow("Appearance", "System theme").assertIsDisplayed()
        scrollTo("Steps")
        indexRow("Steps", "Amounts off").assertIsDisplayed()
        scrollTo("Pantry")
        indexRow("Pantry", "Reminders off").assertIsDisplayed()
        scrollTo("Your recipes")
        compose.onNode(hasText("Your recipes") and hasClickAction()).assertIsDisplayed()
        scrollTo("Help")
        compose.onNode(hasText("Help") and hasClickAction()).assertIsDisplayed()
        // Unlimited recipes is the free tier's, whose flag is off.
        compose.onNodeWithText("Unlimited recipes").assertDoesNotExist()
    }

    @Test
    fun theValuesFollowTheSettingsAsTheyChange() {
        val preferences = FakeAppPreferences(unitSystem = UnitSystem.METRIC)
        showIndex(preferences)
        indexRow("Units", "Metric").assertIsDisplayed()

        // A page's change arrives through the preferences, as it does coming back from that page.
        preferences.unitSystem = UnitSystem.OUNCES
        preferences.temperatureUnit = TemperatureUnit.CELSIUS
        preferences.darkWhileCooking = true
        preferences.amountsInSteps = true
        preferences.expiryReminders = true

        indexRow("Units", "Ounces").assertIsDisplayed()
        indexRow("Oven temperature", "Celsius (°C)").assertIsDisplayed()
        indexRow("Appearance", "Dark while cooking").assertIsDisplayed()
        scrollTo("Steps")
        indexRow("Steps", "Amounts on").assertIsDisplayed()
        scrollTo("Pantry")
        indexRow("Pantry", "Reminders on").assertIsDisplayed()
    }

    @Test
    fun aRowOpensItsSectionsPage() {
        val taps = showIndex()

        indexRow("Oven temperature", "As written").performClick()
        scrollTo("Help")
        compose.onNode(hasText("Help") and hasClickAction()).performClick()

        assertEquals(listOf(SettingsSection.OVEN_TEMPERATURE, SettingsSection.HELP), taps.opened)
    }

    @Test
    fun theVersionShowsAndItsSeventhTapOpensDeveloperSettingsThenCountsAgain() {
        val taps = showIndex(appInfo = FakeAppInfo(appVersion = "2.3 (7)"))
        // At the foot of the index; scroll to it before the first assertion or interaction.
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

    // --- Units ---

    @Test
    fun unitSystemDefaultsToAsWritten() {
        showPage(SettingsSection.UNITS)

        compose.onNodeWithText("Units").assertIsDisplayed()
        asWrittenUnitRow().assertIsSelected()
        compose.onNodeWithText("Metric").assertIsNotSelected()
        compose.onNodeWithText("Ounces").assertIsNotSelected()
    }

    @Test
    fun tappingMetricSelectsItAndWritesThrough() {
        val preferences = FakeAppPreferences()
        showPage(SettingsSection.UNITS, preferences)

        compose.onNodeWithText("Metric").performClick()

        compose.onNodeWithText("Metric").assertIsSelected()
        asWrittenUnitRow().assertIsNotSelected()
        assertEquals(UnitSystem.METRIC, preferences.unitSystem)
    }

    @Test
    fun tappingOuncesSelectsItAndWritesThrough() {
        val preferences = FakeAppPreferences()
        showPage(SettingsSection.UNITS, preferences)

        compose.onNodeWithText("Ounces").performClick()

        compose.onNodeWithText("Ounces").assertIsSelected()
        assertEquals(UnitSystem.OUNCES, preferences.unitSystem)
    }

    @Test
    fun alsoConvertLiquidsShowsOnlyForOunces() {
        showPage(SettingsSection.UNITS)
        compose.onNodeWithText("Also convert liquids").assertDoesNotExist()

        compose.onNodeWithText("Metric").performClick()
        compose.onNodeWithText("Also convert liquids").assertDoesNotExist()

        compose.onNodeWithText("Ounces").performClick()
        compose.onNodeWithText("Also convert liquids").assertIsDisplayed()
    }

    @Test
    fun convertLiquidsSwitchWritesThrough() {
        val preferences = FakeAppPreferences(unitSystem = UnitSystem.OUNCES)
        showPage(SettingsSection.UNITS, preferences)
        val switchRow = compose.onNodeWithText("Also convert liquids")
        switchRow.assertIsOff()

        switchRow.performClick()

        switchRow.assertIsOn()
        assertTrue(preferences.convertLiquids)
    }

    // --- Oven temperature ---

    @Test
    fun ovenTemperatureDefaultsToAsWritten() {
        showPage(SettingsSection.OVEN_TEMPERATURE)

        asWrittenOvenRow().assertIsSelected()
        compose.onNodeWithText("Celsius (°C)").assertIsNotSelected()
        compose.onNodeWithText("Fahrenheit (°F)").assertIsNotSelected()
    }

    @Test
    fun tappingCelsiusSelectsItAndWritesThroughIndependentlyOfUnitSystem() {
        val preferences = FakeAppPreferences()
        showPage(SettingsSection.OVEN_TEMPERATURE, preferences)

        compose.onNodeWithText("Celsius (°C)").performClick()

        compose.onNodeWithText("Celsius (°C)").assertIsSelected()
        assertEquals(TemperatureUnit.CELSIUS, preferences.temperatureUnit)
        // Independent: choosing an oven temperature must not touch the unit system.
        assertEquals(UnitSystem.AS_WRITTEN, preferences.unitSystem)
    }

    @Test
    fun tappingFahrenheitSelectsItAndWritesThrough() {
        val preferences = FakeAppPreferences()
        showPage(SettingsSection.OVEN_TEMPERATURE, preferences)

        compose.onNodeWithText("Fahrenheit (°F)").performClick()

        compose.onNodeWithText("Fahrenheit (°F)").assertIsSelected()
        assertEquals(TemperatureUnit.FAHRENHEIT, preferences.temperatureUnit)
    }

    // --- Appearance ---

    @Test
    fun darkWhileCookingSwitchTogglesAndWritesThrough() {
        val preferences = FakeAppPreferences()
        showPage(SettingsSection.APPEARANCE, preferences)
        val switchRow = compose.onNodeWithText("Dark while cooking")
        switchRow.assertIsOff()

        switchRow.performClick()

        switchRow.assertIsOn()
        assertTrue(preferences.darkWhileCooking)
    }

    // --- Your recipes ---

    @Test
    fun yourRecipesPageShowsExportAndImportRows() {
        showPage(SettingsSection.YOUR_RECIPES)

        scrollTo("Export recipes")
        compose.onNodeWithText("Export recipes").assertIsDisplayed()
        scrollTo("Import recipes")
        compose.onNodeWithText("Import recipes").assertIsDisplayed()
    }

    // --- Help ---

    /** "Show tips again" (#190): an action row, Help's one. */
    @Test
    fun showTipsAgainIsAnActionOnHelpsPage() {
        val taps = showPage(SettingsSection.HELP)
        compose.onNodeWithText("Help").assertExists()
        compose.onNodeWithText("Each tip shows once more, one at a time.").assertExists()
        compose.onNodeWithText("Show tips again").performClick()
        assertEquals(1, taps.tipsShown)
    }
}
