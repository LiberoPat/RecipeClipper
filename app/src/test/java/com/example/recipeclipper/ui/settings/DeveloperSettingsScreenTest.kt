package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Developer settings (#87) over the real flags.json in the APK and a fake store. */
@RunWith(AndroidJUnit4::class)
class DeveloperSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)

    private fun show() {
        val viewModel = DeveloperSettingsViewModel(flags)
        compose.setContent { DeveloperSettingsScreen(onBack = {}, viewModel = viewModel) }
    }

    @Test
    fun aSwitchPerFlagTurnsItOffAndResetTurnsItBack() {
        show()
        // On by default, like every flag without a known bug.
        val mealPlan = compose.onNodeWithText("mealPlan")
        mealPlan.assertIsDisplayed().assertIsOn()

        mealPlan.performClick()

        mealPlan.assertIsOff()
        assertFalse(flags.isOn(Flag.MEAL_PLAN))
        compose.onNode(hasText("Changed from the default", substring = true)).assertIsDisplayed()

        compose.onNodeWithText("Reset to defaults").performClick()

        mealPlan.assertIsOn()
        assertTrue(flags.isOn(Flag.MEAL_PLAN))
    }
}
