package com.example.recipeclipper.ui.pantry

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.ExpiryBadge
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantryList
import com.example.recipeclipper.data.model.PantrySort
import com.example.recipeclipper.data.model.PantryStock
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.data.model.TooltipScreen
import com.example.recipeclipper.ui.groceries.label
import com.example.recipeclipper.ui.plan.shortDate
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import com.example.recipeclipper.ui.sharefile.SendFileEffect
import com.example.recipeclipper.ui.sharefile.SendFileViewModel
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import com.example.recipeclipper.ui.tour.TooltipHost
import com.example.recipeclipper.ui.tour.tooltipAnchor
import kotlinx.coroutines.launch

/**
 * The Pantry tab (#51): "Add to the pantry", a search field, then everything by aisle (or by
 * expiry, from the menu), and what has run out last (#194). Each row shows whether it's in
 * stock, running low or run out, with a labelled action; tapping the row opens its edit sheet (quantity, staple, use-by date, delete). The menu sends what's in stock, as
 * plain text or as a file (#149).
 */
@Composable
fun PantryScreen(
    viewModel: PantryViewModel = hiltViewModel(),
    // "Send as file" (#149, phase 2); null (screen tests) leaves it out.
    sendFileViewModel: SendFileViewModel? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current

    // Snackbars only for undo (#146).
    val message = state.message
    val text = when (message) {
        is PantryMessage.Deleted -> stringResource(R.string.snackbar_pantry_deleted, message.name)
        null -> null
    }
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(message) {
        if (message == null || text == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(text, actionLabel = undoLabel, withDismissAction = false)
        if (result == SnackbarResult.ActionPerformed) viewModel.onUndoDelete() else viewModel.onMessageDismissed()
    }

    val sendFailedMessage = stringResource(R.string.send_file_failed)
    if (sendFileViewModel != null) {
        SendFileEffect(sendFileViewModel) { snackbarHostState.showSnackbar(sendFailedMessage) }
    }

    RecipeClipperTheme {
        TooltipHost(TooltipScreen.PANTRY, blocked = snackbarHostState.currentSnackbarData != null) {
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
                            PantryMenu(
                                sort = state.sort,
                                onSort = viewModel::onSortChange,
                                canSend = state.hasInStock,
                                onShare = {
                                    val title = resources.getString(R.string.tab_pantry)
                                    viewModel.shareText(title) { resources.getString(it.label()) }?.let { text ->
                                        ShareCompat.IntentBuilder(context)
                                            .setType("text/plain")
                                            .setSubject(title)
                                            .setText(text)
                                            .setChooserTitle(title)
                                            .startChooser()
                                    }
                                },
                                onSendFile = sendFileViewModel?.let { vm ->
                                    { vm.sendPantry(resources.getString(R.string.tab_pantry)) }
                                }
                            )
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
                            modifier = Modifier.fillMaxWidth().testTag("pantryDraft").tooltipAnchor(Tooltip.PANTRY_ADD)
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
                        val sectionKey = if (section.runOut) "runOut" else section.aisle?.key ?: "expiry"
                        item(key = "aisle-$sectionKey") {
                            Column(Modifier.padding(top = 18.dp, bottom = 2.dp)) {
                                if (section.runOut) {
                                    // Run out sits last, dimmed (#194).
                                    Text(
                                        stringResource(R.string.pantry_out),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.semantics { heading() }
                                    )
                                    Spacer(Modifier.height(4.dp))
                                } else {
                                    section.aisle?.let {
                                        SectionHeading(stringResource(it.label()))
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                                Hairline()
                            }
                        }
                        items(section.items, key = { "item-${it.id}" }) { item ->
                            PantryRow(
                                item = item,
                                today = state.today,
                                onList = item.id in state.onList,
                                // The stock tooltip (#190) points at the first row's action.
                                first = item == state.sections?.firstOrNull()?.items?.firstOrNull(),
                                onSetStock = { stock -> viewModel.onSetStock(item, stock) },
                                onEdit = { viewModel.onEdit(item) },
                                onTakeOffList = { viewModel.onTakeOffList(item) }
                            )
                        }
                    }
                }
            }
        }

        state.editing?.let { editing ->
            EditSheet(editing, viewModel)
        }
    }
}

/**
 * "Send list" (the in-stock items as text) and "Send as file" (#149), disabled while nothing is
 * in stock, then the sort.
 */
@Composable
private fun PantryMenu(
    sort: PantrySort,
    onSort: (PantrySort) -> Unit,
    canSend: Boolean,
    onShare: () -> Unit,
    onSendFile: (() -> Unit)?
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.tooltipAnchor(Tooltip.PANTRY_MENU)) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share_groceries)) },
                enabled = canSend,
                onClick = {
                    expanded = false
                    onShare()
                }
            )
            if (onSendFile != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_send_file)) },
                    enabled = canSend,
                    onClick = {
                        expanded = false
                        onSendFile()
                    }
                )
            }
            HorizontalDivider()
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

