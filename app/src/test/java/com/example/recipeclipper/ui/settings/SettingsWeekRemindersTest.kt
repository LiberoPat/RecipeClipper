package com.example.recipeclipper.ui.settings

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.MealReminderKind
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Week section's reminder switches and times (#397). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsWeekRemindersTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val preferences = FakeAppPreferences()
    private val flagStore = FakeFeatureFlagStore()
    private val flags = FeatureFlags(flagStore, FlagRegistry.definitions, isDebug = true)

    private fun vm() = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags)

    @Test fun `the evening reminder is on by default at 19 00 and Tonight is off at 16 00`() = runTest(mainDispatcherRule.dispatcher) {
        val state = vm().uiState.value
        assertTrue(state.eveningReminder)
        assertEquals(19 * 60, state.eveningReminderMinute)
        assertFalse(state.tonightReminder)
        assertEquals(16 * 60, state.tonightReminderMinute)
    }

    @Test fun `the Week section is on the index only with the flag`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertTrue(SettingsSection.WEEK in vm.uiState.value.sections)

        flagStore.setOverride(Flag.EVENING_REMINDERS.key, false)
        advanceUntilIdle()
        // Still there for "Show today's meal" (#448), until that flag goes too.
        assertTrue(SettingsSection.WEEK in vm.uiState.value.sections)
        flagStore.setOverride(Flag.TODAY_CARD_CLOSE.key, false)
        advanceUntilIdle()
        assertFalse(SettingsSection.WEEK in vm.uiState.value.sections)
    }

    @Test fun `allowed notifications turn Tonight on`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        vm.onWeekReminderPermission(MealReminderKind.TONIGHT, true)
        advanceUntilIdle()
        assertTrue(preferences.tonightReminder)
        assertTrue(vm.uiState.value.tonightReminder)
        assertFalse(vm.uiState.value.weekRemindersBlocked)
    }

    @Test fun `refused notifications keep the switch off and say why`() = runTest(mainDispatcherRule.dispatcher) {
        preferences.eveningReminder = false
        val vm = vm()
        vm.onWeekReminderPermission(MealReminderKind.EVENING, false)
        advanceUntilIdle()
        assertFalse(preferences.eveningReminder)
        assertFalse(vm.uiState.value.eveningReminder)
        assertTrue(vm.uiState.value.weekRemindersDenied)
        assertTrue(vm.uiState.value.weekRemindersBlocked)

        // A later change to another setting keeps the explanation.
        vm.onDarkWhileCookingChange(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.weekRemindersBlocked)
    }

    @Test fun `a reminder on while the phone blocks notifications says so, until they are allowed`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        vm.onNotificationsAllowed(false)
        assertTrue(vm.uiState.value.weekRemindersBlocked)

        vm.onNotificationsAllowed(true)
        assertFalse(vm.uiState.value.weekRemindersBlocked)
    }

    @Test fun `nothing is said about notifications when no reminder is on`() = runTest(mainDispatcherRule.dispatcher) {
        preferences.eveningReminder = false
        val vm = vm()
        vm.onNotificationsAllowed(false)
        assertFalse(vm.uiState.value.weekRemindersBlocked)
    }

    @Test fun `a refusal is forgotten once the cook has allowed notifications`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        vm.onWeekReminderPermission(MealReminderKind.EVENING, false)
        vm.onNotificationsAllowed(true)
        assertFalse(vm.uiState.value.weekRemindersDenied)
    }

    @Test fun `turning a reminder off needs no asking`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        vm.onWeekReminderOff(MealReminderKind.EVENING)
        advanceUntilIdle()
        assertFalse(preferences.eveningReminder)
        assertFalse(vm.uiState.value.eveningReminder)
    }

    @Test fun `each reminder keeps its own time`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        vm.onWeekReminderTime(MealReminderKind.EVENING, 20 * 60 + 15)
        vm.onWeekReminderTime(MealReminderKind.TONIGHT, 15 * 60)
        advanceUntilIdle()
        assertEquals(20 * 60 + 15, preferences.eveningReminderMinute)
        assertEquals(15 * 60, preferences.tonightReminderMinute)
        assertEquals(20 * 60 + 15, vm.uiState.value.eveningReminderMinute)
        assertEquals(15 * 60, vm.uiState.value.tonightReminderMinute)
    }

    @Test fun `a time outside the day is held to it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        vm.onWeekReminderTime(MealReminderKind.EVENING, 99_999)
        assertEquals(24 * 60 - 1, preferences.eveningReminderMinute)
        vm.onWeekReminderTime(MealReminderKind.EVENING, -5)
        assertEquals(0, preferences.eveningReminderMinute)
    }
}
