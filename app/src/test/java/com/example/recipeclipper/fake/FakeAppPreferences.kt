package com.example.recipeclipper.fake

import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.model.MealReminders
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.RecipesLayout
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
    chefMode: Boolean = false,
    amountsInSteps: Boolean = false,
    recipeSort: RecipeSort = RecipeSort.RECENTLY_VIEWED,
    recipesLayout: RecipesLayout = RecipesLayout.ROWS,
    eveningReminder: Boolean = true,
    eveningReminderMinute: Int = MealReminders.EVENING_MINUTE,
    tonightReminder: Boolean = false,
    tonightReminderMinute: Int = MealReminders.TONIGHT_MINUTE
) : AppPreferences {

    private val state = MutableStateFlow(
        AppSettings(
            unitSystem, convertLiquids, temperatureUnit, darkWhileCooking, expiryReminders, chefMode, amountsInSteps,
            recipeSort, recipesLayout, eveningReminder, eveningReminderMinute, tonightReminder, tonightReminderMinute
        )
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

    override var eveningReminder: Boolean
        get() = state.value.eveningReminder
        set(value) = state.update { it.copy(eveningReminder = value) }

    override var eveningReminderMinute: Int
        get() = state.value.eveningReminderMinute
        set(value) = state.update { it.copy(eveningReminderMinute = value) }

    override var tonightReminder: Boolean
        get() = state.value.tonightReminder
        set(value) = state.update { it.copy(tonightReminder = value) }

    override var tonightReminderMinute: Int
        get() = state.value.tonightReminderMinute
        set(value) = state.update { it.copy(tonightReminderMinute = value) }

    override var chefMode: Boolean
        get() = state.value.chefMode
        set(value) = state.update { it.copy(chefMode = value) }


    override var amountsInSteps: Boolean
        get() = state.value.amountsInSteps
        set(value) = state.update { it.copy(amountsInSteps = value) }

    override var recipeSort: RecipeSort
        get() = state.value.recipeSort
        set(value) = state.update { it.copy(recipeSort = value) }

    override var recipesLayout: RecipesLayout
        get() = state.value.recipesLayout
        set(value) = state.update { it.copy(recipesLayout = value) }
}
