package com.example.recipeclipper.fake

import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds its values in one [MutableStateFlow], so a write through any `var` re-emits on
 * [settings], as the real SharedPreferences listener does. A test that sets a value here
 * directly is Settings changing a default while another screen is open.
 */
class FakeAppPreferences(
    unitSystem: UnitSystem = UnitSystem.AS_WRITTEN,
    convertLiquids: Boolean = false,
    temperatureUnit: TemperatureUnit = TemperatureUnit.AS_WRITTEN,
    darkWhileCooking: Boolean = false,
    expiryReminders: Boolean = false,
    amountsInSteps: Boolean = false,
    recipeSort: RecipeSort = RecipeSort.RECENTLY_VIEWED
) : AppPreferences {

    private val state = MutableStateFlow(
        AppSettings(unitSystem, convertLiquids, temperatureUnit, darkWhileCooking, expiryReminders, amountsInSteps, recipeSort)
    )

    override val settings: StateFlow<AppSettings> = state

    override var unitSystem: UnitSystem
        get() = state.value.unitSystem
        set(value) = state.update { it.copy(unitSystem = value) }

    override var convertLiquids: Boolean
        get() = state.value.convertLiquids
        set(value) = state.update { it.copy(convertLiquids = value) }

    override var temperatureUnit: TemperatureUnit
        get() = state.value.temperatureUnit
        set(value) = state.update { it.copy(temperatureUnit = value) }

    override var darkWhileCooking: Boolean
        get() = state.value.darkWhileCooking
        set(value) = state.update { it.copy(darkWhileCooking = value) }

    override var expiryReminders: Boolean
        get() = state.value.expiryReminders
        set(value) = state.update { it.copy(expiryReminders = value) }

    override var amountsInSteps: Boolean
        get() = state.value.amountsInSteps
        set(value) = state.update { it.copy(amountsInSteps = value) }

    override var recipeSort: RecipeSort
        get() = state.value.recipeSort
        set(value) = state.update { it.copy(recipeSort = value) }
}
