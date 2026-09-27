package com.example.recipeclipper.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.UseUpChange
import com.example.recipeclipper.data.model.UseUpRow

/**
 * Using up the pantry at the end of cooking (#147): the sheet [viewModel] opens, and the Undo
 * snackbar after a confirm, on the recipe screen's [snackbarHostState].
 */
@Composable
internal fun PantryUseUpUi(viewModel: PantryUseUpViewModel, snackbarHostState: SnackbarHostState) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message = stringResource(R.string.snackbar_pantry_used_up)
    val undoLabel = stringResource(R.string.action_undo)
    val updated = state.updated
    LaunchedEffect(updated) {
        if (updated == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(message, actionLabel = undoLabel)
        if (result == SnackbarResult.ActionPerformed) viewModel.onUndo() else viewModel.onUpdatedDismissed()
    }
    state.sheet?.let { sheet ->
        UseUpSheetView(sheet, viewModel::onToggle, viewModel::onChoice, viewModel::onConfirm, viewModel::onDismissed)
    }
}

/**
 * "Update the pantry": one row per pantry item the ticked lines used. A worked-out change
 * ("2 lb → 1 lb") has a checkbox, ticked; one that can't be worked out shows its lines as
 * written with keep, running low or out, keep chosen. One button applies it all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UseUpSheetView(
    sheet: UseUpSheet,
    onToggle: (Long) -> Unit,
    onChoice: (Long, UseUpChoice) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().testTag("useUp")
        ) {
            item(key = "title") {
                SectionHeading(stringResource(R.string.use_up_title))
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.use_up_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            items(sheet.rows, key = { "useUp-${it.item.id}" }) { row ->
                when (val change = row.change) {
                    is UseUpChange.Subtract -> SubtractRow(row, change, row.item.id in sheet.ticked, onToggle)
                    UseUpChange.Ask -> AskRow(row, sheet.choice(row.item.id), onChoice)
                }
            }
            item(key = "confirm") {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("useUpButton")
                ) {
                    Text(stringResource(R.string.action_use_up))
                }
            }
        }
    }
}

@Composable
private fun SubtractRow(row: UseUpRow, change: UseUpChange.Subtract, ticked: Boolean, onToggle: (Long) -> Unit) {
    val name = row.item.name
    val after = change.after
    val shown = if (after != null) stringResource(R.string.use_up_change, change.before, after)
    else stringResource(R.string.use_up_used_up, change.before)
    // "→" reads badly aloud: TalkBack hears "from 2 lb to 1 lb".
    val spoken = if (after != null) stringResource(R.string.cd_use_up_change, name, change.before, after)
    else stringResource(R.string.cd_use_up_used_up, name, change.before)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onToggle(row.item.id) })
            .semantics { contentDescription = spoken }
            .padding(vertical = 6.dp)
            .testTag("useUp-${row.item.id}")
    ) {
        Checkbox(checked = ticked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            UsedLines(row.lines)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskRow(row: UseUpRow, choice: UseUpChoice, onChoice: (Long, UseUpChoice) -> Unit) {
    val name = row.item.name
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("useUp-${row.item.id}")) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        UsedLines(row.lines)
        Text(
            stringResource(R.string.use_up_cant_work_out),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.selectableGroup()
        ) {
            for (option in UseUpChoice.entries) {
                val label = stringResource(option.label())
                val spoken = stringResource(R.string.cd_use_up_choice, name, label)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .selectable(selected = option == choice, role = Role.RadioButton, onClick = { onChoice(row.item.id, option) })
                        .semantics { contentDescription = spoken }
                        .padding(end = 4.dp)
                        .testTag("useUp-${row.item.id}-${option.name.lowercase()}")
                ) {
                    RadioButton(selected = option == choice, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The ticked lines that used the item, as the recipe showed them. */
@Composable
private fun UsedLines(lines: List<String>) {
    Text(
        lines.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun UseUpChoice.label(): Int = when (this) {
    UseUpChoice.KEEP -> R.string.use_up_keep
    UseUpChoice.LOW -> R.string.use_up_low
    UseUpChoice.OUT -> R.string.use_up_out
}
