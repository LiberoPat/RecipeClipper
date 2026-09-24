package com.example.recipeclipper.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.BackupFiles
import com.example.recipeclipper.data.BackupRepository
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ImportSummary
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
    val darkWhileCooking: Boolean = false,
    val backup: BackupStatus = BackupStatus.Idle
)

/**
 * The "Your recipes" section: export and import (#26). One at a time; the screen shows the
 * last outcome under the two rows until the next action.
 */
sealed class BackupStatus {
    object Idle : BackupStatus()
    object Exporting : BackupStatus()
    object Importing : BackupStatus()

    /** The file is written: the screen opens the share sheet on [uri], then calls
     *  [SettingsViewModel.onExportShared]. */
    data class ReadyToShare(val uri: String) : BackupStatus()
    data class Imported(val summary: ImportSummary) : BackupStatus()
    data class Failed(val error: BackupError) : BackupStatus()

    val isBusy: Boolean get() = this == Exporting || this == Importing || this is ReadyToShare
}

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
    private val preferences: AppPreferences,
    private val backups: BackupRepository,
    private val files: BackupFiles
) : ViewModel() {

    private val _uiState = MutableStateFlow(preferences.current.toUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                _uiState.update { settings.toUiState(backup = it.backup) }
            }
        }
    }

    /** The preferences' part of the state; [backup] is this screen's own and carries over. */
    private fun AppSettings.toUiState(backup: BackupStatus = BackupStatus.Idle) =
        SettingsUiState(unitSystem, convertLiquids, temperatureUnit, darkWhileCooking, backup)

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

    /** Export: read everything out, write the file, then hand it to the screen to share. */
    fun onExport() {
        if (_uiState.value.backup.isBusy) return
        _uiState.update { it.copy(backup = BackupStatus.Exporting) }
        viewModelScope.launch {
            val status = when (val exported = backups.export()) {
                is BackupResult.Failure -> BackupStatus.Failed(exported.error)
                is BackupResult.Success ->
                    files.writeExport(exported.value.json, exported.value.exportedAt)
                        ?.let { BackupStatus.ReadyToShare(it) }
                        ?: BackupStatus.Failed(BackupError.ExportFailed)
            }
            _uiState.update { it.copy(backup = status) }
        }
    }

    /** The share sheet has been opened (or couldn't be): the export is done. */
    fun onExportShared() {
        if (_uiState.value.backup is BackupStatus.ReadyToShare) {
            _uiState.update { it.copy(backup = BackupStatus.Idle) }
        }
    }

    /** Import from the file the user picked in the system file picker. */
    fun onImportPicked(uri: String) {
        if (_uiState.value.backup.isBusy) return
        _uiState.update { it.copy(backup = BackupStatus.Importing) }
        viewModelScope.launch {
            val status = when (val text = files.readText(uri)) {
                is BackupResult.Failure -> BackupStatus.Failed(text.error)
                is BackupResult.Success -> when (val imported = backups.import(text.value)) {
                    is BackupResult.Success -> BackupStatus.Imported(imported.value)
                    is BackupResult.Failure -> BackupStatus.Failed(imported.error)
                }
            }
            _uiState.update { it.copy(backup = status) }
        }
    }
}
