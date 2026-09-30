package com.example.recipeclipper.ui.pantry

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * stock, running low or run out, and its quantity as written; tapping the row opens its edit sheet (stock, quantity, staple, use-by date, delete), with swipes and a touch-and-hold menu as shortcuts. The menu sends what's in stock, as
 * plain text or as a file (#149), and clears what has run out after asking, with undo (#194).
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

    // Snackbars only for undo (#146): a delete, or "Clear run-out items" (#194).
    val message = state.message
    val text = when (message) {
        is PantryMessage.Deleted -> stringResource(R.string.snackbar_pantry_deleted, message.name)
        is PantryMessage.RunOutCleared -> stringResource(R.string.snackbar_run_out_cleared)
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
                                },
                                canClearRunOut = state.hasRunOut,
                                onClearRunOut = viewModel::onClearRunOut
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
                                // The stock tooltip (#190) points at the first row.
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
        state.confirmClearRunOut?.let { count ->
            AlertDialog(
                onDismissRequest = viewModel::onClearRunOutDismissed,
                title = { Text(pluralStringResource(R.plurals.clear_run_out_title, count, count)) },
                confirmButton = {
                    TextButton(onClick = viewModel::onClearRunOutConfirm, modifier = Modifier.testTag("clearRunOutConfirm")) {
                        Text(stringResource(R.string.action_clear))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::onClearRunOutDismissed) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
    }
}

/**
 * "Send list" (the in-stock items as text) and "Send as file" (#149), disabled while nothing is
 * in stock, then the sort, then "Clear run-out items" (#194), disabled while nothing has run out.
 */
@Composable
private fun PantryMenu(
    sort: PantrySort,
    onSort: (PantrySort) -> Unit,
    canSend: Boolean,
    onShare: () -> Unit,
    onSendFile: (() -> Unit)?,
    canClearRunOut: Boolean,
    onClearRunOut: () -> Unit
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
            HorizontalDivider()
            // Asks first; the grocery list is left alone, and Undo puts the items back (#194).
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_clear_run_out)) },
                enabled = canClearRunOut,
                onClick = {
                    expanded = false
                    onClearRunOut()
                }
            )
        }
    }
}

/**
 * One item (#194): its name, a "Low" tag while running low, the basket "Groceries" tag while its
 * name is on the grocery list, and its quantity as written on the right. Tap the row for the edit
 * sheet (its In stock | Running low | Run out control changes the state); touch and hold for the
 * other two states; swipes are shortcuts: towards the end restocks, towards the start runs out.
 * No per-row button (owner, 2026-09-29: redundant beside tapping the item). TalkBack reads the
 * row as one ("Garlic, 1 head, Run out, On your grocery list"), with the other two states as its
 * actions.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    // TalkBack's actions and the long-press menu: both other states.
    val otherStates = PantryStock.entries.filter { it != stock }
    val choiceLabels = otherStates.associateWith { stringResource(it.action()) }
    val alwaysHave = if (item.alwaysHave) stringResource(R.string.pantry_always_have) else null
    val badge = item.expiresDay?.let { PantryList.badge(it, today) }
    val expiry = item.expiresDay?.let { day ->
        if (badge == ExpiryBadge.EXPIRED) stringResource(R.string.pantry_expired)
        else stringResource(R.string.pantry_use_by, shortDate(day))
    }
    val description = (
        listOfNotNull(item.name, item.quantity, alwaysHave) + stringResource(stock.label()) +
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
            Box(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
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
                        .then(if (first) Modifier.tooltipAnchor(Tooltip.PANTRY_IN_STOCK) else Modifier)
                        .padding(vertical = 12.dp)
                        .testTag("pantry-${item.id}")
                ) {
                    Column(Modifier.weight(1f)) {
                        // The name, then its tags; they wrap under a long name or large text
                        // rather than squeezing it.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (item.inStock) MaterialTheme.colorScheme.onSurface else muted
                            )
                            if (stock == PantryStock.RUNNING_LOW) {
                                Text(
                                    stringResource(R.string.pantry_low),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                        .testTag("low-${item.id}")
                                )
                            }
                            if (onList) {
                                OnListTag(item.id, onTakeOffList)
                            }
                        }
                        alwaysHave?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                        expiry?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (badge != null) MaterialTheme.colorScheme.tertiary else muted,
                                modifier = Modifier.testTag("expiry-${item.id}")
                            )
                        }
                    }
                    // The quantity as written, in the button's old place (#194); a long one is cut
                    // short, never more than 40% of the row.
                    item.quantity?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp).maxWidthFraction(0.4f).testTag("quantity-${item.id}")
                        )
                    }
                }
                // The row's menu: both other states.
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
        }
    }
}

/** Caps a child's width at [fraction] of what its parent offers; it may be narrower. */
private fun Modifier.maxWidthFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val max = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).toInt() else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = max))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}


/**
 * A state, not a message (#146): the item's name is on the grocery list; tapping takes it off.
 * It wears the Groceries tab's basket and label (owner, 2026-09-29: "On list" wasn't understood)
 * and reads "On your grocery list". At the largest font scales only the basket shows, so the
 * item's name keeps its room.
 */
@Composable
private fun OnListTag(itemId: Long, onTakeOffList: () -> Unit) {
    val density = LocalDensity.current
    val iconOnly = density.fontScale >= 1.5f
    val iconSize = with(density) { 16.sp.toDp() }
    val description = stringResource(R.string.pantry_on_list)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClickLabel = stringResource(R.string.pantry_take_off_list), role = Role.Button, onClick = onTakeOffList)
            .semantics { contentDescription = description }
            .padding(horizontal = if (iconOnly) 6.dp else 8.dp, vertical = 4.dp)
            .testTag("onList-$itemId")
    ) {
        Icon(
            painterResource(R.drawable.ic_tab_groceries),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(iconSize)
        )
        if (!iconOnly) {
            Text(
                stringResource(R.string.tab_groceries),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                // The description above says it; the label isn't read twice.
                modifier = Modifier.padding(start = 4.dp).clearAndSetSemantics {}
            )
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
            // Every state, visibly (#194): the row's menu and swipes are shortcuts. Applied at
            // once, as they are.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("pantryEditStock")) {
                PantryStock.entries.forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = editing.stock == choice,
                        onClick = { viewModel.onEditStock(choice) },
                        shape = SegmentedButtonDefaults.itemShape(index, PantryStock.entries.size),
                        modifier = Modifier.testTag("pantryEditStock-${choice.name}")
                    ) {
                        Text(stringResource(choice.label()), textAlign = TextAlign.Center)
                    }
                }
            }
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
