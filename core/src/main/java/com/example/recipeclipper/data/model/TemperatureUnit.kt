package com.example.recipeclipper.data.model

/**
 * How oven temperatures in instruction text are shown. Independent of [UnitSystem]: a user
 * can be a Metric person and still want an oven temperature left exactly as the recipe wrote
 * it (or vice versa), so this is its own setting rather than implied by the unit choice.
 * AS_WRITTEN (the default) leaves the text alone.
 */
enum class TemperatureUnit { AS_WRITTEN, CELSIUS, FAHRENHEIT }
