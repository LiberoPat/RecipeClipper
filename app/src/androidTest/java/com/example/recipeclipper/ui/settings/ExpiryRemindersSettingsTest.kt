package com.example.recipeclipper.ui.settings

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isToggleable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Pantry section's "Expiry reminders" switch (#52), over a real ViewModel and fakes. */
@RunWith(AndroidJUnit4::class)
class ExpiryRemindersSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val preferences = FakeAppPreferences()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun allowNotifications() {
        // Granted up front, so turning the switch on needs no system dialog.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    private fun show() {
        viewModel = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags)
        compose.setContent { SettingsScreen(onBack = {}, viewModel = viewModel) }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun hiddenWithoutTheMealPlanFlag() {
        show()
        compose.onNodeWithText("Expiry reminders").assertDoesNotExist()
    }

    @Test
    fun turnsOnWhenNotificationsAreAllowed() {
        flags.set(Flag.MEAL_PLAN, true)
        show()
        scrollTo("Expiry reminders")
        val switch = compose.onNode(isToggleable().and(hasText("Expiry reminders", substring = true)))
        switch.assertIsDisplayed().assertIsOff()

        switch.performClick()

        switch.assertIsOn()
        assertTrue(preferences.expiryReminders)

        switch.performClick()

        switch.assertIsOff()
        assertFalse(preferences.expiryReminders)
    }

    @Test
    fun aRefusalExplainsAndStaysOff() {
        flags.set(Flag.MEAL_PLAN, true)
        show()
        compose.runOnIdle { viewModel.onExpiryRemindersPermission(false) }
        scrollTo("Notifications are off")
        compose.onNodeWithText("Notifications are off", substring = true).assertIsDisplayed()
        compose.onNode(isToggleable().and(hasText("Expiry reminders", substring = true)))
            .assertIsOff()
        assertFalse(preferences.expiryReminders)
    }
}
