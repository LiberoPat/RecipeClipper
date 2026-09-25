package com.example.recipeclipper.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One flag's row: its key and flags.json description (developer text, English by design). */
data class FlagRow(
    val flag: Flag,
    val description: String,
    val issue: Int?,
    val on: Boolean,
    val changed: Boolean
)

data class DeveloperSettingsUiState(val flags: List<FlagRow> = emptyList()) {
    val anyChanged: Boolean get() = flags.any { it.changed }
}

/** The hidden Developer settings (#87): a switch per flag and a reset, over [FeatureFlags]. */
@HiltViewModel
class DeveloperSettingsViewModel @Inject constructor(
    private val featureFlags: FeatureFlags
) : ViewModel() {

    private val _uiState = MutableStateFlow(state())
    val uiState: StateFlow<DeveloperSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            featureFlags.values.collect { _uiState.value = state() }
        }
    }

    fun onFlagChange(flag: Flag, on: Boolean) {
        featureFlags.set(flag, on)
        _uiState.value = state()
    }

    fun onReset() {
        featureFlags.reset()
        _uiState.value = state()
    }

    private fun state() = DeveloperSettingsUiState(
        Flag.entries.map { flag ->
            val definition = featureFlags.definition(flag)
            FlagRow(
                flag = flag,
                description = definition?.description.orEmpty(),
                issue = definition?.issue,
                on = featureFlags.isOn(flag),
                changed = featureFlags.isOverridden(flag)
            )
        }
    )
}