/**
 * One item (#194): its name (tap to edit; touch and hold for the stock menu), a "Low" tag while
 * running low, "On list" while its name is on the grocery list, and one labelled action: "Ran
 * out", or "Restock" once it has. Swipes are shortcuts: towards the end restocks, towards the
 * start runs out. TalkBack reads the row as one ("Garlic, Run out, On list"), with the other
 * two states as its actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PantryRow(
    item: PantryItem,
    today: Long,
    onList: Boolean,
    first: Boolean,
    onSetStock: (PantryStock) -> Unit,
    onEdit: () -> Unit,
    onTakeOffList: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val stock = item.stock
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    // SwipeToDismissBox calls onDismiss from an effect keyed on the lambda, so it stays one
    // instance; the row then springs back, since it stays on screen in its new state.
    val currentOnSetStock by rememberUpdatedState(onSetStock)
    val onDismiss = remember {
        { value: SwipeToDismissBoxValue ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentOnSetStock(PantryStock.IN_STOCK)
                SwipeToDismissBoxValue.EndToStart -> currentOnSetStock(PantryStock.RUN_OUT)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { dismissState.reset() }
            Unit
        }
    }
    var menu by rememberSaveable { mutableStateOf(false) }
    val otherStates = PantryStock.entries.filter { it != stock }
    val choiceLabels = otherStates.associateWith { stringResource(it.action()) }
    val details = buildList {
        item.quantity?.let { add(it) }
        if (item.alwaysHave) add(stringResource(R.string.pantry_always_have))
    }
    val badge = item.expiresDay?.let { PantryList.badge(it, today) }
    val expiry = item.expiresDay?.let { day ->
        if (badge == ExpiryBadge.EXPIRED) stringResource(R.string.pantry_expired)
        else stringResource(R.string.pantry_use_by, shortDate(day))
    }
    val description = (
        listOf(item.name) + details + stringResource(stock.label()) +
            listOfNotNull(stringResource(R.string.pantry_on_list).takeIf { onList }, expiry)
        ).joinToString(", ")
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { StockSwipeBackground(dismissState.dismissDirection) },
        enableDismissFromStartToEnd = stock != PantryStock.IN_STOCK,
        enableDismissFromEndToStart = stock != PantryStock.RUN_OUT,
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("pantry-${item.id}")
            ) {
                Box(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(onLongClick = { menu = true }, onClick = onEdit)
                            .semantics {
                                contentDescription = description
                                customActions = otherStates.map { choice ->
                                    CustomAccessibilityAction(choiceLabels.getValue(choice)) {
                                        onSetStock(choice)
                                        true
                                    }
                                }
                            }
                            .padding(vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (item.inStock) MaterialTheme.colorScheme.onSurface else muted,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (stock == PantryStock.RUNNING_LOW) {
                                Text(
                                    stringResource(R.string.pantry_low),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                        .testTag("low-${item.id}")
                                )
                            }
                        }
                        if (details.isNotEmpty()) {
                            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        expiry?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (badge != null) MaterialTheme.colorScheme.tertiary else muted,
                                modifier = Modifier.testTag("expiry-${item.id}")
                            )
                        }
                    }
                    // The row's menu: the other two states.
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        otherStates.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choiceLabels.getValue(choice)) },
                                onClick = {
                                    menu = false
                                    onSetStock(choice)
                                },
                                modifier = Modifier.testTag("stockMenu-${choice.name}")
                            )
                        }
                    }
                }
                if (onList) {
                    // A state, not a message (#146): on the grocery list; tapping takes it off.
                    Text(
                        stringResource(R.string.pantry_on_list),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                            .clickable(onClickLabel = stringResource(R.string.pantry_take_off_list), role = Role.Button, onClick = onTakeOffList)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("onList-${item.id}")
                    )
                }
                Spacer(Modifier.width(4.dp))
                // One labelled action instead of a switch (#194).
                val next = if (stock == PantryStock.RUN_OUT) PantryStock.IN_STOCK else PantryStock.RUN_OUT
                val actionLabel = stringResource(next.action())
                val actionDescription = "$actionLabel: ${item.name}"
                TextButton(
                    onClick = { onSetStock(next) },
                    modifier = Modifier.semantics { contentDescription = actionDescription }.testTag("stockAction-${item.id}")
                        .then(if (first) Modifier.tooltipAnchor(Tooltip.PANTRY_IN_STOCK) else Modifier)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/** Behind a swiped row: Restock towards the end, Ran out towards the start. */
@Composable
private fun StockSwipeBackground(direction: SwipeToDismissBoxValue) {
    val restock = direction == SwipeToDismissBoxValue.StartToEnd
    Box(
        Modifier
            .fillMaxSize()
            .background(if (restock) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            .padding(horizontal = 24.dp),
        contentAlignment = if (restock) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            Text(
                stringResource(if (restock) R.string.pantry_restock else R.string.pantry_ran_out),
                style = MaterialTheme.typography.labelLarge,
                color = if (restock) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.background
            )
        }
    }
}

/** A stock state's name, as the row reads it out. */
private fun PantryStock.label(): Int = when (this) {
    PantryStock.IN_STOCK -> R.string.pantry_in_stock
    PantryStock.RUNNING_LOW -> R.string.pantry_running_low
    PantryStock.RUN_OUT -> R.string.pantry_out
}

/** The action that moves an item to this state. */
private fun PantryStock.action(): Int = when (this) {
    PantryStock.IN_STOCK -> R.string.pantry_restock
    PantryStock.RUNNING_LOW -> R.string.pantry_running_low
    PantryStock.RUN_OUT -> R.string.pantry_ran_out
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
