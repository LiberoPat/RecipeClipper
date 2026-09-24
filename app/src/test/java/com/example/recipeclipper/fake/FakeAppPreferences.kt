package com.example.recipeclipper.fake

import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem

/** Plain `var`s: enough to prove a ViewModel writes a choice through, which is what makes
 *  these global defaults rather than per-recipe state. */
class FakeAppPreferences(
    override var unitSystem: UnitSystem = UnitSystem.AS_WRITTEN,
    override var convertLiquids: Boolean = false,
    override var temperatureUnit: TemperatureUnit = TemperatureUnit.AS_WRITTEN,
    override var darkWhileCooking: Boolean = false
) : AppPreferences
