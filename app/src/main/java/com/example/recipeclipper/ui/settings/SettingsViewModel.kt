package com.example.recipeclipper.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.AppInfo
import com.example.recipeclipper.data.BackupFiles
import com.example.recipeclipper.data.BackupRepository
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.Entitlements
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.ShortStepRepository
import com.example.recipeclipper.data.needsNotice
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val backup: BackupStatus = BackupStatus.Idle,
    /** The Pantry section (#52): only with the `mealPlan` flag on, since the pantry is behind it. */
    val showsPantry: Boolean = false,
    /** The Steps section's "Amounts in steps" (#101): only with the `amountsInSteps` flag on. */
    val showsSteps: Boolean = false,
    /** Ingredient amounts inside steps. */
    val amountsInSteps: Boolean = false,
    /** A morning notification when pantry items are about to expire. */
    val expiryReminders: Boolean = false,
    /** Turning reminders on was refused (notifications not allowed): the switch stays off and
     *  says why, until it is turned on successfully. */
    val expiryRemindersDenied: Boolean = false,
    /** e.g. "1.0 (1)", shown at the foot; tapping it [SettingsViewModel.DEVELOPER_TAPS] times
     *  opens Developer settings (#87). */
    val appVersion: String = "",
    /** The Steps section's "Chef mode" (#100): only with the `chefMode` flag on. */
    val showsChefMode: Boolean = false,
    /** Chef mode as saved: short steps written on the device. */
    val chefMode: Boolean = false,
    /** What this phone can do, once asked; null until then. The switch works only when Available. */
    val chefSupport: ChefSupport? = null,
    /** The "Unlimited recipes" row (#107); null while the `freeTier` flag is off. */
    val unlock: UnlockRow? = null,
    /** A purchase or restore that didn't simply unlock; shown until the next one starts. */
    val unlockNotice: PurchaseOutcome? = null
) {
    val chefModeAvailable: Boolean get() = chefSupport is ChefSupport.Available
}

/**
 * [unlocked] counts Developer settings' override too, so testing sees the unlocked row.
 * [busy] while the store's sheet or a restore is under way, so the buttons can't be doubled.
 */
