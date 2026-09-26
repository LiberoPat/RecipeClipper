package com.example.recipeclipper.ui.sharefile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.backup.BackupError
import com.example.recipeclipper.data.backup.PantryDestination
import com.example.recipeclipper.ui.recipe.SectionHeading

/**
 * "Add from a shared file" (#149, phase 2), over whatever is on screen while
 * [ReceiveFileUiState.open]: the recipes, grocery items (each with its recipe) and pantry items
 * the file holds, each ticked to start, the pantry items' destination, and one Add. Once added,
 * [onAdded] says where they went, so the app can show them.
 */
@Composable
fun ReceiveFileHost(viewModel: ReceiveFileViewModel, onAdded: (ReceivedWhere) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.added, state.skippedFree) {
        val added = state.added ?: return@LaunchedEffect
        if (state.skippedFree != null) return@LaunchedEffect
        viewModel.onDismiss()
        onAdded(added)
    }
    if (!state.open) return
    ReceiveFileSheet(
        state = state,
        onToggle = viewModel::onToggle,
        onPantryTo = viewModel::onPantryTo,
        onAdd = viewModel::onAdd,
        onDone = {
            val added = state.added
            viewModel.onDismiss()
            if (added != null) onAdded(added)
        },
        onDismiss = viewModel::onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveFileSheet(
    state: ReceiveFileUiState,
    onToggle: (String) -> Unit,
    onPantryTo: (PantryDestination) -> Unit,
    onAdd: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val resources = LocalResources.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().testTag("receiveFile")
        ) {
            item(key = "title") {
                SectionHeading(stringResource(R.string.receive_file_title))
                Spacer(Modifier.height(8.dp))
            }
            val skipped = state.skippedFree
            if (skipped != null) {
                item(key = "skipped") {
                    Quiet(resources.getQuantityString(R.plurals.receive_file_skipped_free, skipped.first, skipped.first, skipped.second))
                    Spacer(Modifier.height(16.dp))
                    WideButton(stringResource(R.string.action_done), enabled = true, tag = "receiveFileDone", onClick = onDone)
                }
                return@LazyColumn
            }
            state.error?.let { error ->
                item(key = "error") {
                    Text(
                        error.message(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            if (state.isEmpty) {
                if (state.error == null) item(key = "empty") { Quiet(stringResource(R.string.receive_file_empty)) }
                return@LazyColumn
            }
            section("recipes", R.string.tab_recipes, state.recipes, state.unticked, onToggle)
            section("groceries", R.string.tab_groceries, state.groceries, state.unticked, onToggle)
            section("pantry", R.string.tab_pantry, state.pantry, state.unticked, onToggle)
            if (state.pantry.isNotEmpty()) {
                item(key = "pantryTo") {
                    Column(Modifier.padding(top = 4.dp)) {
                        Destination(R.string.receive_file_to_pantry, PantryDestination.PANTRY, state.pantryTo, onPantryTo)
                        Destination(R.string.receive_file_to_groceries, PantryDestination.GROCERIES, state.pantryTo, onPantryTo)
                    }
                }
            }
            item(key = "add") {
                Spacer(Modifier.height(16.dp))
                WideButton(
                    stringResource(R.string.action_add),
                    enabled = state.tickedCount > 0 && !state.adding && state.added == null,
                    tag = "receiveFileAdd",
                    onClick = onAdd
                )
            }
        }
    }
}

private fun LazyListScope.section(
    key: String,
    heading: Int,
    rows: List<ReceivedRow>,
    unticked: Set<String>,
    onToggle: (String) -> Unit
) {
    if (rows.isEmpty()) return
    item(key = "heading-$key") {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(heading), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
    }
    items(rows, key = { it.key }) { row ->
        val ticked = row.key !in unticked
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onToggle(row.key) })
                .padding(vertical = 4.dp)
                .testTag("receiveRow-${row.key}")
        ) {
            Checkbox(checked = ticked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(row.text, style = MaterialTheme.typography.bodyLarge)
                row.detail?.let { Quiet(it) }
            }
        }
    }
}

@Composable
private fun Destination(
    label: Int,
    destination: PantryDestination,
    selected: PantryDestination,
    onSelect: (PantryDestination) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = destination == selected, role = Role.RadioButton, onClick = { onSelect(destination) })
            .padding(vertical = 2.dp)
            .testTag("receivePantryTo-${destination.name}")
    ) {
        RadioButton(selected = destination == selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WideButton(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    ) {
        Text(label)
    }
}

@Composable
private fun BackupError.message(): String = when (this) {
    BackupError.NotABackup -> stringResource(R.string.backup_error_not_a_backup)
    is BackupError.NewerVersion -> stringResource(R.string.backup_error_newer_version, found)
    is BackupError.Malformed -> stringResource(R.string.backup_error_malformed, detail)
    BackupError.ReadFailed -> stringResource(R.string.backup_error_read_failed)
    BackupError.SaveFailed -> stringResource(R.string.backup_error_save_failed)
    BackupError.ExportFailed -> stringResource(R.string.backup_error_export_failed)
}
