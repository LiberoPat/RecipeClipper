package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Steps section's "Amounts in steps" switch (#101), over a real ViewModel and fakes (Robolectric). */
@RunWith(AndroidJUnit4::class)
class AmountsInStepsSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val preferences = FakeAppPreferences()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)

    private fun show() {
        val viewModel = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags)
        compose.setContent { SettingsScreen(onBack = {}, viewModel = viewModel) }
    }

    @Test fun hiddenWithoutTheFlag() {
        show()
        compose.onNodeWithText("Amounts in steps").assertDoesNotExist()
    }

    @Test fun offByDefaultAndTurnsOn() {
        flags.set(Flag.AMOUNTS_IN_STEPS, true)
        show()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Amounts in steps", substring = true))
        compose.onNodeWithText("Steps").assertExists()
        val switch = compose.onNode(isToggleable().and(hasText("Amounts in steps", substring = true)))
        switch.assertIsOff().performClick()
        switch.assertIsOn()
        assertTrue(preferences.amountsInSteps)
    }
}
