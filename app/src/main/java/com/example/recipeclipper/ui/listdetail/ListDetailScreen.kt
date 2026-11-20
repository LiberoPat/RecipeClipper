package com.example.recipeclipper.ui.listdetail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Snackbar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.ui.common.ConfirmDeleteRecipesDialog
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.common.MenuItemText
import com.example.recipeclipper.ui.common.RecipeRow
import com.example.recipeclipper.ui.common.RowActionSet
import com.example.recipeclipper.ui.common.RowWithMenu
import com.example.recipeclipper.ui.common.UndoSnackbarEffect
import com.example.recipeclipper.ui.plan.AddToPlanBottomSheet
import com.example.recipeclipper.ui.plan.AddToPlanViewModel
import com.example.recipeclipper.ui.print.PrintEffect
import com.example.recipeclipper.ui.print.PrintViewModel
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.printLabels
import com.example.recipeclipper.ui.recipe.shareLabels
import com.example.recipeclipper.ui.recipes.SelectionBar
import com.example.recipeclipper.ui.recipes.shareRecipeText
import com.example.recipeclipper.ui.savetolist.SaveToListBottomSheet
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * One list's recipes, most recently added first. Renaming and deleting the list live in the
 * overflow menu here, mirroring how a recipe is deleted from the recipe screen.
 *
 * A long press on a row opens its menu (#376) and Select starts selecting several recipes (#377).
 * Taking recipes off this list is offered there, and only there, beside the save-to-list sheet
 * (the one place membership is otherwise edited): leaving a list is not a deletion.
 */
@Composable
fun ListDetailScreen(
    onBack: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    viewModel: ListDetailViewModel = hiltViewModel(),
    // "Make a cookbook" (#318); null (the flag off, or a screen test without it) hides it.
    printViewModel: PrintViewModel? = null,
    /** A long press on a row opens its menu (#376). */
    rowActions: Boolean = LocalFlagValues.current.isOn(Flag.ROW_ACTIONS),
    /** Selecting several recipes (#377): Select in the overflow menu and in a row's menu. */
    multiSelect: Boolean = LocalFlagValues.current.isOn(Flag.MULTI_SELECT),
    /** The sheet behind a row menu's Add to list (#376, #377). */
    saveViewModel: SaveToListViewModel? = if (rowActions || multiSelect) hiltViewModel() else null,
    /** The sheet behind a row menu's Add to plan (#376). */
    planViewModel: AddToPlanViewModel? = if (rowActions) hiltViewModel() else null,
    // A parameter so a test can watch the one snackbar a burst of deletes shares.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val context = LocalContext.current
    var saveSheetOpen by rememberSaveable { mutableStateOf(false) }
    var planSheetOpen by rememberSaveable { mutableStateOf(false) }
    // Back leaves selecting first (#377), then the screen.
    BackHandler(enabled = state.selecting, onBack = viewModel::onExitSelection)

    // The one Undo of a burst of deletes (#268, #345, #377), as Recipes shows it.
    val pending = state.pendingDeletes
    val deletedMessage = when {
        pending.isEmpty() -> null
        pending.size == 1 -> stringResource(R.string.snackbar_deleted_one, pending.single())
        else -> pluralStringResource(R.plurals.snackbar_deleted_many, pending.size, pending.size)
    }
    UndoSnackbarEffect(
        state.deleteBatch, deletedMessage, stringResource(R.string.action_undo), snackbarHostState,
        viewModel::onUndoDelete, viewModel::onSnackbarDismissed
    )
    // The Undo of a delete that the free library's limit refused (#345): its own outcome, said once.
    val refusedMessage = stringResource(R.string.snackbar_restore_full)
    LaunchedEffect(state.restoreRefused) {
        if (state.restoreRefused) {
            viewModel.onRestoreRefusedShown()
            snackbarHostState.showSnackbar(refusedMessage)
        }
    }
    val printFailedMessage = stringResource(R.string.print_failed)
    val contentsHeading = stringResource(R.string.cookbook_contents)
    var makingCookbook = false
    if (printViewModel != null) {
        PrintEffect(printViewModel) { snackbarHostState.showSnackbar(printFailedMessage) }
        makingCookbook = printViewModel.uiState.collectAsStateWithLifecycle().value.busy
    }
    val now = remember { System.currentTimeMillis() }

    // The list is gone the moment the delete lands, so leave rather than show an empty shell.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    RecipeClipperTheme {
        if (saveSheetOpen && saveViewModel != null) {
            SaveToListBottomSheet(saveViewModel, onDismiss = { saveSheetOpen = false })
        }
        if (planSheetOpen && planViewModel != null) {
            AddToPlanBottomSheet(planViewModel, onDismiss = { planSheetOpen = false })
        }
        if (state.confirmingDelete.isNotEmpty()) {
            ConfirmDeleteRecipesDialog(
                state.confirmingDelete,
                onConfirm = viewModel::onConfirmDelete,
                onCancel = viewModel::onCancelDelete
            )
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                // Edge-to-edge: the background fills behind the bars, the rows stay clear of them,
                // the display cutout and the keyboard.
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    // Selecting several recipes (#377): the count, Done, and what to do with them.
                    if (state.selecting) {
                        SelectionBar(
                            count = state.selectedIds.size,
                            onDone = viewModel::onExitSelection,
                            onAddToList = if (saveViewModel == null) null else {
                                {
                                    saveViewModel.setRecipes(state.selectedIds.toList())
                                    saveSheetOpen = true
                                }
                            },
                            onRemoveFromList = viewModel::onRemoveSelectedFromList,
                            onDelete = viewModel::onRequestDeleteSelected
                        )
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
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
                                        onSelect = if (multiSelect && !state.selecting && state.recipes.isNotEmpty()) {
                                            { viewModel.onStartSelecting() }
                                        } else {
                                            null
                                        },
                                        onDelete = viewModel::onDelete,
                                        onCookbook = if (printViewModel == null || state.recipes.isEmpty()) null else {
                                            {
                                                printViewModel.makeCookbook(
                                                    list.name, state.recipes.map { it.id }, printLabels(resources), contentsHeading
                                                )
                                            }
                                        }
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
                            // A cookbook takes a while (#331): say so until the share sheet opens.
                            if (makingCookbook) MakingCookbook()
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
                            RowWithMenu(
                                // A long press (#376): not while selecting, when a row is only a checkbox.
                                if (!rowActions || state.selecting || saveViewModel == null) null else RowActionSet(
                                    onAddToList = {
                                        saveViewModel.setRecipe(recipe.id, recipe.title)
                                        saveSheetOpen = true
                                    },
                                    onAddToPlan = planViewModel?.let { plan ->
                                        {
                                            viewModel.onAddToPlan(recipe.id) { base ->
                                                plan.setRecipe(recipe.id, base)
                                                planSheetOpen = true
                                            }
                                        }
                                    },
                                    onShare = {
                                        viewModel.onShare(recipe.id, shareLabels(resources)) { shareRecipeText(context, it) }
                                    },
                                    onSelect = if (multiSelect) ({ viewModel.onStartSelecting(recipe) }) else null,
                                    onRemoveFromList = { viewModel.onRemoveFromList(listOf(recipe.id)) },
                                    onDelete = { viewModel.onRequestDelete(listOf(recipe)) }
                                )
                            ) { onLongClick ->
                                RecipeRow(
                                    recipe, now,
                                    onClick = { if (state.selecting) viewModel.onToggleSelected(recipe.id) else onOpenRecipe(recipe.id) },
                                    onLongClick = onLongClick,
                                    selecting = state.selecting,
                                    selected = recipe.id in state.selectedIds
                                )
                            }
                        }
                    }
                }
                SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).safeDrawingPadding()) { data ->
                    Snackbar(snackbarData = data)
                }
            }
        }

        // Inside the theme (#186): outside it, the dialog was Material purple.
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
}

/** Rename, and — for user lists only — Delete behind a confirm dialog naming the list. */
@Composable
private fun ListOverflowMenu(
    listName: String,
    canDelete: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCookbook: (() -> Unit)? = null,
    /** Starts selecting several recipes (#377); null (the flag off, nothing to select) leaves it out. */
    onSelect: (() -> Unit)? = null
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
        if (onSelect != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_select)) },
                onClick = {
                    expanded = false
                    onSelect()
                },
                modifier = Modifier.testTag("selectRecipes")
            )
        }
        // A cookbook (#318): every recipe here in one PDF, for the share sheet.
        if (onCookbook != null) {
            DropdownMenuItem(
                text = { MenuItemText(stringResource(R.string.action_cookbook), stringResource(R.string.menu_desc_cookbook)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_print), contentDescription = null) },
                onClick = {
                    expanded = false
                    onCookbook()
                }
            )
        }
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

@Composable
private fun MakingCookbook() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.cookbook_making),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
