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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.GroceryCombiner
import com.example.recipeclipper.data.model.GroceryCombiner.Row as GroceryRow
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * The Groceries tab (#50): "Add an item", then the list by aisle. Lines naming the same
 * ingredient sit together under its name, or as one added-up row when that's exact. Tap to
 * tick; long-press to move to another aisle or delete (with undo). The menu shares the list
 * as plain text. While anything is ticked, "Done shopping" (#146) puts it away in the pantry
 * and clears it, with undo.
 */
@Composable
fun GroceriesScreen(viewModel: GroceriesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current

    // Snackbars only for undo (#146): a delete, or "Done shopping".
    val removed = state.removed
    val removedMessage = removed?.let {
        when {
            it.label != null -> stringResource(R.string.snackbar_grocery_deleted, it.label)
            it.putAway -> stringResource(R.string.snackbar_done_shopping)
            else -> stringResource(R.string.snackbar_checked_cleared)
        }
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
            // "Done shopping" (#146), while anything is ticked: one step to put away and clear.
            bottomBar = {
                if (state.hasChecked) {
                    Button(
                        onClick = viewModel::onDoneShopping,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag("doneShopping")
                    ) {
                        Text(stringResource(R.string.action_done_shopping))
                    }
                }
            },
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
                            }
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
        state.putAway?.let { sheet ->
            PutAwaySheetView(
                sheet = sheet,
                onToggle = viewModel::onPutAwayToggle,
                onConfirm = viewModel::onPutAwayConfirm,
                onDismiss = viewModel::onPutAwayDismissed
            )
        }
    }
}

@Composable
private fun GroceriesMenu(canShare: Boolean, onShare: () -> Unit) {
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
        }
    }
}

/**
 * A row on the list, always one tick. An added-up total shows its lines under it; lines that
 * can't be added up honestly sit under their ingredient's name, as written, without ticks of
 * their own: ticking the row ticks them all.
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
            detail = emptyList(),
            checked = row.item.checked,
            tag = "grocery-${row.item.id}",
            onToggle = { onToggle(row) },
            onMove = { onMove(row) },
            onDelete = { onDelete(row, row.item.text) }
        )
        is GroceryRow.Combined -> CheckLine(
            text = row.text,
            detail = listOf(GroceryCombiner.lines(row).joinToString(" + ")).filter { it != row.text },
            checked = row.items.all { it.checked },
            tag = "grocery-${row.items.first().id}",
            onToggle = { onToggle(row) },
            onMove = { onMove(row) },
            onDelete = { onDelete(row, row.text) }
        )
        is GroceryRow.Together -> CheckLine(
            text = row.name,
            detail = GroceryCombiner.lines(row),
            checked = row.items.all { it.checked },
            tag = "grocery-${row.items.first().id}",
            onToggle = { onToggle(row) },
            onMove = { onMove(row) },
            onDelete = { onDelete(row, row.name) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheckLine(
    text: String,
    detail: List<String>,
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
                detail.forEach { line ->
                    Text(
                        line,
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

/**
 * "Done shopping" (#146): the ticked items the pantry can hold, with checkboxes (what it tracks
 * starts ticked), and one button that puts the ticked ones away and clears every ticked line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PutAwaySheetView(sheet: PutAwaySheet, onToggle: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().testTag("putAway")
        ) {
            item(key = "title") {
                SectionHeading(stringResource(R.string.action_done_shopping))
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.put_away_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            items(sheet.items, key = { it.key }) { item ->
                val ticked = item.key in sheet.ticked
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onToggle(item.key) })
                        .padding(vertical = 4.dp)
                        .testTag("putAway-${item.key}")
                ) {
                    Checkbox(checked = ticked, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item(key = "confirm") {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("putAwayButton")
                ) {
                    Text(stringResource(R.string.action_put_away))
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
