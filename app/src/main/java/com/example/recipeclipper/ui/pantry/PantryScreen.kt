package com.example.recipeclipper.ui.pantry

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.ExpiryBadge
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantryList
import com.example.recipeclipper.data.model.PantrySort
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.ui.groceries.label
import com.example.recipeclipper.ui.plan.shortDate
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * The Pantry tab (#51): "Add to the pantry", a search field, then everything by aisle (or by
 * expiry, from the menu). Each row's switch says whether it's in stock; tapping the row opens
 * its edit sheet (quantity, staple, use-by date, delete).
 */
@Composable
fun PantryScreen(viewModel: PantryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val message = state.message
    val text = when (message) {
        is PantryMessage.OutOfStock -> stringResource(R.string.snackbar_pantry_out, message.item.name)
        is PantryMessage.Deleted -> stringResource(R.string.snackbar_pantry_deleted, message.name)
        is PantryMessage.AddedToGroceries -> stringResource(R.string.snackbar_added_to_groceries, message.name)
        null -> null
    }
    val action = when (message) {
        is PantryMessage.OutOfStock -> stringResource(R.string.action_add_to_groceries)
        is PantryMessage.Deleted -> stringResource(R.string.action_undo)
        else -> null
    }
    LaunchedEffect(message) {
        if (message == null || text == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(text, actionLabel = action, withDismissAction = false)
        when {
            result != SnackbarResult.ActionPerformed -> viewModel.onMessageDismissed()
            message is PantryMessage.OutOfStock -> viewModel.onAddToGroceries(message.item)
            message is PantryMessage.Deleted -> viewModel.onUndoDelete()
        }
    }

    RecipeClipperTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing
        ) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("pantryList")
            ) {
                item(key = "header") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                        Text(
                            stringResource(R.string.tab_pantry),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f)
                        )
                        SortMenu(state.sort, viewModel::onSortChange)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = viewModel::onDraftChange,
                        label = { Text(stringResource(R.string.pantry_add_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.onAddTyped() }),
                        trailingIcon = {
                            if (state.draft.isNotBlank()) {
                                TextButton(onClick = viewModel::onAddTyped) { Text(stringResource(R.string.action_add)) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("pantryDraft")
                    )
                    if (state.hasItems) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            label = { Text(stringResource(R.string.pantry_search_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("pantrySearch")
                        )
                    }
                    val empty = when {
                        state.sections == null -> null
                        !state.hasItems -> stringResource(R.string.pantry_empty)
                        state.sections.orEmpty().isEmpty() -> stringResource(R.string.pantry_no_results, state.query.trim())
                        else -> null
                    }
                    if (empty != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.sections.orEmpty().forEach { section ->
                    item(key = "aisle-${section.aisle?.key ?: "expiry"}") {
                        Column(Modifier.padding(top = 18.dp, bottom = 2.dp)) {
                            section.aisle?.let {
                                SectionHeading(stringResource(it.label()))
                                Spacer(Modifier.height(4.dp))
                            }
                            Hairline()
                        }
                    }
                    items(section.items, key = { "item-${it.id}" }) { item ->
                        PantryRow(
                            item = item,
                            today = state.today,
                            onToggle = { viewModel.onToggleStock(item) },
                            onEdit = { viewModel.onEdit(item) }
                        )
                    }
                }
            }
        }

        state.editing?.let { editing ->
            EditSheet(editing, viewModel)
        }
    }
}

@Composable
private fun SortMenu(sort: PantrySort, onSort: (PantrySort) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // An exclusive choice, so radio rows.
            listOf(PantrySort.AISLE to R.string.pantry_sort_aisle, PantrySort.EXPIRY to R.string.pantry_sort_expiry)
                .forEach { (option, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                        onClick = {
                            expanded = false
                            onSort(option)
                        }
                    )
                }
        }
    }
}

@Composable
private fun PantryRow(item: PantryItem, today: Long, onToggle: () -> Unit, onEdit: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("pantry-${item.id}")
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 6.dp)
        ) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.inStock) MaterialTheme.colorScheme.onSurface else muted
            )
            val details = buildList {
                item.quantity?.let { add(it) }
                if (item.alwaysHave) add(stringResource(R.string.pantry_always_have))
                if (!item.inStock) add(stringResource(R.string.pantry_out))
            }
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            item.expiresDay?.let { day ->
                val badge = PantryList.badge(day, today)
                Text(
                    if (badge == ExpiryBadge.EXPIRED) stringResource(R.string.pantry_expired)
                    else stringResource(R.string.pantry_use_by, shortDate(day)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (badge != null) MaterialTheme.colorScheme.tertiary else muted,
                    modifier = Modifier.testTag("expiry-${item.id}")
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = item.inStock,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag("inStock-${item.id}")
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(editing: PantryEditing, viewModel: PantryViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picking by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = viewModel::onEditDismissed, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .testTag("pantryEdit")
        ) {
            SectionHeading(stringResource(R.string.pantry_edit_title))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = editing.name,
                onValueChange = viewModel::onEditName,
                label = { Text(stringResource(R.string.pantry_label_name)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("pantryEditName")
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = editing.quantity,
                onValueChange = viewModel::onEditQuantity,
                label = { Text(stringResource(R.string.pantry_label_quantity)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("pantryEditQuantity")
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = editing.alwaysHave, role = Role.Switch, onValueChange = viewModel::onEditAlwaysHave)
                    .padding(vertical = 4.dp)
                    .testTag("pantryEditAlwaysHave")
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pantry_always_have), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.pantry_always_have_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = editing.alwaysHave, onCheckedChange = null)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pantry_label_expiry), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        editing.expiresDay?.let { shortDate(it) } ?: stringResource(R.string.pantry_no_date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (editing.expiresDay != null) {
                    TextButton(onClick = { viewModel.onEditExpiry(null) }) { Text(stringResource(R.string.action_clear_date)) }
                }
                TextButton(onClick = { picking = true }) { Text(stringResource(R.string.action_set_date)) }
            }
            editing.purchasedDay?.let {
                Text(
                    stringResource(R.string.pantry_bought, shortDate(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = viewModel::onEditDelete, modifier = Modifier.testTag("pantryEditDelete")) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = viewModel::onEditSave,
                    enabled = editing.name.isNotBlank(),
                    modifier = Modifier.testTag("pantryEditSave")
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (picking) {
        // The picker works in UTC midnights, which is exactly how an epoch day is formatted.
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = editing.expiresDay?.let(PlanDays::utcMillis))
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { viewModel.onEditExpiry(Math.floorDiv(it, PlanDays.MILLIS_PER_DAY)) }
                    picking = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}
