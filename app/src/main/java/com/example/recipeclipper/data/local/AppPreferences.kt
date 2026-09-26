package com.example.recipeclipper.data.local

import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import kotlinx.coroutines.flow.Flow

/**
 * The user's global defaults: set once, applied to every recipe, and kept between sessions.
 * Named for the app rather than for units because [darkWhileCooking] is a display choice,
 * not a measurement one.
 *
 * An interface so a ViewModel test can hand it a fake: [SharedPrefsAppPreferences] is the
 * only class holding a `Context`, which a plain-JUnit test cannot construct.
 */
interface AppPreferences {
    var unitSystem: UnitSystem
    var convertLiquids: Boolean

    /**
     * Independent of [unitSystem] — see [TemperatureUnit]'s doc for why. Defaults to
     * AS_WRITTEN, so choosing a unit system alone no longer converts oven temperatures.
     */
    var temperatureUnit: TemperatureUnit

    /**
     * Forces the ink scheme while cooking even in light mode. Off by default: cook mode
     * follows the system theme like every other screen, and this opts back in to the
     * always-dark behaviour the app shipped with.
     */
    var darkWhileCooking: Boolean

    /**
     * A morning notification when something in the pantry is about to expire (#52). Off by
     * default; Settings turns it on only once notifications are allowed.
     */
    var expiryReminders: Boolean

    /**
     * Ingredient amounts inside steps (#101): "Add the carrots" reads "Add 2 carrots". Off by
     * default, and shown only with the `amountsInSteps` flag on.
     */
    var amountsInSteps: Boolean

    /** The Recipes screen's sort (#102): a view preference, kept so it survives leaving the screen. */
    var recipeSort: RecipeSort

    /**
     * The current values first, then every change, never repeating a value. A screen that
     * collects this stays current when Settings changes a default while it is open (#24).
     */
    val settings: Flow<AppSettings>

    /** The values as they are right now. */
    val current: AppSettings
        get() = AppSettings(unitSystem, convertLiquids, temperatureUnit, darkWhileCooking, expiryReminders, amountsInSteps, recipeSort)
}

/** One snapshot of [AppPreferences], as its [AppPreferences.settings] flow emits them. */
data class AppSettings(
    val unitSystem: UnitSystem = UnitSystem.AS_WRITTEN,
    val convertLiquids: Boolean = false,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.AS_WRITTEN,
    val darkWhileCooking: Boolean = false,
    val expiryReminders: Boolean = false,
    val amountsInSteps: Boolean = false,
    val recipeSort: RecipeSort = RecipeSort.RECENTLY_VIEWED
)
