package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.Servings
import com.example.recipeclipper.data.model.ServingsScale
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.data.model.YieldKind

/**
 * Servings and units, always in view: `Serves − 6 +` on the left and the unit choice on
 * the right. Servings is per-recipe; the unit choice is the user's global default, and
 * the menu says so. Changing servings is one tap on − or +, with nothing to open.
 */
@Composable
internal fun ServesUnitsRow(
    servings: ServingsScale?,
    yieldText: String?,
    unitSystem: UnitSystem,
    onServingsChange: (Int) -> Unit,
    onUnitSystemChange: (UnitSystem) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            if (servings != null) {
                ServesStepper(servings, Servings.kind(yieldText), onServingsChange)
            } else {
                // No number in the yield to scale from: show what the recipe says, if anything.
                Text(yieldText.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            }
            UnitsMenu(system = unitSystem, onSystemChange = onUnitSystemChange)
        }
        if (servings != null && servings.target != servings.base && yieldText != null) {
            val bareCount = Servings.bareCount(yieldText)
            val describedYield = if (bareCount != null) {
                pluralStringResource(R.plurals.servings, bareCount, bareCount)
            } else {
                yieldText
            }
            Text(
                stringResource(R.string.original_servings, describedYield),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Hairline()
    }
}

@Composable
private fun ServesStepper(servings: ServingsScale, kind: YieldKind, onChange: (Int) -> Unit) {
    // "Makes 16" for a yield that counts things made (cookies, loaves); only the words change.
    val makes = kind == YieldKind.MAKES
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (makes) R.string.label_makes else R.string.label_serves),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.padding(start = 6.dp))
        val decreaseDescription = stringResource(
            if (makes) R.string.cd_decrease_amount else R.string.cd_decrease_servings
        )
        FilledTonalIconButton(
            onClick = { onChange(servings.target - 1) },
            enabled = servings.target > 1,
            modifier = Modifier.semantics { contentDescription = decreaseDescription }
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            "${servings.target}",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 32.dp)
        )
        val increaseDescription = stringResource(
            if (makes) R.string.cd_increase_amount else R.string.cd_increase_servings
        )
        FilledTonalIconButton(
            onClick = { onChange(servings.target + 1) },
            enabled = servings.target < Servings.MAX,
            modifier = Modifier.semantics { contentDescription = increaseDescription }
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/**
 * The current unit choice as tappable text; tapping opens the four, mutually exclusive
 * options. Everything else that used to live in this menu ("Also convert liquids", "Dark
 * while cooking") has moved to the Settings screen — this menu is exclusive-choice only now,
 * so a plain ✓ on each row is unambiguous.
 */
@Composable
private fun UnitsMenu(
    system: UnitSystem,
    onSystemChange: (UnitSystem) -> Unit
) {
    // Transient: a menu closing on rotation costs nothing, so this needn't live in the ViewModel.
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_change_units)) { open = true }
                .padding(horizontal = 4.dp, vertical = 12.dp)
        ) {
            Text(
                system.shortLabel(),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                " ▾",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.units_menu_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            UnitSystem.values().forEach { option ->
                val selected = option == system
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                option.shortLabel(),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            )
                            Text(
                                option.description(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = {
                        if (selected) {
                            Text("✓", color = MaterialTheme.colorScheme.tertiary)
                        }
                    },
                    onClick = {
                        onSystemChange(option)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun UnitSystem.shortLabel(): String = stringResource(
    when (this) {
        UnitSystem.AS_WRITTEN -> R.string.unit_as_written
        UnitSystem.GRAMS -> R.string.unit_grams
        UnitSystem.OUNCES -> R.string.unit_ounces
        UnitSystem.METRIC -> R.string.unit_metric
    }
)

// Oven temperature is a separate setting now (Settings screen), so these descriptions no
// longer claim it.
@Composable
private fun UnitSystem.description(): String = stringResource(
    when (this) {
        UnitSystem.AS_WRITTEN -> R.string.unit_as_written_description
        UnitSystem.GRAMS -> R.string.unit_grams_description
        UnitSystem.OUNCES -> R.string.unit_ounces_description
        UnitSystem.METRIC -> R.string.unit_metric_description
    }
)
