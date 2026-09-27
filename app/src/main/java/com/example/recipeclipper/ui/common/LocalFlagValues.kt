package com.example.recipeclipper.ui.common

import androidx.compose.runtime.compositionLocalOf
import com.example.recipeclipper.data.flags.FlagValues

/**
 * The feature flags (#87) as they are now, provided by MainActivity from `FeatureFlags.values`,
 * so a screen deep in the graph reacts to a Developer settings change without a restart.
 * Unprovided (a screen test), every flag is off, the shipped state.
 */
val LocalFlagValues = compositionLocalOf { FlagValues.ALL_OFF }
