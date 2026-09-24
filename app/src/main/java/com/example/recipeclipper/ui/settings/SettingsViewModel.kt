package com.example.recipeclipper.ui.settings

import androidx.lifecycle.ViewModel
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Mirrors [AppPreferences] — the app's global defaults, not any one recipe's. [unitSystem]
 * and [convertLiquids] are ingredient units; [temperatureUnit] is the independent oven-
 * temperature choice (see [TemperatureUnit]'s doc for why it's separate); [darkWhileCooking]
 * is a display choice.
 */
data class SettingsUiState(
    val unitSystem: UnitSystem = UnitSystem.AS_WRITTEN,
    val convertLiquids: Boolean = false,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.AS_WRITTEN,
    val darkWhileCooking: Boolean = false
)

/**
 * The Settings screen's ViewModel. Unlike [com.example.recipeclipper.ui.recipe.RecipeViewModel]
 * this injects [AppPreferences] directly rather than going through the repository — these are
 * app-wide defaults, not per-recipe state. [AppPreferences] is plain `var`s, so [uiState] is a
 * `MutableStateFlow` seeded from it in [init] and updated in each setter alongside the write,
 * rather than derived from a Flow the preferences don't expose.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            unitSystem = preferences.unitSystem,
            convertLiquids = preferences.convertLiquids,
            temperatureUnit = preferences.temperatureUnit,
            darkWhileCooking = preferences.darkWhileCooking
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun onUnitSystemChange(system: UnitSystem) {
        preferences.unitSystem = system
        _uiState.update { it.copy(unitSystem = system) }
    }

    fun onConvertLiquidsChange(enabled: Boolean) {
        preferences.convertLiquids = enabled
        _uiState.update { it.copy(convertLiquids = enabled) }
    }

    fun onTemperatureUnitChange(unit: TemperatureUnit) {
        preferences.temperatureUnit = unit
        _uiState.update { it.copy(temperatureUnit = unit) }
    }

    fun onDarkWhileCookingChange(enabled: Boolean) {
        preferences.darkWhileCooking = enabled
        _uiState.update { it.copy(darkWhileCooking = enabled) }
    }
}
