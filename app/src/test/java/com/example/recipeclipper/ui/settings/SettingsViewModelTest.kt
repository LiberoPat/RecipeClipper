package com.example.recipeclipper.ui.settings

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.fake.FakeAppPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [SettingsUiState] is a plain `MutableStateFlow`, not `stateIn(WhileSubscribed(...))`, and the
 * ViewModel collects `AppPreferences.settings` itself, so it holds its value with no collector
 * needed — unlike [com.example.recipeclipper.ui.home.HomeViewModelTest] and
 * [com.example.recipeclipper.ui.history.HistoryViewModelTest], these tests don't need
 * `collectEagerly`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test fun `state is seeded from preferences on construction`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences(
            unitSystem = UnitSystem.METRIC,
            convertLiquids = true,
            temperatureUnit = TemperatureUnit.CELSIUS,
            darkWhileCooking = true
        )
        val vm = SettingsViewModel(preferences)

        assertEquals(UnitSystem.METRIC, vm.uiState.value.unitSystem)
        assertTrue(vm.uiState.value.convertLiquids)
        assertEquals(TemperatureUnit.CELSIUS, vm.uiState.value.temperatureUnit)
        assertTrue(vm.uiState.value.darkWhileCooking)
    }

    @Test fun `defaults match AppPreferences defaults`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = SettingsViewModel(FakeAppPreferences())

        assertEquals(UnitSystem.AS_WRITTEN, vm.uiState.value.unitSystem)
        assertFalse(vm.uiState.value.convertLiquids)
        assertEquals(TemperatureUnit.AS_WRITTEN, vm.uiState.value.temperatureUnit)
        assertFalse(vm.uiState.value.darkWhileCooking)
    }

    @Test fun `onUnitSystemChange writes through and updates state`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences)

        vm.onUnitSystemChange(UnitSystem.GRAMS)

        assertEquals(UnitSystem.GRAMS, vm.uiState.value.unitSystem)
        assertEquals(UnitSystem.GRAMS, preferences.unitSystem)
    }

    @Test fun `onConvertLiquidsChange writes through and updates state`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences)

        vm.onConvertLiquidsChange(true)

        assertTrue(vm.uiState.value.convertLiquids)
        assertTrue(preferences.convertLiquids)
    }

    @Test fun `onTemperatureUnitChange writes through and updates state independently of unit system`() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeAppPreferences()
            val vm = SettingsViewModel(preferences)

            vm.onTemperatureUnitChange(TemperatureUnit.FAHRENHEIT)

            assertEquals(TemperatureUnit.FAHRENHEIT, vm.uiState.value.temperatureUnit)
            assertEquals(TemperatureUnit.FAHRENHEIT, preferences.temperatureUnit)
            // Independent: changing the oven temperature choice must not touch the unit system.
            assertEquals(UnitSystem.AS_WRITTEN, vm.uiState.value.unitSystem)
        }

    @Test fun `onDarkWhileCookingChange writes through and updates state`() = runTest(mainDispatcherRule.dispatcher) {
        val preferences = FakeAppPreferences()
        val vm = SettingsViewModel(preferences)

        vm.onDarkWhileCookingChange(true)

        assertTrue(vm.uiState.value.darkWhileCooking)
        assertTrue(preferences.darkWhileCooking)
    }

    @Test fun `a change written elsewhere while Settings is open reaches its state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val preferences = FakeAppPreferences()
            val vm = SettingsViewModel(preferences)
            advanceUntilIdle()

            // e.g. the recipe screen's units dropdown, with Settings on the back stack
            preferences.unitSystem = UnitSystem.OUNCES
            preferences.temperatureUnit = TemperatureUnit.CELSIUS
            advanceUntilIdle()

            assertEquals(UnitSystem.OUNCES, vm.uiState.value.unitSystem)
            assertEquals(TemperatureUnit.CELSIUS, vm.uiState.value.temperatureUnit)
        }

    @Test fun `a setter's write echoing back through settings leaves the state as set`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = SettingsViewModel(FakeAppPreferences())
            advanceUntilIdle()

            vm.onUnitSystemChange(UnitSystem.METRIC)
            vm.onConvertLiquidsChange(true)
            advanceUntilIdle()

            assertEquals(SettingsUiState(unitSystem = UnitSystem.METRIC, convertLiquids = true), vm.uiState.value)
        }
}
