package com.example.recipeclipper.ui.settings

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagDefinition
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeveloperSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val store = FakeFeatureFlagStore()
    private val flags = FeatureFlags(
        store,
        listOf(FlagDefinition("chefMode", "Chef mode", debugDefault = false, releaseDefault = false, issue = 100)),
        isDebug = true
    )

    @Test fun `lists every flag with its description, issue and state`() = runTest(mainDispatcherRule.dispatcher) {
        val row = DeveloperSettingsViewModel(flags).uiState.value.flags.single { it.flag == Flag.CHEF_MODE }

        assertEquals(Flag.CHEF_MODE, row.flag)
        assertEquals("Chef mode", row.description)
        assertEquals(100, row.issue)
        assertFalse(row.on)
        assertFalse(row.changed)
    }

    @Test fun `a switch overrides the flag, and reset clears it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = DeveloperSettingsViewModel(flags)

        vm.onFlagChange(Flag.CHEF_MODE, true)
        assertTrue(flags.isOn(Flag.CHEF_MODE))
        assertTrue(vm.uiState.value.flags.single { it.flag == Flag.CHEF_MODE }.on)
        assertTrue(vm.uiState.value.anyChanged)

        vm.onReset()
        assertFalse(flags.isOn(Flag.CHEF_MODE))
        assertFalse(vm.uiState.value.anyChanged)
    }

    @Test fun `follows a change made elsewhere`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = DeveloperSettingsViewModel(flags)

        store.setOverride("chefMode", true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.flags.single { it.flag == Flag.CHEF_MODE }.on)
    }
}
