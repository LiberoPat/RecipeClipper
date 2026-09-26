package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
    fun aSwitchPerFlagTurnsItOnAndResetTurnsItBack() {
        show()
        val mealPlan = compose.onNodeWithText("mealPlan")
        mealPlan.assertIsDisplayed().assertIsOff()

        mealPlan.performClick()

        mealPlan.assertIsOn()
        assertTrue(flags.isOn(Flag.MEAL_PLAN))
        compose.onNode(hasText("Changed from the default", substring = true)).assertIsDisplayed()

        // With enough flags the button is below the fold of the lazy list.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Reset to defaults"))
        compose.onNodeWithText("Reset to defaults").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("mealPlan"))

        mealPlan.assertIsOff()
        assertFalse(flags.isOn(Flag.MEAL_PLAN))
    }
}
