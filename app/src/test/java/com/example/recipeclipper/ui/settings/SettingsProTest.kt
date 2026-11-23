package com.example.recipeclipper.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.R
import com.example.recipeclipper.data.DefaultProAccess
import com.example.recipeclipper.data.ProExtra
import com.example.recipeclipper.data.UnlockState
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.MealReminderKind
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeProAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings and Pro (#413). Pro adds nothing today (owner, 2026-11-23: the Week extras are free, Pro
 * is kept for future AI features), so there is no Pro row; the first extra brings it back. And the
 * Week reminders are free for everyone. A real [SettingsViewModel] with `proUnlock` on (its default).
 * The flag-off app is `SettingsUnlockTest`'s and `SettingsWeekRemindersTest`'s. iOS: `ProTests`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsProTest {

    @get:Rule
    val compose = createComposeRule()

    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)
    private val entitlements = FakeEntitlements()
    private val testExtra = ProExtra(R.string.action_move, R.string.action_remove_from_plan)

    private fun vm(preferences: FakeAppPreferences = FakeAppPreferences()) = SettingsViewModel(
        preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags,
        entitlements = entitlements, pro = DefaultProAccess(flags, entitlements)
    )

    private fun vmWithAnExtra() = SettingsViewModel(
        FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags,
        entitlements = entitlements, pro = FakeProAccess(flags, entitlements, listOf(testExtra))
    )

    private fun showWeek(vm: SettingsViewModel) = compose.setContent {
        SettingsSectionScreen(SettingsSection.WEEK, onBack = {}, viewModel = vm)
    }

    @Test
    fun withNoProExtrasThereIsNoProRowEvenWithTheFlagOn() {
        flags.set(Flag.FREE_TIER, true)
        val vm = vm()
        compose.waitForIdle()
        assertTrue(flags.isOn(Flag.PRO_UNLOCK))
        assertFalse(SettingsSection.PRO in vm.uiState.value.sections)
        assertFalse(vm.uiState.value.showsPro)
    }

    @Test
    fun theProRowComesBackWhenProAddsSomething() {
        val vm = vmWithAnExtra()
        compose.waitForIdle()
        val state = vm.uiState.value
        assertTrue(state.showsPro)
        assertTrue(SettingsSection.PRO in state.sections)
        assertFalse(SettingsSection.UNLIMITED in state.sections)
        assertNull(state.unlock)
        assertFalse(state.isPro)
    }

    @Test
    fun theProRowSaysUnlockedForAnOld107Unlock() {
        entitlements.state.value = UnlockState(unlocked = true)
        val vm = vmWithAnExtra()
        compose.setContent { SettingsScreen(onBack = {}, onOpenSection = {}, onOpenDeveloperSettings = {}, viewModel = vm) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Pith Pro"))
        compose.onNode(hasText("Pith Pro") and hasText("Unlocked") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun withTheFlagOffThereIsNoProRowEvenWithAnExtra() {
        flags.set(Flag.PRO_UNLOCK, false)
        val vm = vmWithAnExtra()
        compose.waitForIdle()
        assertFalse(SettingsSection.PRO in vm.uiState.value.sections)
    }

    @Test
    fun theRemindersAreFreeTheDefaultEveningOneIsOnAndNothingCarriesABadge() {
        val preferences = FakeAppPreferences() // evening on by default, never set by the cook
        val vm = vm(preferences)
        showWeek(vm)
        assertTrue(vm.uiState.value.eveningReminder)
        compose.onNodeWithTag("reminderSwitch-EVENING").assertIsOn()
        compose.onNodeWithTag("reminderSwitch-TONIGHT").assertIsOff()
        compose.onNodeWithTag("proBadge", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun anyoneCanTurnAReminderOnAndOffWithNoProScreen() {
        val preferences = FakeAppPreferences()
        val vm = vm(preferences)
        showWeek(vm)
        compose.onNodeWithTag("reminderSwitch-TONIGHT").performClick()
        compose.waitForIdle()
        assertTrue(preferences.tonightReminder)
        compose.onNodeWithTag("reminderSwitch-TONIGHT").assertIsOn()
        compose.onNodeWithTag("reminderSwitch-TONIGHT").performClick()
        compose.waitForIdle()
        assertFalse(preferences.tonightReminder)
    }

    @Test
    fun theViewModelTurnsAReminderOnWithoutPro() {
        val preferences = FakeAppPreferences(eveningReminder = false)
        val vm = vm(preferences)
        vm.onWeekReminderPermission(MealReminderKind.EVENING, true)
        assertTrue(preferences.eveningReminder)
        assertTrue(vm.uiState.value.eveningReminder)
    }
}
