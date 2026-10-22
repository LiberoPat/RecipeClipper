package com.example.recipeclipper.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.recipeclipper.R
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.HISTORY_LIMIT
import com.example.recipeclipper.data.backup.BackupDestination
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Settings' rows (#286: moved from SettingsScreen.kt when each section got a page of its own).

/** An exclusive-choice row: a title, a one-line description, and a [RadioButton] that shows
 *  it against its siblings rather than a bare checkmark. The whole row is the tap target. */
@Composable
internal fun RadioRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
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
internal fun ActionRow(
    title: String,
    description: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
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

/** Progress, or the outcome of the last export or import, until the next one. Home's Restore
 *  (#150) shows its outcome with it too. */
@Composable
internal fun BackupStatusText(status: BackupStatus) {
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

/**
 * The automatic backup copy (#150): the switch, the folder, when the last copy was written (and
 * whether something is wrong), and "Back up now". Quiet lines, in the error colour only when
 * the copy has stopped working.
 */
@Composable
internal fun AutoBackupRows(
    row: AutoBackupRow,
    onEnabledChange: (Boolean) -> Unit,
    onChooseFolder: () -> Unit,
    onBackUpNow: () -> Unit
) {
    SwitchRow(
        title = stringResource(R.string.auto_backup_title),
        description = stringResource(R.string.auto_backup_description),
        checked = row.enabled,
        onCheckedChange = onEnabledChange
    )
    ActionRow(
        title = stringResource(R.string.auto_backup_folder_title),
        description = when (row.destination) {
            BackupDestination.NOT_CHOSEN -> stringResource(R.string.auto_backup_folder_not_chosen)
            else -> row.folderName?.takeIf { it.isNotEmpty() }
        },
        enabled = true,
        onClick = onChooseFolder
    )
    val lines = buildList {
        if (row.destination == BackupDestination.LOST) add(stringResource(R.string.auto_backup_folder_lost) to true)
        if (row.folderRefused) add(stringResource(R.string.auto_backup_folder_refused) to true)
        val last = row.lastBackupAt?.let {
            val locale = LocalConfiguration.current.locales[0]
            stringResource(
                R.string.auto_backup_last,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it))
            )
        } ?: stringResource(R.string.auto_backup_never)
        add(last to false)
        if (row.lastFailed && row.destination == BackupDestination.READY) add(stringResource(R.string.auto_backup_failed) to true)
        if (row.nudge) add(stringResource(R.string.auto_backup_nudge) to true)
    }
    lines.forEach { (text, alert) ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    ActionRow(
        title = stringResource(if (row.running) R.string.auto_backup_running else R.string.auto_backup_now),
        description = null,
        enabled = row.canBackUpNow,
        onClick = onBackUpNow
    )
}

/** Opens the share sheet on an export file, letting only the chosen app read it. */
internal fun shareExport(context: Context, uriString: String, title: String) {
    val uri = uriString.toUri()
    val send = Intent(Intent.ACTION_SEND).apply {
        // With photos (#116) the export is a zip.
        type = if (uriString.endsWith(".zip")) "application/zip" else "application/json"
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
internal val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream", "application/zip")

/** An independent toggle: a title, a one-line description, and a [Switch] — never a
 *  checkmark, which would read as an exclusive choice among its siblings. */
@Composable
internal fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
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
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Chef mode (#100): one switch. Where the phone can't write short steps it is disabled, with one
 * line saying why (naming the phones it needs, #144, so it doesn't read like a bug); where it can,
 * a line names the recipe languages it writes.
 */
@Composable
internal fun ChefModeRow(state: SettingsUiState, onChefModeChange: (Boolean) -> Unit) {
    SwitchRow(
        title = stringResource(R.string.chef_mode_title),
        description = stringResource(R.string.chef_mode_description),
        checked = state.chefMode && state.chefModeAvailable,
        onCheckedChange = onChefModeChange,
        enabled = state.chefModeAvailable
    )
    val support = state.chefSupport ?: return
    val locale = LocalConfiguration.current.locales[0]
    val note = when (support) {
        is ChefSupport.Available -> stringResource(
            R.string.chef_mode_languages,
            support.languages.map { Locale.forLanguageTag(it).getDisplayLanguage(locale) }.sorted().joinToString(", ")
        )
        ChefSupport.NotReady -> stringResource(R.string.chef_mode_not_ready)
        // NotEnabled is Apple Intelligence switched off: iOS only, as ML Kit has no such state.
        ChefSupport.Unsupported, ChefSupport.NotEnabled -> stringResource(R.string.chef_mode_unsupported)
    }
    Text(
        note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
internal fun UnitSystem.settingsLabel(): String = stringResource(
    when (this) {
        UnitSystem.AS_WRITTEN -> R.string.unit_as_written
        UnitSystem.OUNCES -> R.string.unit_ounces
        UnitSystem.METRIC -> R.string.unit_metric
    }
)

@Composable
internal fun UnitSystem.settingsDescription(): String = stringResource(
    when (this) {
        UnitSystem.AS_WRITTEN -> R.string.unit_as_written_description
        UnitSystem.OUNCES -> R.string.unit_ounces_description
        UnitSystem.METRIC -> R.string.unit_metric_description
    }
)

@Composable
internal fun TemperatureUnit.settingsLabel(): String = stringResource(
    when (this) {
        TemperatureUnit.AS_WRITTEN -> R.string.temperature_as_written
        TemperatureUnit.CELSIUS -> R.string.temperature_celsius
        TemperatureUnit.FAHRENHEIT -> R.string.temperature_fahrenheit
    }
)

@Composable
internal fun TemperatureUnit.settingsDescription(): String = stringResource(
    when (this) {
        TemperatureUnit.AS_WRITTEN -> R.string.temperature_as_written_description
        TemperatureUnit.CELSIUS -> R.string.temperature_celsius_description
        TemperatureUnit.FAHRENHEIT -> R.string.temperature_fahrenheit_description
    }
)
