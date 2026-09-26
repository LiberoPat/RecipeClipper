package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
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
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultShortStepRepository
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeShortStepDao
import com.example.recipeclipper.fake.FakeStepShortener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Steps section's "Chef mode" switch (#100), over a real ViewModel and a fake model (Robolectric). */
@RunWith(AndroidJUnit4::class)
class ChefModeSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val preferences = FakeAppPreferences()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
    private val model = FakeStepShortener()

    private fun show() {
        val shortSteps = DefaultShortStepRepository(FakeShortStepDao(), model, Clock { 0 }) { _, e -> throw e }
        val viewModel = SettingsViewModel(
            preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags, shortSteps
        )
        compose.setContent { SettingsScreen(onBack = {}, viewModel = viewModel) }
    }

    private fun switch() = compose.onNode(isToggleable().and(hasText("Chef mode", substring = true)))

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun hiddenWithoutTheFlag() {
        show()
        compose.onNodeWithText("Chef mode").assertDoesNotExist()
    }

    @Test
    fun turnsOnAndNamesTheLanguages() {
        flags.set(Flag.CHEF_MODE, true)
        show()
        scrollTo("For recipes in")
        compose.onNodeWithText("For recipes in English, German.").assertExists()
        switch().assertIsOff().performClick()
        switch().assertIsOn()
        assertTrue(preferences.chefMode)
    }

    @Test
    fun disabledWithAReasonOnAPhoneThatCant() {
        flags.set(Flag.CHEF_MODE, true)
        model.support = ChefSupport.Unsupported
        show()
        scrollTo("Needs a phone with Google's on-device AI, such as a Pixel 9 or newer or a Galaxy S25 or newer.")
        switch().assertIsOff().assertIsNotEnabled().performClick()
        assertFalse(preferences.chefMode)
    }
}
