package com.example.recipeclipper.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * Settings' index (#286): one row per section, in order, each with its icon, its current value
 * (muted, on the right) and a chevron; a row opens its section's own page
 * ([SettingsSectionScreen]). Everything set there is a global default, applied to every recipe,
 * injected straight from [AppPreferences][com.example.recipeclipper.data.local.AppPreferences]
 * via [SettingsViewModel], which this collects, so a value is never stale. The version sits
 * quietly at the foot; seven taps on it open Developer settings (#87).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit = {},
    onOpenDeveloperSettings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize()
                    // Edge-to-edge: the background fills behind the bars, the rows stay clear of
                    // them and the display cutout.
                    .safeDrawingPadding()
            ) {
                item(key = "title") {
                    BackButton(onBack)
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                }
                items(state.sections, key = { "section-${it.route}" }) { section ->
                    IndexRow(
                        section,
                        value = sectionValue(section, state),
                        alert = section == SettingsSection.YOUR_RECIPES && state.autoBackup?.needsAttention == true,
                        onClick = { onOpenSection(section) }
                    )
                }
                item(key = "version") {
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

/**
 * One section on the index: its icon, its name, its value and a chevron, the whole row one
 * button. The value takes at most half the row and wraps, so it never runs into the name; at the
 * largest text sizes it moves under the name instead.
 */
@Composable
private fun IndexRow(section: SettingsSection, value: String?, alert: Boolean, onClick: () -> Unit) {
    val valueColor = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val stacked = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val valueMax = maxWidth * 0.5f
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settingsSection-${section.route}")
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 14.dp)
        ) {
            Icon(
                painterResource(section.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(section.title), style = MaterialTheme.typography.bodyLarge)
                if (stacked && value != null) {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
                }
            }
            if (!stacked && value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 12.dp).widthIn(max = valueMax)
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp).clearAndSetSemantics {}
            )
        }
    }
    Hairline()
}

/** A section's current value as the index shows it, from the settings themselves; Help has none. */
@Composable
private fun sectionValue(section: SettingsSection, state: SettingsUiState): String? = when (section) {
    SettingsSection.UNITS -> state.unitSystem.settingsLabel()
    SettingsSection.OVEN_TEMPERATURE -> state.temperatureUnit.settingsLabel()
    // Dark mode is the system's call: the one choice here is cook mode's.
    SettingsSection.APPEARANCE -> stringResource(
        if (state.darkWhileCooking) R.string.dark_while_cooking_title else R.string.settings_value_system_theme
    )
    SettingsSection.STEPS -> stringResource(
        if (state.amountsInSteps) R.string.settings_value_amounts_on else R.string.settings_value_amounts_off
    )
    SettingsSection.PANTRY -> stringResource(
        if (state.expiryReminders) R.string.settings_value_reminders_on else R.string.settings_value_reminders_off
    )
    // The automatic copy's "Last backed up", as its page says it, but how long ago.
    SettingsSection.YOUR_RECIPES -> state.autoBackup?.let { row ->
        row.lastBackupAt?.let { at ->
            val now = System.currentTimeMillis()
            stringResource(
                R.string.auto_backup_last,
                DateUtils.getRelativeTimeSpanString(minOf(at, now), now, DateUtils.MINUTE_IN_MILLIS).toString()
            )
        } ?: stringResource(R.string.auto_backup_never)
    }
    SettingsSection.HELP -> null
    SettingsSection.UNLIMITED -> if (state.unlock?.unlocked == true) stringResource(R.string.settings_value_unlocked) else null
}
