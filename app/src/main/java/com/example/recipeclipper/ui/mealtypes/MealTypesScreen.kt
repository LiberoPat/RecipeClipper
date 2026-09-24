package com.example.recipeclipper.ui.mealtypes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * Meal types (#49), from the Week menu: each row's menu renames it, moves it up or down, and,
 * for the user's own, deletes it (its meals move to Dinner, and the confirmation says so).
 * "+ New meal type" expands inline, as "+ New list" does.
 */
@Composable
fun MealTypesScreen(
    onBack: () -> Unit,
    viewModel: MealTypesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize().safeDrawingPadding()
            ) {
                item {
                    BackButton(onBack)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.meal_types_title), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Hairline()
                }
                itemsIndexed(state.types, key = { _, type -> type.id }) { index, type ->
                    MealTypeRow(
                        type = type,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.types.lastIndex,
                        onRename = { viewModel.onRenameStart(type) },
                        onMoveUp = { viewModel.onMoveUp(type) },
                        onMoveDown = { viewModel.onMoveDown(type) },
                        onDelete = { viewModel.onDeleteStart(type) }
                    )
                    Hairline()
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    if (state.creating) {
                        NameField(
                            value = state.newName,
                            onValueChange = viewModel::onNewNameChange,
                            onCancel = viewModel::onCancelCreating,
                            onConfirm = viewModel::onCreate,
                            confirmLabel = stringResource(R.string.action_create)
                        )
                    } else {
                        TextButton(onClick = viewModel::onStartCreating) {
                            Text(stringResource(R.string.action_new_meal_type))
                        }
                    }
                }
            }
        }

        state.renaming?.let {
            AlertDialog(
                onDismissRequest = viewModel::onRenameDismissed,
                title = { Text(stringResource(R.string.rename_meal_type_title)) },
                text = {
                    OutlinedTextField(
                        value = state.renameText,
                        onValueChange = viewModel::onRenameTextChange,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("renameMealType")
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::onRenameConfirm, enabled = state.renameText.isNotBlank()) {
                        Text(stringResource(R.string.action_rename))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::onRenameDismissed) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
        state.deleting?.let { type ->
            AlertDialog(
                onDismissRequest = viewModel::onDeleteDismissed,
                title = { Text(stringResource(R.string.delete_meal_type_title, type.name)) },
                text = { Text(stringResource(R.string.delete_meal_type_body)) },
                confirmButton = {
                    TextButton(onClick = viewModel::onDeleteConfirm) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::onDeleteDismissed) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
    }
}

@Composable
private fun MealTypeRow(
    type: MealType,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(type.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("mealTypeMenu-${type.id}")) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    onClick = { menuOpen = false; onRename() }
                )
                if (canMoveUp) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_move_up)) },
                        onClick = { menuOpen = false; onMoveUp() }
                    )
                }
                if (canMoveDown) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_move_down)) },
                        onClick = { menuOpen = false; onMoveDown() }
                    )
                }
                if (!type.isBuiltIn) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.label_meal_type_name)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Box(Modifier.width(4.dp))
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(confirmLabel) }
        }
    }
}
