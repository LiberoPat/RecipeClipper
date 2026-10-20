package com.example.recipeclipper.ui.pantry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.TooltipScreen
import com.example.recipeclipper.ui.common.UndoSnackbarEffect
import com.example.recipeclipper.ui.groceries.label
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.recipe.SectionHeading
import com.example.recipeclipper.ui.sharefile.SendFileEffect
import com.example.recipeclipper.ui.sharefile.SendFileViewModel
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import com.example.recipeclipper.ui.tour.TooltipHost
import kotlinx.coroutines.launch

/** The list's leading padding, which the drag's checkbox column is measured from. */
private val ListStart = 20.dp

/**
 * The Pantry tab (#51): counts of what's in stock, running low and run out, each jumping to its
 * rows (#286), "Add to the pantry", a search field, then everything by aisle (or by
 * expiry, from the menu), and what has run out last (#194). Each row shows whether it's in
 * stock, running low or run out, and its quantity as written; tapping the row opens its edit sheet (stock, quantity, staple, use-by date, delete), with swipes as shortcuts. The menu sends what's in stock, as
 * plain text or as a file (#149), and clears what has run out after asking, with undo (#194).
 * A touch and hold, or the menu's "Select items", starts selecting (#258): checkboxes, drag to
 * check a run of rows, then Remove or Mark run out for them all, each with one undo.
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

    // Snackbars only for undo (#146): a delete, "Clear run-out items" (#194), or the tag's removal.
    val message = state.message
    val text = when (message) {
        is PantryMessage.Deleted -> stringResource(R.string.snackbar_pantry_deleted, message.name)
        is PantryMessage.RunOutCleared -> stringResource(R.string.snackbar_run_out_cleared)
        is PantryMessage.TakenOffList ->
            if (message.count > 1) pluralStringResource(R.plurals.snackbar_taken_off_list_count, message.count, message.count)
            else stringResource(R.string.snackbar_taken_off_list)
        is PantryMessage.Removed -> pluralStringResource(R.plurals.snackbar_pantry_removed, message.count, message.count)
        is PantryMessage.MarkedRunOut -> pluralStringResource(R.plurals.snackbar_pantry_marked_run_out, message.count, message.count)
        null -> null
    }
    val undoLabel = stringResource(R.string.action_undo)
    UndoSnackbarEffect(message, text, undoLabel, snackbarHostState, viewModel::onUndoDelete, viewModel::onMessageDismissed)

    val sendFailedMessage = stringResource(R.string.send_file_failed)
    if (sendFileViewModel != null) {
        SendFileEffect(sendFileViewModel) { snackbarHostState.showSnackbar(sendFailedMessage) }
    }

    // Selecting (#258): Back leaves it; the gesture reads whether it's on as it goes, so starting
    // it mid-gesture doesn't restart the gesture.
    val selection = state.selection
    BackHandler(enabled = selection != null, onBack = viewModel::onExitSelection)
    val selecting by rememberUpdatedState(selection != null)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // The bar's height: the drag's top edge sits under it. View geometry only.
    var barHeight by remember { mutableIntStateOf(0) }

    RecipeClipperTheme {
        TooltipHost(TooltipScreen.PANTRY, blocked = snackbarHostState.currentSnackbarData != null || selection != null) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing
            ) { padding ->
                // The selection bar lies over the top of the list rather than pushing it down, so
                // the rows stay under the finger that has just started selecting (#258).
                Box(Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(start = ListStart, end = 20.dp, top = 4.dp, bottom = 32.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .dragToSelect(
                                listState = listState,
                                scope = scope,
                                checkboxEdge = ListStart + CheckboxColumn,
                                topCover = { if (selecting) barHeight.toFloat() else 0f },
                                isSelecting = { selecting },
                                onLongPress = { id ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.onLongPress(id)
                                },
                                onDragStart = viewModel::onDragStart,
                                onDragTo = viewModel::onDragTo,
                                onDragEnd = viewModel::onDragEnd
                            )
                            .testTag("pantryList")
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
                                    onClearRunOut = viewModel::onClearRunOut,
                                    canSelect = state.sections.orEmpty().isNotEmpty(),
                                    onSelectItems = viewModel::onStartSelecting
                                )
                            }
                            // In stock, Running low, Run out (#286); hidden while the pantry is empty.
                            if (state.counts.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                PantryCounts(state.counts, enabled = selection == null) { id ->
                                    state.sections?.scrollIndex(id)?.let { scope.launch { listState.animateScrollToItem(it) } }
                                }
                                Spacer(Modifier.height(4.dp))
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
                            val sectionKey = if (section.runOut) "runOut" else section.aisle?.key ?: "expiry"
                            item(key = "aisle-$sectionKey") {
                                Column(Modifier.padding(top = 18.dp, bottom = 2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
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
                                        }
                                        // A section's own Select all, while selecting and not all of it is (#258).
                                        if (selection != null && (section.runOut || section.aisle != null) &&
                                            section.items.any { it.id !in selection.ids }
                                        ) {
                                            TextButton(
                                                onClick = { viewModel.onSelectSection(section) },
                                                modifier = Modifier.testTag("selectSection-$sectionKey")
                                            ) { Text(stringResource(R.string.action_select_all)) }
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
                                    onTakeOffList = { viewModel.onTakeOffList(item) },
                                    selecting = selection != null,
                                    selected = selection?.ids?.contains(item.id) == true,
                                    onToggle = { viewModel.onToggle(item.id) },
                                    onSelect = { viewModel.onSelect(item.id) }
                                )
                            }
                        }
                    }
                    if (selection != null) {
                        PantrySelectionBar(
                            count = selection.ids.size,
                            allSelected = state.allSelected(),
                            onExit = viewModel::onExitSelection,
                            onSelectAll = viewModel::onSelectAll,
                            onMarkRunOut = viewModel::onMarkRunOutSelected,
                            onRemove = viewModel::onRemoveSelected,
                            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { barHeight = it.height }
                        )
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
