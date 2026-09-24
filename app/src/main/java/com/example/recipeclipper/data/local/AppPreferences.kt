package com.example.recipeclipper.data.local

import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem

/**
 * The user's global defaults: set once, applied to every recipe, and kept between sessions.
 * Named for the app rather than for units because [darkWhileCooking] is a display choice,
 * not a measurement one, even though the menu that sets it is reached from the unit label.
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
}
