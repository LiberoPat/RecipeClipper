package com.example.recipeclipper.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * The Settings screen's ViewModel. It injects [AppPreferences] directly rather than going
 * through a repository: these are app-wide defaults, not per-recipe state.
 *
 * [uiState] is a plain `MutableStateFlow`, seeded synchronously from the preferences so the
 * first frame is right, then kept in step with [AppPreferences.settings], which this collects
 * itself (so it holds its value with no subscriber). Each setter also updates the state at
 * once, alongside the write, rather than waiting for the flow to echo it back.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(preferences.current.toUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.settings.collect { settings -> _uiState.value = settings.toUiState() }
        }
    }

    private fun AppSettings.toUiState() =
        SettingsUiState(unitSystem, convertLiquids, temperatureUnit, darkWhileCooking)

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
