package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.flags.planSettingsLink
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Plan section's "Show today's meal" switch (#448), over a real ViewModel and fakes (Robolectric). */
@RunWith(AndroidJUnit4::class)
class ShowTodayCardSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val preferences = FakeAppPreferences()
    private val flagStore = FakeFeatureFlagStore()
    private val flags = FeatureFlags(flagStore, FlagRegistry.definitions, isDebug = true)

    private fun viewModel() = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags)

    private fun show(viewModel: SettingsViewModel = viewModel()) {
        compose.setContent { SettingsSectionScreen(SettingsSection.WEEK, onBack = {}, viewModel = viewModel) }
    }

    private val switch get() = compose.onNode(isToggleable().and(hasText("Show today's meal", substring = true)))

    @Test fun onByDefaultAndTurnsOff() {
        show()
        compose.onNodeWithText("Today's planned meal", substring = true).assertExists()
        switch.assertIsOn().performClick()
        switch.assertIsOff()
        assertFalse(preferences.showTodayCard)
        switch.performClick()
        assertTrue(preferences.showTodayCard)
    }

    @Test fun followsThePreferenceChangedElsewhere() {
        show()
        preferences.showTodayCard = false
        compose.waitForIdle()
        switch.assertIsOff()
    }

    @Test fun withTheFlagOffThereIsNoSwitch() {
        flagStore.setOverride(Flag.TODAY_CARD_CLOSE.key, false)
        show()
        compose.onNodeWithText("Show today's meal").assertDoesNotExist()
        // The reminders are still there.
        compose.onNodeWithText("Evening reminder").assertExists()
    }

    @Test fun thePlanSectionStaysForTheSwitchAloneWithTheRemindersOff() {
        flagStore.setOverride(Flag.EVENING_REMINDERS.key, false)
        val viewModel = viewModel()
        assertTrue(SettingsSection.WEEK in viewModel.uiState.value.sections)
        assertTrue("the Plan tab still links its page", flags.current.planSettingsLink)
        show(viewModel)
        switch.assertIsOn()
        compose.onNodeWithText("Evening reminder").assertDoesNotExist()
    }
}