data class UnlockRow(
    val unlocked: Boolean,
    val pending: Boolean = false,
    val price: String? = null,
    val busy: Boolean = false
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
    private val files: BackupFiles,
    private val appInfo: AppInfo,
    // Last and optional, so a test that doesn't care builds the screen without it (no Pantry section).
    private val featureFlags: FeatureFlags? = null,
    // Chef mode (#100); without it the Steps section says the phone can't.
    private val shortSteps: ShortStepRepository? = null,
    // The free tier's store (#107); pass it by name.
    private val entitlements: Entitlements = Entitlements.Unavailable
) : ViewModel() {

    private val _uiState = MutableStateFlow(preferences.current.toUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Chef mode's support is asked once, and only with the flag on, so the model is never woken otherwise.
    private var askedChefSupport = false

    init {
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                _uiState.update { settings.toUiState(it) }
            }
        }
        featureFlags?.let { flags ->
            viewModelScope.launch {
                flags.values.collect { values ->
                    _uiState.update {
                        it.copy(
                            showsPantry = values.isOn(Flag.MEAL_PLAN),
                            showsSteps = values.isOn(Flag.AMOUNTS_IN_STEPS),
                            showsChefMode = values.isOn(Flag.CHEF_MODE)
                        )
                    }
                    if (values.isOn(Flag.CHEF_MODE)) askChefSupport()
                }
            }
            viewModelScope.launch {
                combine(flags.values, flags.unlockedOverrides, entitlements.state) { values, override, store ->
                    if (!values.isOn(Flag.FREE_TIER)) null
                    else UnlockRow(store.unlocked || override, store.pending, store.price)
                }.collect { row ->
                    _uiState.update { it.copy(unlock = row?.copy(busy = unlockBusy)) }
                }
            }
        }
    }

    private fun askChefSupport() {
        if (askedChefSupport) return
        askedChefSupport = true
        viewModelScope.launch {
            val support = shortSteps?.support() ?: ChefSupport.Unsupported
            _uiState.update { it.copy(chefSupport = support) }
        }
    }

    /** The Chef mode switch: only turns on where the phone can write short steps. */
    fun onChefModeChange(enabled: Boolean) {
        if (enabled && !_uiState.value.chefModeAvailable) return
        preferences.chefMode = enabled
        _uiState.update { it.copy(chefMode = enabled) }
    }

    // A purchase or restore is under way; kept apart so a store update can't clear it.
    private var unlockBusy = false

    /** "Unlock" (#107): the store's purchase sheet. */
    fun onUnlock() = runUnlock { entitlements.purchase() }

    /** "Restore purchase" (#107): asks the store for this account's purchase again. */
    fun onRestore() = runUnlock { entitlements.restore() }

    private fun runUnlock(action: suspend () -> PurchaseOutcome) {
        if (unlockBusy) return
        _uiState.update { it.copy(unlockNotice = null) }
        setUnlockBusy(true)
        viewModelScope.launch {
            val outcome = action()
            setUnlockBusy(false)
            if (outcome.needsNotice) _uiState.update { it.copy(unlockNotice = outcome) }
        }
    }

    private fun setUnlockBusy(busy: Boolean) {
        unlockBusy = busy
        _uiState.update { it.copy(unlock = it.unlock?.copy(busy = busy)) }
    }

    /** The preferences' part of the state; the rest is this screen's own and carries over. */
    private fun AppSettings.toUiState(previous: SettingsUiState? = null) = SettingsUiState(
        unitSystem, convertLiquids, temperatureUnit, darkWhileCooking,
        backup = previous?.backup ?: BackupStatus.Idle,
        showsPantry = previous?.showsPantry ?: (featureFlags?.isOn(Flag.MEAL_PLAN) ?: false),
        showsSteps = previous?.showsSteps ?: (featureFlags?.isOn(Flag.AMOUNTS_IN_STEPS) ?: false),
        amountsInSteps = amountsInSteps,
        expiryReminders = expiryReminders,
        expiryRemindersDenied = previous?.expiryRemindersDenied ?: false,
        appVersion = appInfo.appVersion,
        showsChefMode = previous?.showsChefMode ?: (featureFlags?.isOn(Flag.CHEF_MODE) ?: false),
        chefMode = chefMode,
        chefSupport = previous?.chefSupport,
        unlock = previous?.unlock,
        unlockNotice = previous?.unlockNotice
    )

    // Taps on the version so far. Here, not in the screen, so a rotation mid-sequence keeps it.
    private var versionTaps = 0

    /**
     * The hidden way into Developer settings (#87), in release builds too (the owner's call):
     * true on the [DEVELOPER_TAPS]th tap, when the screen opens it, and the count starts over.
     */
    fun onVersionTapped(): Boolean {
        versionTaps++
        if (versionTaps < DEVELOPER_TAPS) return false
        versionTaps = 0
        return true
    }

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

    fun onAmountsInStepsChange(enabled: Boolean) {
        preferences.amountsInSteps = enabled
        _uiState.update { it.copy(amountsInSteps = enabled) }
    }

    /**
     * The expiry reminders switch (#52). Turning it on is the screen's job first: it asks for
     * notification permission (never on launch) and calls [onExpiryRemindersPermission] with the
     * answer. Off needs no asking.
     */
    fun onExpiryRemindersOff() {
        preferences.expiryReminders = false
        _uiState.update { it.copy(expiryReminders = false) }
    }

    /** Notifications are allowed (reminders go on) or refused (they stay off, and the row says why). */
    fun onExpiryRemindersPermission(granted: Boolean) {
        preferences.expiryReminders = granted
        _uiState.update { it.copy(expiryReminders = granted, expiryRemindersDenied = !granted) }
    }

    /** Export: read everything out, write the file, then hand it to the screen to share. */
    fun onExport() {
        if (_uiState.value.backup.isBusy) return
        _uiState.update { it.copy(backup = BackupStatus.Exporting) }
        viewModelScope.launch {
            val status = when (val exported = backups.export()) {
                is BackupResult.Failure -> BackupStatus.Failed(exported.error)
                is BackupResult.Success ->
                    files.writeExport(exported.value.json, exported.value.exportedAt, exported.value.photos)
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
            val status = when (val picked = files.read(uri)) {
                is BackupResult.Failure -> BackupStatus.Failed(picked.error)
                is BackupResult.Success -> when (val imported = backups.import(picked.value)) {
                    is BackupResult.Success -> BackupStatus.Imported(imported.value)
                    is BackupResult.Failure -> BackupStatus.Failed(imported.error)
                }
            }
            _uiState.update { it.copy(backup = status) }
        }
    }

    companion object {
        const val DEVELOPER_TAPS = 7
    }
}
