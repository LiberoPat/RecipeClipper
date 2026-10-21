package com.example.recipeclipper.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.data.model.TooltipScreen
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import com.example.recipeclipper.ui.tour.TooltipHost
import com.example.recipeclipper.ui.tour.tooltipAnchor

/**
 * One section of Settings on a page of its own (#286), opened from its row on the index
 * ([SettingsScreen]); Back returns there. It holds exactly that section's controls, as the one
 * long screen had them: exclusive choices are radio rows, toggles are switches, actions are plain
 * rows. Its ViewModel is its own, collecting [AppPreferences][com.example.recipeclipper.data.local.AppPreferences]
 * like the index's, so a change here is on the index when the cook goes back.
 *
 * Help hosts Settings' tooltip (#190), "Show tips again": the visit is the Help page's.
 */
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    onShowTips: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The pickers live here, not in a lazy row, so a row scrolled away can't drop their answer.
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onImportPicked(it.toString()) }
    }
    // The automatic backup copy's folder (#150): the system's folder picker, kept by permission.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onBackupFolderPicked(uri?.toString())
    }
    val notificationPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onExpiryRemindersPermission(granted)
    }
    val shareTitle = stringResource(R.string.backup_share_title)
    val backup = state.backup
    if (backup is BackupStatus.ReadyToShare) {
        // The share sheet is a platform effect, so it lives here; the ViewModel only says when.
        LaunchedEffect(backup.uri) {
            shareExport(context, backup.uri, shareTitle)
            viewModel.onExportShared()
        }
    }
    val actions = SectionActions(
        onShowTips = onShowTips,
        onImport = { importPicker.launch(IMPORT_MIME_TYPES) },
        onChooseFolder = { folderPicker.launch(null) },
        onExpiryRemindersChange = { on ->
            // Asking is a platform effect, so it lives here: only when the cook turns reminders
            // on, never on launch (#52).
            when {
                !on -> viewModel.onExpiryRemindersOff()
                NotificationManagerCompat.from(context).areNotificationsEnabled() ->
                    viewModel.onExpiryRemindersPermission(true)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED ->
                    notificationPrompt.launch(Manifest.permission.POST_NOTIFICATIONS)
                // Allowed, but switched off for the app in system settings.
                else -> viewModel.onExpiryRemindersPermission(false)
            }
        }
    )
    val page: @Composable () -> Unit = {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize()
                    // Edge-to-edge: the background fills behind the bars, the rows stay clear
                    // of them, the display cutout and the keyboard.
                    .safeDrawingPadding()
            ) {
                item(key = "title") {
                    BackButton(onBack)
                    Text(
                        stringResource(section.title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                }
                sectionRows(section, state, viewModel, actions)
            }
        }
    }
    RecipeClipperTheme {
        if (section == SettingsSection.HELP) TooltipHost(TooltipScreen.SETTINGS) { page() } else page()
    }
}

/** What a page's rows ask of the screen: the platform effects (pickers, the permission prompt). */
private class SectionActions(
    val onShowTips: () -> Unit,
    val onImport: () -> Unit,
    val onChooseFolder: () -> Unit,
    val onExpiryRemindersChange: (Boolean) -> Unit
)

private fun LazyListScope.sectionRows(
    section: SettingsSection,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    actions: SectionActions
) {
    when (section) {
        SettingsSection.UNITS -> {
            items(UnitSystem.entries, key = { "unit-${it.name}" }) { option ->
                RadioRow(
                    title = option.settingsLabel(),
                    description = option.settingsDescription(),
                    selected = option == state.unitSystem,
                    onClick = { viewModel.onUnitSystemChange(option) }
                )
            }
            if (state.unitSystem == UnitSystem.OUNCES) {
                item(key = "convert-liquids") {
                    SwitchRow(
                        title = stringResource(R.string.convert_liquids_title),
                        description = stringResource(R.string.convert_liquids_description),
                        checked = state.convertLiquids,
                        onCheckedChange = viewModel::onConvertLiquidsChange
                    )
                }
            }
        }
        SettingsSection.OVEN_TEMPERATURE -> items(TemperatureUnit.entries, key = { "oven-${it.name}" }) { option ->
            RadioRow(
                title = option.settingsLabel(),
                description = option.settingsDescription(),
                selected = option == state.temperatureUnit,
                onClick = { viewModel.onTemperatureUnitChange(option) }
            )
        }
        SettingsSection.APPEARANCE -> item(key = "appearance") {
            SwitchRow(
                title = stringResource(R.string.dark_while_cooking_title),
                description = stringResource(R.string.dark_while_cooking_description),
                checked = state.darkWhileCooking,
                onCheckedChange = viewModel::onDarkWhileCookingChange
            )
        }
        SettingsSection.STEPS -> item(key = "steps") {
            SwitchRow(
                title = stringResource(R.string.amounts_in_steps_title),
                description = stringResource(R.string.amounts_in_steps_description),
                checked = state.amountsInSteps,
                onCheckedChange = viewModel::onAmountsInStepsChange
            )
            if (state.showsChefMode) ChefModeRow(state, viewModel::onChefModeChange)
        }
        SettingsSection.PANTRY -> item(key = "pantry") {
            SwitchRow(
                title = stringResource(R.string.expiry_reminders_title),
                description = stringResource(R.string.expiry_reminders_description),
                checked = state.expiryReminders,
                onCheckedChange = actions.onExpiryRemindersChange
            )
            if (state.expiryRemindersDenied && !state.expiryReminders) {
                Text(
                    stringResource(R.string.expiry_reminders_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
        SettingsSection.YOUR_RECIPES -> item(key = "your-recipes") { YourRecipesRows(state, viewModel, actions) }
        SettingsSection.HELP -> item(key = "help") {
            ActionRow(
                title = stringResource(R.string.settings_show_tips),
                description = stringResource(R.string.settings_show_tips_description),
                enabled = true,
                onClick = actions.onShowTips,
                modifier = Modifier.tooltipAnchor(Tooltip.SETTINGS_SHOW_TIPS)
            )
        }
        SettingsSection.UNLIMITED -> state.unlock?.let { row ->
            item(key = "unlimited") { UnlockSection(row, state.unlockNotice, viewModel::onUnlock, viewModel::onRestore) }
        }
    }
}

/** Your recipes: the automatic backup copy (#150), then Export and Import (#26), then their outcome. */
@Composable
private fun YourRecipesRows(state: SettingsUiState, viewModel: SettingsViewModel, actions: SectionActions) {
    state.autoBackup?.let { auto ->
        AutoBackupRows(
            auto,
            onEnabledChange = viewModel::onAutoBackupChange,
            onChooseFolder = actions.onChooseFolder,
            onBackUpNow = viewModel::onBackUpNow
        )
    }
    // Actions, not choices: plain rows, no radio or switch.
    ActionRow(
        title = stringResource(R.string.backup_export_title),
        description = stringResource(R.string.backup_export_description),
        enabled = !state.backup.isBusy,
        onClick = viewModel::onExport
    )
    ActionRow(
        title = stringResource(R.string.backup_import_title),
        description = stringResource(R.string.backup_import_description),
        enabled = !state.backup.isBusy,
        onClick = actions.onImport
    )
    BackupStatusText(state.backup)
}
