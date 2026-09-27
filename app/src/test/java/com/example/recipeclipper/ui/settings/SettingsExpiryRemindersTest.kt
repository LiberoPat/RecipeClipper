package com.example.recipeclipper.ui.settings

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagDefinition
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

/** The Pantry section's "Expiry reminders" switch (#52). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsExpiryRemindersTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val preferences = FakeAppPreferences()
    private val flags = FeatureFlags(
        FakeFeatureFlagStore(),
        listOf(FlagDefinition("mealPlan", "The meal plan", debugDefault = false, releaseDefault = false, issue = 47)),
        isDebug = true
    )

    private fun vm() = SettingsViewModel(preferences, FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags)

    @Test fun `the Pantry section follows the mealPlan flag`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showsPantry)

        flags.set(Flag.MEAL_PLAN, true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showsPantry)
    }

    @Test fun `allowed notifications turn reminders on`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        vm.onExpiryRemindersPermission(true)
        advanceUntilIdle()
        assertTrue(preferences.expiryReminders)
        assertTrue(vm.uiState.value.expiryReminders)
        assertFalse(vm.uiState.value.expiryRemindersDenied)
    }

    @Test fun `refused notifications keep reminders off and say why`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = vm()
        vm.onExpiryRemindersPermission(false)
        advanceUntilIdle()
        assertFalse(preferences.expiryReminders)
        assertEquals(false, vm.uiState.value.expiryReminders)
        assertTrue(vm.uiState.value.expiryRemindersDenied)

        // A later change to another setting keeps the explanation.
        vm.onDarkWhileCookingChange(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.expiryRemindersDenied)
    }

    @Test fun `turning reminders off`() = runTest(mainDispatcherRule.dispatcher) {
        preferences.expiryReminders = true
        val vm = vm()
        assertTrue(vm.uiState.value.expiryReminders)
        vm.onExpiryRemindersOff()
        advanceUntilIdle()
        assertFalse(preferences.expiryReminders)
        assertFalse(vm.uiState.value.expiryReminders)
    }
}
