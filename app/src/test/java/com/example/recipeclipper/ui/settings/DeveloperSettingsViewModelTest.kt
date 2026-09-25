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
        listOf(FlagDefinition("mealPlan", "The meal plan", debugDefault = false, releaseDefault = false, issue = 47)),
        isDebug = true
    )

    @Test fun `lists every flag with its description, issue and state`() = runTest(mainDispatcherRule.dispatcher) {
        val row = DeveloperSettingsViewModel(flags).uiState.value.flags.first { it.flag == Flag.MEAL_PLAN }

        assertEquals(Flag.MEAL_PLAN, row.flag)
        assertEquals("The meal plan", row.description)
        assertEquals(47, row.issue)
        assertFalse(row.on)
        assertFalse(row.changed)
    }

    @Test fun `a switch overrides the flag, and reset clears it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = DeveloperSettingsViewModel(flags)

        vm.onFlagChange(Flag.MEAL_PLAN, true)
        assertTrue(flags.isOn(Flag.MEAL_PLAN))
        assertTrue(vm.uiState.value.flags.first { it.flag == Flag.MEAL_PLAN }.on)
        assertTrue(vm.uiState.value.anyChanged)

        vm.onReset()
        assertFalse(flags.isOn(Flag.MEAL_PLAN))
        assertFalse(vm.uiState.value.anyChanged)
    }

    @Test fun `follows a change made elsewhere`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = DeveloperSettingsViewModel(flags)

        store.setOverride("mealPlan", true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.flags.first { it.flag == Flag.MEAL_PLAN }.on)
    }
}
