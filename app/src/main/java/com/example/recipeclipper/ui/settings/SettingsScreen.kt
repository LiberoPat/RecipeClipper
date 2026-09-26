package com.example.recipeclipper.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.HISTORY_LIMIT
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * The app's one settings surface. Everything here is a global default, applied to every
 * recipe, injected straight from [AppPreferences][com.example.recipeclipper.data.local.AppPreferences]
 * via [SettingsViewModel] rather than through the repository.
 *
 * Every control signals its own behaviour: exclusive choices are [RadioButton] rows, toggles
 * are [Switch] rows — never a bare checkmark for either, which is the whole reason this
 * screen exists (see CLAUDE.md). The sections: Units (the three [UnitSystem] options, plus
 * "Also convert liquids" for Ounces only), Oven temperature (the three [TemperatureUnit]
 * options, independent of Units), Appearance ("Dark while cooking"), Pantry ("Expiry
 * reminders", #52, only with the `mealPlan` flag on), and Your recipes (Export and Import,
 * #26: actions, so plain rows).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDeveloperSettings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onImportPicked(it.toString()) }
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

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize()
                    // Edge-to-edge: the background fills behind the bars, the rows stay clear
                    // of them, the display cutout and the keyboard.
                    .safeDrawingPadding()
            ) {
                item {
                    BackButton(onBack)
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    SectionHeading(stringResource(R.string.settings_section_units))
                    Spacer(Modifier.height(4.dp))
                }

                items(UnitSystem.values().toList()) { option ->
                    RadioRow(
                        title = option.settingsLabel(),
                        description = option.settingsDescription(),
                        selected = option == state.unitSystem,
                        onClick = { viewModel.onUnitSystemChange(option) }
                    )
                }

                if (state.unitSystem == UnitSystem.OUNCES) {
                    item {
                        SwitchRow(
                            title = stringResource(R.string.convert_liquids_title),
                            description = stringResource(R.string.convert_liquids_description),
                            checked = state.convertLiquids,
                            onCheckedChange = viewModel::onConvertLiquidsChange
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Hairline()
                    Spacer(Modifier.height(16.dp))
                    SectionHeading(stringResource(R.string.settings_section_oven_temperature))
                    Spacer(Modifier.height(4.dp))
                }

                items(TemperatureUnit.values().toList()) { option ->
                    RadioRow(
                        title = option.settingsLabel(),
                        description = option.settingsDescription(),
                        selected = option == state.temperatureUnit,
                        onClick = { viewModel.onTemperatureUnitChange(option) }
                    )
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Hairline()
                    Spacer(Modifier.height(16.dp))
                    SectionHeading(stringResource(R.string.settings_section_appearance))
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        title = stringResource(R.string.dark_while_cooking_title),
                        description = stringResource(R.string.dark_while_cooking_description),
                        checked = state.darkWhileCooking,
                        onCheckedChange = viewModel::onDarkWhileCookingChange
                    )
                }

                if (state.showsSteps) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Hairline()
                        Spacer(Modifier.height(16.dp))
                        SectionHeading(stringResource(R.string.settings_section_steps))
                        Spacer(Modifier.height(4.dp))
                        SwitchRow(
                            title = stringResource(R.string.amounts_in_steps_title),
                            description = stringResource(R.string.amounts_in_steps_description),
                            checked = state.amountsInSteps,
                            onCheckedChange = viewModel::onAmountsInStepsChange
                        )
                    }
                }

                if (state.showsPantry) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Hairline()
                        Spacer(Modifier.height(16.dp))
                        SectionHeading(stringResource(R.string.settings_section_pantry))
                        Spacer(Modifier.height(4.dp))
                        SwitchRow(
                            title = stringResource(R.string.expiry_reminders_title),
                            description = stringResource(R.string.expiry_reminders_description),
                            checked = state.expiryReminders,
                            onCheckedChange = { on ->
                                // Asking is a platform effect, so it lives here: only when the cook
                                // turns reminders on, never on launch (#52).
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
                        if (state.expiryRemindersDenied && !state.expiryReminders) {
                            Text(
                                stringResource(R.string.expiry_reminders_denied),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Hairline()
                    Spacer(Modifier.height(16.dp))
                    SectionHeading(stringResource(R.string.settings_section_your_recipes))
                    Spacer(Modifier.height(4.dp))
                    // Actions, not choices: plain rows, no radio or switch.
                    ActionRow(
                        title = stringResource(R.string.backup_export_title),
                        description = stringResource(R.string.backup_export_description),
                        enabled = !backup.isBusy,
                        onClick = viewModel::onExport
                    )
                    ActionRow(
                        title = stringResource(R.string.backup_import_title),
                        description = stringResource(R.string.backup_import_description),
                        enabled = !backup.isBusy,
                        onClick = { importPicker.launch(IMPORT_MIME_TYPES) }
                    )
                    BackupStatusText(backup)
                }

                state.unlock?.let { row ->
                    item {
                        UnlockSection(row, state.unlockNotice, viewModel::onUnlock, viewModel::onRestore)
                    }
                }

                item {
                    // The version, quietly at the foot. Seven taps open Developer settings (#87);
                    // the count is the ViewModel's, so it survives a rotation.
                    Text(
                        stringResource(R.string.settings_version, state.appVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 24.dp)
                            .clickable { if (viewModel.onVersionTapped()) onOpenDeveloperSettings() }
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** An exclusive-choice row: a title, a one-line description, and a [RadioButton] that shows
 *  it against its siblings rather than a bare checkmark. The whole row is the tap target. */
@Composable
private fun RadioRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A row that does something when tapped: a title and, usually, a one-line description. */
@Composable
internal fun ActionRow(title: String, description: String?, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Progress, or the outcome of the last export or import, until the next one. */
@Composable
private fun BackupStatusText(status: BackupStatus) {
    val resources = LocalResources.current
    val text = when (status) {
        BackupStatus.Idle, is BackupStatus.ReadyToShare -> null
        BackupStatus.Exporting -> stringResource(R.string.backup_exporting)
        BackupStatus.Importing -> stringResource(R.string.backup_importing)
        is BackupStatus.Imported -> importSummaryText(resources, status.summary)
        is BackupStatus.Failed -> when (val error = status.error) {
            BackupError.NotABackup -> stringResource(R.string.backup_error_not_a_backup)
            is BackupError.NewerVersion -> stringResource(R.string.backup_error_newer_version, error.found)
            is BackupError.Malformed -> stringResource(R.string.backup_error_malformed, error.detail)
            BackupError.ReadFailed -> stringResource(R.string.backup_error_read_failed)
            BackupError.SaveFailed -> stringResource(R.string.backup_error_save_failed)
            BackupError.ExportFailed -> stringResource(R.string.backup_error_export_failed)
        }
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (status is BackupStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}

private fun importSummaryText(resources: android.content.res.Resources, summary: ImportSummary): String {
    val recipes = resources.getQuantityString(R.plurals.backup_recipes, summary.recipesAdded, summary.recipesAdded)
    val lists = resources.getQuantityString(R.plurals.backup_lists, summary.listsAdded, summary.listsAdded)
    val parts = mutableListOf(
        when {
            summary.recipesAdded == 0 && summary.listsAdded == 0 -> resources.getString(R.string.backup_imported_nothing)
            summary.listsAdded == 0 -> resources.getString(R.string.backup_imported_recipes, recipes)
            else -> resources.getString(R.string.backup_imported, recipes, lists)
        }
    )
    if (summary.recipesAlreadyHere > 0 && (summary.recipesAdded > 0 || summary.listsAdded > 0)) {
        parts += resources.getQuantityString(
            R.plurals.backup_already_here, summary.recipesAlreadyHere, summary.recipesAlreadyHere
        )
    }
    if (summary.recipesSkipped > 0) {
        val freeLimit = summary.freeLimit
        parts += if (freeLimit != null) {
            resources.getQuantityString(
                R.plurals.backup_skipped_free, summary.recipesSkipped, summary.recipesSkipped, freeLimit
            )
        } else {
            resources.getQuantityString(
                R.plurals.backup_skipped, summary.recipesSkipped, summary.recipesSkipped, HISTORY_LIMIT
            )
        }
    }
    return parts.joinToString(" ")
}

/** Opens the share sheet on an export file, letting only the chosen app read it. */
private fun shareExport(context: Context, uriString: String, title: String) {
    val uri = uriString.toUri()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: ActivityNotFoundException) {
        // Nothing can receive a file: nothing to do, and the export is still in the cache.
    }
}

/** What the file picker offers. Some file managers label .json as a generic binary. */
private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

/** An independent toggle: a title, a one-line description, and a [Switch] — never a
 *  checkmark, which would read as an exclusive choice among its siblings. */
@Composable
internal fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun UnitSystem.settingsLabel(): String = stringResource(
    when (this) {
        UnitSystem.AS_WRITTEN -> R.string.unit_as_written
        UnitSystem.OUNCES -> R.string.unit_ounces
        UnitSystem.METRIC -> R.string.unit_metric
    }
)

@Composable
private fun UnitSystem.settingsDescription(): String = stringResource(
    when (this) {
        UnitSystem.AS_WRITTEN -> R.string.unit_as_written_description
        UnitSystem.OUNCES -> R.string.unit_ounces_description
        UnitSystem.METRIC -> R.string.unit_metric_description
    }
)

@Composable
private fun TemperatureUnit.settingsLabel(): String = stringResource(
    when (this) {
        TemperatureUnit.AS_WRITTEN -> R.string.temperature_as_written
        TemperatureUnit.CELSIUS -> R.string.temperature_celsius
        TemperatureUnit.FAHRENHEIT -> R.string.temperature_fahrenheit
    }
)

@Composable
private fun TemperatureUnit.settingsDescription(): String = stringResource(
    when (this) {
        TemperatureUnit.AS_WRITTEN -> R.string.temperature_as_written_description
        TemperatureUnit.CELSIUS -> R.string.temperature_celsius_description
        TemperatureUnit.FAHRENHEIT -> R.string.temperature_fahrenheit_description
    }
)
