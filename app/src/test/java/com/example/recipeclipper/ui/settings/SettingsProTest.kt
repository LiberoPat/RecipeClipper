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
import com.example.recipeclipper.data.DefaultProAccess
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings under Pro (#413): the Pro row in place of the free tier's, and the Week reminders,
 * locked but never taken away. A real [SettingsViewModel] over a locked store, with `proUnlock`
 * on (its default). The flag-off app is `SettingsUnlockTest`'s and `SettingsWeekRemindersTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class SettingsProTest {

    @get:Rule
    val compose = createComposeRule()

    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)
    private val entitlements = FakeEntitlements()
    private var proOpened = 0

    private fun vm(preferences: FakeAppPreferences = FakeAppPreferences()) = SettingsViewModel(
        preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags,
        entitlements = entitlements, pro = DefaultProAccess(flags, entitlements)
    )

    private fun showWeek(vm: SettingsViewModel) = compose.setContent {
        SettingsSectionScreen(SettingsSection.WEEK, onBack = {}, viewModel = vm, onOpenPro = { proOpened++ })
    }

    @Test
    fun theProRowTakesTheFreeTiersPlaceEvenWithFreeTierOn() {
        flags.set(Flag.FREE_TIER, true)
        val vm = vm()
        compose.waitForIdle()
        val state = vm.uiState.value
        assertTrue(SettingsSection.PRO in state.sections)
        assertFalse(SettingsSection.UNLIMITED in state.sections)
        assertNull(state.unlock)
        assertFalse(state.isPro)
    }

    @Test
    fun theProRowSaysUnlockedForAnOld107Unlock() {
        entitlements.state.value = UnlockState(unlocked = true)
        val vm = vm()
        compose.setContent { SettingsScreen(onBack = {}, onOpenSection = {}, onOpenDeveloperSettings = {}, viewModel = vm) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Pith Pro"))
        compose.onNode(hasText("Pith Pro") and hasText("Unlocked") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun withTheFlagOffThereIsNoProRow() {
        flags.set(Flag.PRO_UNLOCK, false)
        val vm = vm()
        compose.waitForIdle()
        assertFalse(SettingsSection.PRO in vm.uiState.value.sections)
        assertFalse(vm.uiState.value.remindersLocked)
    }

    @Test
    fun lockedTheDefaultEveningReminderShowsOffWithTheBadgeAndOpensPro() {
        val preferences = FakeAppPreferences() // evening on by default, never set by the cook
        val vm = vm(preferences)
        showWeek(vm)
        assertTrue(vm.uiState.value.remindersLocked)
        assertFalse(vm.uiState.value.eveningReminder)

        compose.onNodeWithTag("reminderSwitch-EVENING").assertIsOff()
        compose.onNodeWithTag("reminderSwitch-EVENING").performClick()
        assertEquals(1, proOpened)
        // Nothing was written: the cook's setting is as it was.
        assertFalse(preferences.eveningReminderChosen)
        compose.onNodeWithTag("reminderSwitch-TONIGHT").performClick()
        assertEquals(2, proOpened)
        assertFalse(preferences.tonightReminder)
    }

    @Test
    fun lockedAReminderTheCookTurnedOnStaysOnAndCanBeTurnedOff() {
        val preferences = FakeAppPreferences(tonightReminder = true, eveningReminderChosen = true)
        val vm = vm(preferences)
        showWeek(vm)
        compose.onNodeWithTag("reminderSwitch-EVENING").assertIsOn()
        compose.onNodeWithTag("reminderSwitch-TONIGHT").assertIsOn()
        compose.onNodeWithTag("reminderTime-TONIGHT").assertIsDisplayed()

        compose.onNodeWithTag("reminderSwitch-TONIGHT").performClick()
        compose.waitForIdle()
        assertFalse(preferences.tonightReminder)
        assertEquals(0, proOpened)
        // Off now, and locked: on again needs Pro.
        compose.onNodeWithTag("reminderSwitch-TONIGHT").performClick()
        assertEquals(1, proOpened)
    }

    @Test
    fun lockedTheViewModelWontTurnAReminderOn() {
        val preferences = FakeAppPreferences(eveningReminder = false, eveningReminderChosen = true)
        val vm = vm(preferences)
        vm.onWeekReminderPermission(MealReminderKind.EVENING, true)
        assertFalse(preferences.eveningReminder)
        assertFalse(vm.uiState.value.eveningReminder)
    }

    @Test
    fun withProTheDefaultIsOnAndTheSwitchesWorkAsBefore() {
        entitlements.state.value = UnlockState(unlocked = true)
        val preferences = FakeAppPreferences()
        val vm = vm(preferences)
        showWeek(vm)
        assertFalse(vm.uiState.value.remindersLocked)
        compose.onNodeWithTag("reminderSwitch-EVENING").assertIsOn()
        vm.onWeekReminderPermission(MealReminderKind.TONIGHT, true)
        compose.waitForIdle()
        assertTrue(preferences.tonightReminder)
        assertEquals(0, proOpened)
    }

    @Test
    fun buyingProWhileThePageIsOpenUnlocksTheReminders() {
        val vm = vm()
        showWeek(vm)
        compose.onNodeWithTag("reminderSwitch-EVENING").assertIsOff()
        entitlements.state.value = UnlockState(unlocked = true)
        compose.waitForIdle()
        compose.onNodeWithTag("reminderSwitch-EVENING").assertIsOn()
    }
}
