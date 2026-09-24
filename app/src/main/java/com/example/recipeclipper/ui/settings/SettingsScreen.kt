package com.example.recipeclipper.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
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
 * screen exists (see CLAUDE.md). Three sections: Units (the four [UnitSystem] options, plus
 * "Also convert liquids" for Grams/Ounces only), Oven temperature (the three
 * [TemperatureUnit] options, independent of Units), and Appearance ("Dark while cooking").
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize()
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

                if (state.unitSystem == UnitSystem.GRAMS || state.unitSystem == UnitSystem.OUNCES) {
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

/** An independent toggle: a title, a one-line description, and a [Switch] — never a
 *  checkmark, which would read as an exclusive choice among its siblings. */
@Composable
private fun SwitchRow(
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
        UnitSystem.GRAMS -> R.string.unit_grams
        UnitSystem.OUNCES -> R.string.unit_ounces
        UnitSystem.METRIC -> R.string.unit_metric
    }
)

@Composable
private fun UnitSystem.settingsDescription(): String = stringResource(
    when (this) {
        UnitSystem.AS_WRITTEN -> R.string.unit_as_written_description
        UnitSystem.GRAMS -> R.string.unit_grams_description
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
