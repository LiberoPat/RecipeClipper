package com.example.recipeclipper.ui.groceries

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.GroceryCombiner.Row as GroceryRow
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * The Groceries tab (#50): "Add an item", then the list by aisle. Lines naming the same
 * ingredient sit together under its name, or as one added-up row when that's exact. Tap to
 * tick; long-press to move to another aisle or delete (with undo). The menu shares the list
 * as plain text and clears what's ticked.
 */
@Composable
fun GroceriesScreen(viewModel: GroceriesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current

    val removed = state.removed
    val removedMessage = removed?.let {
        if (it.label == null) stringResource(R.string.snackbar_checked_cleared)
        else stringResource(R.string.snackbar_grocery_deleted, it.label)
    }
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(removed) {
        val message = removedMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(message, actionLabel = undoLabel, withDismissAction = false)
        if (result == SnackbarResult.ActionPerformed) viewModel.onUndoRemove() else viewModel.onSnackbarDismissed()
    }

    RecipeClipperTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing
        ) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("groceryList")
            ) {
                item(key = "header") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                        Text(
                            stringResource(R.string.tab_groceries),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f)
                        )
                        GroceriesMenu(
                            canShare = !state.isEmpty,
                            canClear = state.hasChecked,
                            onShare = {
                                val title = resources.getString(R.string.tab_groceries)
                                viewModel.shareText(title) { resources.aisleName(it) }?.let { text ->
                                    ShareCompat.IntentBuilder(context)
                                        .setType("text/plain")
                                        .setSubject(title)
                                        .setText(text)
                                        .setChooserTitle(title)
                                        .startChooser()
                                }
                            },
                            onClearChecked = viewModel::onClearChecked
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = viewModel::onDraftChange,
                        label = { Text(stringResource(R.string.groceries_add_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.onAddTyped() }),
                        trailingIcon = {
                            if (state.draft.isNotBlank()) {
                                TextButton(onClick = viewModel::onAddTyped) { Text(stringResource(R.string.action_add)) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("groceryDraft")
                    )
                    if (state.isEmpty) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.groceries_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.sections.orEmpty().forEach { section ->
                    item(key = "aisle-${section.aisle.key}") {
                        Column(Modifier.padding(top = 18.dp, bottom = 2.dp)) {
                            SectionHeading(stringResource(section.aisle.label()))
                            Spacer(Modifier.height(4.dp))
                            Hairline()
                        }
                    }
                    items(section.rows, key = { row -> "row-${row.items.first().id}" }) { row ->
                        GroceryRowView(
                            row = row,
                            onToggle = viewModel::onToggle,
                            onMove = viewModel::onMoveStart,
                            onDelete = { r, label -> viewModel.onDelete(r, label) }
                        )
                    }
                }
            }
        }

        state.moving?.let { row ->
            MoveToAisleSheet(
                current = row.items.first().aisle,
                onSelect = viewModel::onMoveTo,
                onDismiss = viewModel::onMoveDismissed
            )
        }
    }
}

@Composable
private fun GroceriesMenu(canShare: Boolean, canClear: Boolean, onShare: () -> Unit, onClearChecked: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share_groceries)) },
                enabled = canShare,
                onClick = {
                    expanded = false
                    onShare()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_clear_checked)) },
                enabled = canClear,
                onClick = {
                    expanded = false
                    onClearChecked()
                }
            )
        }
    }
}

/**
 * A row on the list. A line on its own or an added-up total is one tick; lines that can't be
 * added up honestly sit under their ingredient's name, each with its own tick.
 */
@Composable
private fun GroceryRowView(
    row: GroceryRow,
    onToggle: (GroceryRow) -> Unit,
    onMove: (GroceryRow) -> Unit,
    onDelete: (GroceryRow, String) -> Unit
) {
    when (row) {
        is GroceryRow.Single -> CheckLine(
            text = row.item.text,
            detail = null,
            checked = row.item.checked,
            tag = "grocery-${row.item.id}",
            onToggle = { onToggle(row) },
            onMove = { onMove(row) },
            onDelete = { onDelete(row, row.item.text) }
        )
        is GroceryRow.Combined -> CheckLine(
            text = row.text,
            detail = row.items.joinToString(" + ") { it.text },
            checked = row.items.all { it.checked },
            tag = "grocery-${row.items.first().id}",
            onToggle = { onToggle(row) },
            onMove = { onMove(row) },
            onDelete = { onDelete(row, row.text) }
        )
        is GroceryRow.Together -> Column {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            row.items.forEach { item ->
                val single = GroceryRow.Single(item)
                CheckLine(
                    text = item.text,
                    detail = null,
                    checked = item.checked,
                    tag = "grocery-${item.id}",
                    onToggle = { onToggle(single) },
                    onMove = { onMove(single) },
                    onDelete = { onDelete(single, item.text) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheckLine(
    text: String,
    detail: String?,
    checked: Boolean,
    tag: String,
    onToggle: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(role = Role.Checkbox, onClick = onToggle, onLongClick = { menuOpen = true })
                // A long-press as well as a tap, so not `toggleable`; the state is still announced.
                .semantics { toggleableState = ToggleableState(checked) }
                .padding(vertical = 6.dp)
                .testTag(tag)
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (checked) TextDecoration.LineThrough else null,
                    color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_move_to_aisle)) },
                onClick = {
                    menuOpen = false
                    onMove()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}

/** Long-press → "Move to aisle…": every aisle, an exclusive choice, so radio rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveToAisleSheet(current: Aisle, onSelect: (Aisle) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
        ) {
            item {
                SectionHeading(stringResource(R.string.move_to_aisle_title))
                Spacer(Modifier.height(8.dp))
            }
            items(Aisle.entries, key = { it.key }) { aisle ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = aisle == current, role = Role.RadioButton, onClick = { onSelect(aisle) })
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(selected = aisle == current, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(aisle.label()), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@StringRes
internal fun Aisle.label(): Int = when (this) {
    Aisle.PRODUCE -> R.string.aisle_produce
    Aisle.MEAT -> R.string.aisle_meat
    Aisle.SEAFOOD -> R.string.aisle_seafood
    Aisle.DAIRY -> R.string.aisle_dairy
    Aisle.BAKERY -> R.string.aisle_bakery
    Aisle.BAKING -> R.string.aisle_baking
    Aisle.GRAINS -> R.string.aisle_grains
    Aisle.CANNED -> R.string.aisle_canned
    Aisle.CONDIMENTS -> R.string.aisle_condiments
    Aisle.SPICES -> R.string.aisle_spices
    Aisle.FROZEN -> R.string.aisle_frozen
    Aisle.SNACKS -> R.string.aisle_snacks
    Aisle.DRINKS -> R.string.aisle_drinks
    Aisle.OTHER -> R.string.aisle_other
}

private fun Resources.aisleName(aisle: Aisle): String = getString(aisle.label())
