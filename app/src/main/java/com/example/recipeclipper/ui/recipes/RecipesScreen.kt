package com.example.recipeclipper.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.ui.edit.PasteTarget
import com.example.recipeclipper.ui.common.UndoSnackbarEffect
import com.example.recipeclipper.R
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.common.ConfirmDeleteRecipesDialog
import com.example.recipeclipper.ui.common.LocalFlagValues
import com.example.recipeclipper.ui.common.RowActionSet
import com.example.recipeclipper.ui.common.RowWithMenu
import com.example.recipeclipper.ui.plan.AddToPlanBottomSheet
import com.example.recipeclipper.ui.plan.AddToPlanViewModel
import com.example.recipeclipper.ui.recipe.shareLabels
import com.example.recipeclipper.ui.recipe.BackButton
import com.example.recipeclipper.ui.recipe.Hairline
import com.example.recipeclipper.ui.savetolist.SaveToListBottomSheet
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import com.example.recipeclipper.ui.theme.RecipeClipperTheme

/**
 * Every recipe on the phone (#102, the library that replaced History): newest viewed first by
 * default, searchable, sortable, swipe to delete. The + adds one: typed in (the editor), or
 * from a pasted link (the usual import flow), or scanned (#226).
 */
@Composable
fun RecipesScreen(
    onBack: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onNewRecipe: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    /** Pasted text that isn't a link (#372): the editor, the meal-plan review, Groceries' list sheet. */
    onOpenPasted: (PasteTarget) -> Unit = {},
    onScan: (List<String>) -> Unit = {},
    /** A view's tile on the strip (#345): its own screen, Back to Recipes. */
    onOpenView: (RecipeFilter) -> Unit = {},
    /** The strip's Lists card (#371): the Lists screen, Back to here. */
    onOpenLists: () -> Unit = {},
    viewModel: RecipesViewModel = hiltViewModel(),
    scanEnabled: Boolean = LocalFlagValues.current.isOn(Flag.PHOTO_TEXT),
    /** One cookbook (#345): the views strip in place of the filter pills, and a bookmark to save from a row. */
    oneCookbook: Boolean = LocalFlagValues.current.isOn(Flag.ONE_COOKBOOK),
    /** A long press on a row opens its menu (#376). */
    rowActions: Boolean = LocalFlagValues.current.isOn(Flag.ROW_ACTIONS),
    /** Selecting several recipes (#377): a Select action in the header and in a row's menu. */
    multiSelect: Boolean = LocalFlagValues.current.isOn(Flag.MULTI_SELECT),
    /** The sheet behind a row's outlined bookmark (#345) and its menu's Add to list (#376); the recipe goes in with `setRecipe`. */
    saveViewModel: SaveToListViewModel? = if (oneCookbook || rowActions || multiSelect) hiltViewModel() else null,
    /** The sheet behind a row menu's Add to plan (#376). */
    planViewModel: AddToPlanViewModel? = if (rowActions) hiltViewModel() else null,
    // A parameter so a test can watch the one snackbar a burst shares (#268).
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var saveSheetOpen by rememberSaveable { mutableStateOf(false) }
    var planSheetOpen by rememberSaveable { mutableStateOf(false) }
    val view = state.view
    val context = LocalContext.current
    val resources = LocalResources.current
    // Back leaves selecting first (#377), then the screen.
    BackHandler(enabled = state.selecting, onBack = viewModel::onExitSelection)

    // Resolved here, not inside LaunchedEffect below: its block is a suspend lambda, not a
    // composable one, so stringResource cannot be called from it. Resolving the finished
    // message here rather than a template also keeps String.format out of it, and lets the
    // many-case be a real plurals lookup.
    val pending = state.pendingDeletes
    val deletedMessage = when {
        pending.isEmpty() -> null
        pending.size == 1 -> stringResource(R.string.snackbar_deleted_one, pending.single())
        else -> pluralStringResource(R.plurals.snackbar_deleted_many, pending.size, pending.size)
    }
    val undoLabel = stringResource(R.string.action_undo)

    // Keyed on the burst, not its list: a further swipe joins the batch and the one snackbar's
    // message counts it in place. Keyed on the list, each swipe took the snackbar down and
    // showed another, which flashed (#268).
    UndoSnackbarEffect(
        state.deleteBatch, deletedMessage, undoLabel, snackbarHostState,
        viewModel::onUndoDelete, viewModel::onSnackbarDismissed
    )

    // The Undo of a delete that the free library's limit refused (#345): its own outcome, said once.
    val refusedMessage = stringResource(R.string.snackbar_restore_full)
    // Cleared at once, so the snackbar is shown from the screen's own scope: the effect restarts on the
    // clearing, which would cancel a snackbar shown inside it.
    val refusedScope = rememberCoroutineScope()
    LaunchedEffect(state.restoreRefused) {
        if (state.restoreRefused) {
            viewModel.onRestoreRefusedShown()
            refusedScope.launch { snackbarHostState.showSnackbar(refusedMessage) }
        }
    }

    RecipeClipperTheme {
        // Inside the theme, like every dialog (#186): outside it, Material's purple applies.
        state.linkInput?.let { link ->
            PasteLinkDialog(
                value = link,
                canOpen = state.canOpenLink,
                onValueChange = viewModel::onLinkChange,
                onOpen = { viewModel.onOpenLink { if (it is PasteTarget.Link) onOpenUrl(it.url) else onOpenPasted(it) } },
                noRecipe = state.linkNoRecipe,
                onDismiss = viewModel::onLinkDismiss
            )
        }
        if (saveSheetOpen && saveViewModel != null) {
            SaveToListBottomSheet(saveViewModel, onDismiss = { saveSheetOpen = false })
        }
        if (planSheetOpen && planViewModel != null) {
            AddToPlanBottomSheet(planViewModel, onDismiss = { planSheetOpen = false })
        }
        // A row menu's Delete or a selection's (#376, #377): one confirmation for everything asked about.
        if (state.confirmingDelete.isNotEmpty()) {
            ConfirmDeleteRecipesDialog(
                state.confirmingDelete,
                onConfirm = viewModel::onConfirmDelete,
                onCancel = viewModel::onCancelDelete
            )
        }
        Scaffold(
            topBar = {
                // Selecting several recipes (#377): the count, Done, and what to do with them.
                if (state.selecting) {
                    SelectionBar(
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        ),
                        count = state.selectedIds.size,
                        onDone = viewModel::onExitSelection,
                        onAddToList = if (saveViewModel == null) null else {
                            {
                                saveViewModel.setRecipes(state.selectedIds.toList())
                                saveSheetOpen = true
                            }
                        },
                        onRemoveFromList = null,
                        onDelete = viewModel::onRequestDeleteSelected
                    )
                }
            },
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    // No colour overrides: the defaults are the scheme's inverse* slots,
                    // which Theme.kt defines. Overriding only the container here is what
                    // made the text and the Undo action unreadable.
                    Snackbar(snackbarData = data)
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            // Edge-to-edge: safeDrawing rather than the default system bars, so the
            // results also clear the keyboard while searching.
            contentWindowInsets = WindowInsets.safeDrawing
        ) { padding ->
            Surface(
                Modifier.fillMaxSize().padding(padding),
                color = MaterialTheme.colorScheme.background
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        BackButton(onBack)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text(
                                stringResource(if (view != null) filterLabel(view) else R.string.recipes_title),
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.weight(1f)
                            )
                            // Select (#377): the header's own entry, beside the + (a view has no +).
                            if (multiSelect && !state.selecting && !state.recipes.isNullOrEmpty()) {
                                TextButton(
                                    onClick = { viewModel.onStartSelecting() },
                                    modifier = Modifier.testTag("selectRecipes")
                                ) { Text(stringResource(R.string.action_select)) }
                            }
                            if (view == null && !state.selecting) {
                                AddMenu(
                                    onTypeRecipe = onNewRecipe, onPasteLink = viewModel::onPasteLink,
                                    onScan = onScan.takeIf { scanEnabled }
                                )
                            }
                        }
                        if (view != null) ViewNote(view, Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(12.dp))
                        SearchField(query = state.query, onQueryChange = viewModel::onQueryChange)
                        when {
                            // One cookbook (#345): the views, then the lists, in place of the pills.
                            oneCookbook && state.showsViewsStrip ->
                                RecipesViewsStrip(state.counts, state.lists.size, onOpenView, onOpenLists)
                            !oneCookbook && state.showsFilter ->
                                RecipeFilterPills(state.filter, state.counts, viewModel::onFilterChange)
                        }
                        if (state.showsSortLine) {
                            SortLine(state, viewModel::onSortChange)
                        } else {
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    val cullKeep = state.cullKeep
                    if (state.showsCullNote && cullKeep != null) {
                        item { CullNote(cullKeep, Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                    }

                    val recipes = state.recipes
                    if (recipes != null && recipes.isEmpty()) {
                        item {
                            Text(
                                when {
                                    state.query.isNotBlank() -> stringResource(R.string.recipes_no_results, state.query)
                                    state.filter == RecipeFilter.COOKED -> stringResource(R.string.recipes_empty_cooked)
                                    state.filter == RecipeFilter.TYPED -> stringResource(R.string.recipes_empty_typed)
                                    state.filter == RecipeFilter.CHAT -> stringResource(R.string.recipes_empty_chat)
                                    state.showsFilter && state.filter == RecipeFilter.IN_A_LIST -> stringResource(R.string.recipes_empty_in_list)
                                    state.showsFilter && state.filter == RecipeFilter.NOT_IN_A_LIST -> stringResource(R.string.recipes_empty_not_in_list)
                                    else -> stringResource(R.string.recipes_empty)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(recipes.orEmpty(), key = { it.id }) { recipe ->
                        Hairline()
                        SwipeToDeleteRow(
                            recipe = recipe,
                            onClick = { if (state.selecting) viewModel.onToggleSelected(recipe.id) else onOpenRecipe(recipe.id) },
                            onDelete = { viewModel.onDelete(recipe) },
                            onSaveToList = if (oneCookbook && saveViewModel != null) {
                                {
                                    saveViewModel.setRecipe(recipe.id, recipe.title)
                                    saveSheetOpen = true
                                }
                            } else {
                                null
                            },
                            selecting = state.selecting,
                            selected = recipe.id in state.selectedIds,
                            // A long press (#376): not while selecting, when a row is only a checkbox.
                            actions = if (!rowActions || state.selecting || saveViewModel == null) null else RowActionSet(
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
                                onRemoveFromList = null,
                                onDelete = { viewModel.onRequestDelete(listOf(recipe)) }
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.label_search_recipes)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear_search))
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth()
    )
}

/** A row that can be swiped away in either direction to delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    recipe: RecipeSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSaveToList: (() -> Unit)? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
    actions: RowActionSet? = null
) {
    // Remembered, not rememberSaveable like rememberSwipeToDismissBoxState: the lazy list keeps a
    // removed row's saved state under its key, so a row put back by Undo came back dismissed and
    // SwipeToDismissBox deleted it again, popping the snackbar back up (#268). A row swiped away
    // is gone, so there is nothing to keep across a rotation either.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    // SwipeToDismissBox calls onDismiss from an effect keyed on the lambda itself, so the
    // lambda must stay the same instance across recompositions or a recomposition while the
    // row is still settled off-screen would delete it a second time.
    val currentOnDelete by rememberUpdatedState(onDelete)
    val onDismiss = remember { { _: SwipeToDismissBoxValue -> currentOnDelete() } }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { DeleteBackground(dismissState) },
        modifier = Modifier.fillMaxWidth(),
        // Not while selecting (#377): a row is then only a checkbox.
        gesturesEnabled = !selecting,
        // Called once the row has settled off-screen, in either direction.
        onDismiss = onDismiss
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RowWithMenu(actions) { onLongClick ->
                LibraryRow(
                    recipe, onClick = onClick, onSaveToList = onSaveToList, onLongClick = onLongClick,
                    selecting = selecting, selected = selected
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteBackground(dismissState: SwipeToDismissBoxState) {
    val alignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
        Alignment.CenterEnd
    } else {
        Alignment.CenterStart
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment
    ) {
        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
    }
}

/**
 * The quiet line under search (#286): how many recipes on the left ("20 recipes", or on the
 * free tier "12 of 20 recipes", #107), and the order they're in on the right.
 */
@Composable
private fun SortLine(state: RecipesUiState, onSort: (RecipeSort) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Inside a view the count is the view's own, not the free library's.
        val count = state.count.takeIf { state.view == null }
        val listed = state.listed
        Text(
            when {
                count == null -> pluralStringResource(R.plurals.recipes_listed, listed ?: 0, listed ?: 0)
                count.over -> stringResource(R.string.recipes_count_over, count.recipes, count.max)
                else -> stringResource(R.string.recipes_count, count.recipes, count.max)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 2.dp)
        )
        SortMenu(state.sort, onSort)
    }
}
