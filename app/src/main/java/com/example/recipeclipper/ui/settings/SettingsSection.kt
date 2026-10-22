package com.example.recipeclipper.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.recipeclipper.R

/**
 * Settings' sections (#286), in the index's order: each is a row there, with its current value,
 * and a page of its own at `settings/<route>`. [UNLIMITED] shows only with the `freeTier` flag.
 */
enum class SettingsSection(val route: String, @StringRes val title: Int, @DrawableRes val icon: Int) {
    UNITS("units", R.string.settings_section_units, R.drawable.ic_settings_units),
    OVEN_TEMPERATURE("oven-temperature", R.string.settings_section_oven_temperature, R.drawable.ic_settings_oven),
    APPEARANCE("appearance", R.string.settings_section_appearance, R.drawable.ic_settings_appearance),
    STEPS("steps", R.string.settings_section_steps, R.drawable.ic_settings_steps),
    PANTRY("pantry", R.string.settings_section_pantry, R.drawable.ic_settings_pantry),
    YOUR_RECIPES("your-recipes", R.string.settings_section_your_recipes, R.drawable.ic_settings_recipes),
    HELP("help", R.string.settings_section_help, R.drawable.ic_settings_help),
    UNLIMITED("unlimited", R.string.unlimited_title, R.drawable.ic_settings_unlimited)
}
