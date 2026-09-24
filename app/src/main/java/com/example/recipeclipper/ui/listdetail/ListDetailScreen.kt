package com.example.recipeclipper.ui.listdetail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.ui.common.RecipeRow
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * One list's recipes, most recently added first. Renaming and deleting the list live in the
 * overflow menu here, mirroring how a recipe is deleted from the recipe screen.
 *
 * Removing a recipe from the list is deliberately not offered here: membership is edited in
 * one place, the save-to-list sheet, rather than in two that could drift apart.
 */
@Composable
fun ListDetailScreen(
    onBack: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    viewModel: ListDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }

    // The list is gone the moment the delete lands, so leave rather than show an empty shell.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    RecipeClipperTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        BackButton(onBack)
                        Spacer(Modifier.weight(1f))
                        state.list?.let { list ->
                            ListOverflowMenu(
                                listName = list.name,
                                // Favorites is the only list that can't be deleted — "saved"
                                // is built around it. Lunch, Dinner, Desserts, Breakfast and
                                // Snacks are starting suggestions and delete like any other.
                                canDelete = !list.isFavorites,
                                onRename = viewModel::onStartRenaming,
                                onDelete = viewModel::onDelete
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.list?.name.orEmpty(),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Hairline()
                    Spacer(Modifier.height(4.dp))
                }

                if (state.loaded && state.recipes.isEmpty()) {
                    item {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            stringResource(R.string.list_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(state.recipes.size, key = { state.recipes[it].id }) { index ->
                    val recipe = state.recipes[index]
                    RecipeRow(recipe, now, onClick = { onOpenRecipe(recipe.id) })
                }
            }
        }
    }

    if (state.renaming) {
        AlertDialog(
            onDismissRequest = viewModel::onCancelRenaming,
            title = { Text(stringResource(R.string.rename_list_title)) },
            text = {
                OutlinedTextField(
                    value = state.renameValue,
                    onValueChange = viewModel::onRenameValueChange,
                    label = { Text(stringResource(R.string.label_list_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::onRenameConfirm,
                    enabled = state.renameValue.isNotBlank()
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onCancelRenaming) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** Rename, and — for user lists only — Delete behind a confirm dialog naming the list. */
@Composable
private fun ListOverflowMenu(
    listName: String,
    canDelete: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_rename)) },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = {
                expanded = false
                onRename()
            }
        )
        if (canDelete) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete_list)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    confirming = true
                }
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.delete_list_title, listName)) },
            text = { Text(stringResource(R.string.delete_list_body)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
