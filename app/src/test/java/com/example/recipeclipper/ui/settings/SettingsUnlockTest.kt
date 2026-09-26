package com.example.recipeclipper.ui.settings

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.UnlockState
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeBackupRepository
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Settings' "Unlimited recipes" row and Developer settings' "Unlocked" override (#107). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsUnlockTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
    private val entitlements = FakeEntitlements(UnlockState(price = "€2.99"))

    private fun settings() = SettingsViewModel(
        FakeAppPreferences(), FakeBackupRepository(), FakeBackupFiles(), FakeAppInfo(), flags,
        entitlements = entitlements
    )

    @Test fun `no row while the free tier is off`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = settings()
        advanceUntilIdle()
        assertNull(vm.uiState.value.unlock)
    }

    @Test fun `the row offers the store's price, and unlocks`() = runTest(mainDispatcherRule.dispatcher) {
        flags.set(Flag.FREE_TIER, true)
        val vm = settings()
        advanceUntilIdle()
        assertEquals(UnlockRow(unlocked = false, price = "€2.99"), vm.uiState.value.unlock)

        vm.onUnlock()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.unlock!!.unlocked)
        assertFalse(vm.uiState.value.unlock!!.busy)
        assertNull(vm.uiState.value.unlockNotice)
    }

    @Test fun `a restore with nothing to restore says so`() = runTest(mainDispatcherRule.dispatcher) {
        flags.set(Flag.FREE_TIER, true)
        val vm = settings()
        advanceUntilIdle()
        vm.onRestore()
        advanceUntilIdle()
        assertEquals(1, entitlements.restores)
        assertEquals(PurchaseOutcome.NOTHING_TO_RESTORE, vm.uiState.value.unlockNotice)
        assertFalse(vm.uiState.value.unlock!!.unlocked)
    }

    @Test fun `the developer override shows as unlocked, and Reset clears it`() = runTest(mainDispatcherRule.dispatcher) {
        flags.set(Flag.FREE_TIER, true)
        val developer = DeveloperSettingsViewModel(flags)
        val vm = settings()
        developer.onUnlockedOverrideChange(true)
        advanceUntilIdle()
        assertTrue(developer.uiState.value.unlockedOverride)
        assertTrue(developer.uiState.value.anyChanged)
        assertTrue(vm.uiState.value.unlock!!.unlocked)

        developer.onReset()
        advanceUntilIdle()
        assertFalse(developer.uiState.value.unlockedOverride)
        assertNull("Reset turned the free tier off too", vm.uiState.value.unlock)
    }
}
