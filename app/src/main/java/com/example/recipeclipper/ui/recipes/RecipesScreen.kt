package com.example.recipeclipper.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import com.example.recipeclipper.data.model.TooltipScreen
import com.example.recipeclipper.ui.askai.AskAiSheet
import com.example.recipeclipper.ui.askai.AskAiViewModel
import com.example.recipeclipper.ui.edit.PasteTarget
import com.example.recipeclipper.ui.home.BackupFolderCard
import com.example.recipeclipper.ui.home.HomeUiState
import com.example.recipeclipper.ui.settings.BackupStatus
import com.example.recipeclipper.ui.settings.IMPORT_MIME_TYPES
import com.example.recipeclipper.ui.tour.TooltipHost
import com.example.recipeclipper.ui.common.UndoSnackbarEffect
import com.example.recipeclipper.R
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.PantryCoverage
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeQuickFilter
import com.example.recipeclipper.data.model.RecipesLayout
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
    /** Quick filter chips under search (#378). */
    quickFilters: Boolean = LocalFlagValues.current.isOn(Flag.RECIPE_FILTERS),
    /** The rows / photo grid toggle (#380); off, Recipes is the rows whatever was stored. */
    gridEnabled: Boolean = LocalFlagValues.current.isOn(Flag.RECIPES_GRID),
    /**
     * Library first (#375): this is the Recipes tab's first screen, not a page pushed from Home. No Back,
     * the gear and a + that opens the Add a recipe sheet beside the title, Today's card above search, and
     * the four ways in when there is nothing yet. Null, the screen is as before.
     */
    libraryRoot: LibraryRoot? = null,
    /** Today's card (#402): off, only a recipe being cooked gets the library's card. */
    homeTonight: Boolean = LocalFlagValues.current.isOn(Flag.HOME_TONIGHT),
    /** "Ask an AI" (#351): off, the Add a recipe sheet has three ways in. */
    askAiEnabled: Boolean = LocalFlagValues.current.isOn(Flag.ASK_AI),
    /** The Ask an AI sheet's ViewModel (#351), for the library as the first screen. */
    askAiViewModel: AskAiViewModel? = if (askAiEnabled && libraryRoot != null) hiltViewModel() else null,
    /**
     * The top redesigned (#449, design B): with the library as the first screen and one cookbook, a ⋮ menu
     * (Select, Settings) after the +, the views shelf above search, the filters in a sheet behind search.
     */
    topB: Boolean = LocalFlagValues.current.isOn(Flag.RECIPES_TOP_B),
    // A parameter so a test can watch the one snackbar a burst shares (#268).
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The card, the restore row and the Ask an AI sheet's state (#375); null for a page that isn't the root.
    val home = libraryRoot?.viewModel
    val homeState = home?.uiState?.collectAsStateWithLifecycle()?.value
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }
    // Restore from a backup file (#150): the same file picker as Settings' Import.
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { home?.onRestorePicked(it.toString()) }
    }
    // The one-time folder card (#150): the system's folder picker.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        home?.onBackupFolderPicked(uri?.toString())
    }
    // Nothing of the cook's yet (the sample, #151, aside): the ways in take the list's place.
    val emptyLibrary = libraryRoot != null && state.view == null && homeState?.libraryEmpty == true
    var saveSheetOpen by rememberSaveable { mutableStateOf(false) }
    var planSheetOpen by rememberSaveable { mutableStateOf(false) }
    // Design B (#449): the library's own top, and its filter sheet.
    val styleB = topB && libraryRoot != null && oneCookbook
    var filterSheetOpen by rememberSaveable { mutableStateOf(false) }
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
        // The + 's sheet (#375): the four ways in, each going where Home's tile did.
        if (addSheetOpen && libraryRoot != null) {
            AddRecipeSheet(
                onDismiss = { addSheetOpen = false },
                onPasteLink = viewModel::onPasteLink,
                onScan = onScan.takeIf { scanEnabled },
                onTypeRecipe = onNewRecipe,
                onAskAi = home?.let { it::onAskAi }.takeIf { askAiViewModel != null }
            )
        }
        if (homeState?.askAiOpen == true && askAiViewModel != null && home != null) {
            AskAiSheet(
                onDismiss = home::onAskAiDismiss,
                onOpenPasted = { if (it is PasteTarget.Link) onOpenUrl(it.url) else onOpenPasted(it) },
                viewModel = askAiViewModel,
                onOpenPro = libraryRoot?.onOpenPro ?: {}
            )
        }
        if (saveSheetOpen && saveViewModel != null) {
            SaveToListBottomSheet(saveViewModel, onDismiss = { saveSheetOpen = false })
        }
        if (filterSheetOpen && styleB) {
            RecipeFilterSheet(
                offered = state.quickFilters,
                on = state.quick,
                onToggle = viewModel::onQuickFilterToggle,
                onClear = viewModel::onClearQuickFilters,
                onDismiss = { filterSheetOpen = false }
            )
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
        // The tour's tooltip points at the + (#375): the library is the first screen it shows on.
        val scaffold: @Composable () -> Unit = {
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
                            onDelete = viewModel::onRequestDeleteSelected,
                            // Design B (#449): Select all, beside Done.
                            onSelectAll = if (styleB) viewModel::onSelectAll else null,
                            allSelected = state.allSelected,
                            hairline = !styleB
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
                    val header: @Composable () -> Unit = if (styleB && libraryRoot != null) {
                        {
                            RecipesHeaderB(
                                state = state,
                                root = RootHeader(
                                    libraryRoot, homeState, homeTonight, emptyLibrary,
                                    onAdd = { addSheetOpen = true },
                                    onOpenRecipe = onOpenRecipe,
                                    onAskAi = home?.let { it::onAskAi }.takeIf { askAiViewModel != null },
                                    onRestore = if (home?.canRestore == true) ({ restorePicker.launch(IMPORT_MIME_TYPES) }) else null,
                                    onChooseFolder = { folderPicker.launch(null) }
                                ),
                                onNewRecipe = onNewRecipe,
                                onPasteLink = viewModel::onPasteLink,
                                onScan = onScan.takeIf { scanEnabled },
                                onQueryChange = viewModel::onQueryChange,
                                onOpenView = onOpenView,
                                onOpenLists = onOpenLists,
                                onOpenFilters = if (quickFilters) ({ filterSheetOpen = true }) else null,
                                onQuickToggle = viewModel::onQuickFilterToggle,
                                onClearQuick = viewModel::onClearQuickFilters,
                                onSort = viewModel::onSortChange,
                                onLayout = viewModel::onLayoutChange.takeIf { gridEnabled },
                                onSelect = if (multiSelect) ({ viewModel.onStartSelecting() }) else null
                            )
                        }
                    } else {
                        {
                        RecipesHeader(
                            state = state,
                            onBack = onBack,
                            onNewRecipe = onNewRecipe,
                            onPasteLink = viewModel::onPasteLink,
                            onScan = onScan.takeIf { scanEnabled },
                            onQueryChange = viewModel::onQueryChange,
                            onFilterChange = viewModel::onFilterChange,
                            oneCookbook = oneCookbook,
                            onOpenView = onOpenView,
                            onOpenLists = onOpenLists,
                            quickFilters = quickFilters,
                            onQuickToggle = viewModel::onQuickFilterToggle,
                            onSort = viewModel::onSortChange,
                            onLayout = viewModel::onLayoutChange.takeIf { gridEnabled },
                            // Select (#377): the header's own entry, beside the + (a view has no +).
                            onSelect = if (multiSelect) ({ viewModel.onStartSelecting() }) else null,
                            root = if (libraryRoot == null) null else RootHeader(
                                libraryRoot, homeState, homeTonight, emptyLibrary,
                                onAdd = { addSheetOpen = true },
                                onOpenRecipe = onOpenRecipe,
                                onAskAi = home?.let { it::onAskAi }.takeIf { askAiViewModel != null },
                                onRestore = if (home?.canRestore == true) ({ restorePicker.launch(IMPORT_MIME_TYPES) }) else null,
                                onChooseFolder = { folderPicker.launch(null) }
                            )
                        )
                        }
                    }
                    val onSave: ((RecipeSummary) -> Unit)? = if (oneCookbook && saveViewModel != null) {
                        { recipe ->
                            saveViewModel.setRecipe(recipe.id, recipe.title)
                            saveSheetOpen = true
                        }
                    } else {
                        null
                    }
                    // A long press (#376): not while selecting, when a row is only a checkbox. The rows and the
                    // photo grid share it, and the tap (#377), so a recipe acts the same in either layout.
                    val actionsFor: (RecipeSummary) -> RowActionSet? = { recipe ->
                        if (!rowActions || state.selecting || saveViewModel == null) {
                            null
                        } else {
                            RowActionSet(
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
                        }
                    }
                    val onRowClick: (RecipeSummary) -> Unit = { recipe ->
                        if (state.selecting) viewModel.onToggleSelected(recipe.id) else onOpenRecipe(recipe.id)
                    }
                    val pantryChip = RecipeQuickFilter.USES_PANTRY in state.quick
                    if (gridEnabled && state.layout == RecipesLayout.GRID) {
                        // The photo grid (#380): two columns on a phone, more as the width allows.
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(GRID_CELL_MIN_WIDTH),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize().testTag("recipesGrid")
                        ) {
                            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
                            val cullKeep = state.cullKeep
                            if (state.showsCullNote && cullKeep != null) {
                                item(key = "cull", span = { GridItemSpan(maxLineSpan) }) { CullNote(cullKeep) }
                            }
                            val recipes = state.recipes
                            if (recipes != null && recipes.isEmpty() && !emptyLibrary) {
                                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyMessage(state) }
                            }
                            gridItems(recipes.orEmpty(), key = { "recipe-${it.id}" }) { recipe ->
                                // No swipe in a grid: Delete is in the long-press menu (#376), with the rows' other actions.
                                RowWithMenu(actionsFor(recipe)) { onLongClick ->
                                    LibraryGridCell(
                                        recipe = recipe,
                                        onClick = { onRowClick(recipe) },
                                        pantry = state.coverage[recipe.id].takeIf { pantryChip },
                                        onSaveToList = onSave?.let { save -> { save(recipe) } },
                                        onLongClick = onLongClick,
                                        selecting = state.selecting,
                                        selected = recipe.id in state.selectedIds
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item { header() }

                            val cullKeep = state.cullKeep
                            if (state.showsCullNote && cullKeep != null) {
                                item { CullNote(cullKeep, Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                            }

                            val recipes = state.recipes
                            if (recipes != null && recipes.isEmpty() && !emptyLibrary) {
                                item { EmptyMessage(state) }
                            }

                            items(recipes.orEmpty(), key = { it.id }) { recipe ->
                                Hairline()
                                SwipeToDeleteRow(
                                    recipe = recipe,
                                    onClick = { onRowClick(recipe) },
                                    onDelete = { viewModel.onDelete(recipe) },
                                    pantry = state.coverage[recipe.id].takeIf { pantryChip },
                                    onSaveToList = onSave?.let { save -> { save(recipe) } },
                                    selecting = state.selecting,
                                    selected = recipe.id in state.selectedIds,
                                    actions = actionsFor(recipe)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (libraryRoot != null) {
            TooltipHost(TooltipScreen.HOME, blocked = snackbarHostState.currentSnackbarData != null || state.selecting) { scaffold() }
        } else {
            scaffold()
        }
    }
}

/** A photo cell's narrowest width: two columns on a phone (320dp of content and up), more on wider screens. */
private val GRID_CELL_MIN_WIDTH = 140.dp

/**
 * Everything above the recipes: the back button, the title with its + menu, search, the quick
 * filter chips (#378), the views strip or the old pills, and the line with the count, the layout
 * toggle (#380) and the sort. The same in the rows and in the grid.
 */
@Composable
private fun RecipesHeader(
    state: RecipesUiState,
    onBack: () -> Unit,
    onNewRecipe: () -> Unit,
    onPasteLink: () -> Unit,
    onScan: ((List<String>) -> Unit)?,
    onQueryChange: (String) -> Unit,
    onFilterChange: (RecipeFilter) -> Unit,
    oneCookbook: Boolean,
    onOpenView: (RecipeFilter) -> Unit,
    onOpenLists: () -> Unit,
    quickFilters: Boolean,
    onQuickToggle: (RecipeQuickFilter) -> Unit,
    onSort: (RecipeSort) -> Unit,
    onLayout: ((RecipesLayout) -> Unit)?,
    /** Select (#377); null with the flag off. */
    onSelect: (() -> Unit)? = null,
    /** The library as the tab's first screen (#375); null on a page pushed over something. */
    root: RootHeader? = null
) {
    val view = state.view
    Column {
        if (root == null) BackButton(onBack) else Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                stringResource(if (view != null) filterLabel(view) else R.string.recipes_title),
                style = if (root == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            if (onSelect != null && !state.selecting && !state.recipes.isNullOrEmpty()) {
                TextButton(onClick = onSelect, modifier = Modifier.testTag("selectRecipes")) {
                    Text(stringResource(R.string.action_select))
                }
            }
            if (root != null && !state.selecting) {
                LibraryTopActions(onOpenSettings = root.root.onOpenSettings, onAdd = root.onAdd)
            } else if (view == null && !state.selecting) {
                AddMenu(onTypeRecipe = onNewRecipe, onPasteLink = onPasteLink, onScan = onScan)
            }
        }
        if (view != null) ViewNote(view, Modifier.padding(top = 6.dp))
        // Today's card, then the one-time backup card (#150): above search, scrolling away with the list (#375).
        if (root?.home != null && !state.selecting) {
            Spacer(Modifier.height(12.dp))
            LibraryCard(root.home, root.root, root.homeTonight, root.onOpenRecipe)
            if (root.home.offersBackupFolder) {
                BackupFolderCard(onChoose = root.onChooseFolder, onDismiss = root.root.viewModel::onBackupPromptDismissed)
                Spacer(Modifier.height(12.dp))
            }
        }
        if (root?.emptyLibrary == true) {
            EmptyLibrary(
                onPasteLink = onPasteLink,
                onScan = onScan,
                onTypeRecipe = onNewRecipe,
                onAskAi = root.onAskAi,
                restore = root.home?.restore ?: BackupStatus.Idle,
                onRestore = root.onRestore
            )
            Spacer(Modifier.height(12.dp))
            return@Column
        }
        Spacer(Modifier.height(12.dp))
        SearchField(query = state.query, onQueryChange = onQueryChange)
        if (quickFilters && state.showsFilter) {
            QuickFilterChips(state.quickFilters, state.quick, onQuickToggle)
        }
        when {
            // One cookbook (#345): the views, then the lists, in place of the pills.
            oneCookbook && state.showsViewsStrip ->
                RecipesViewsStrip(state.counts, state.lists.size, onOpenView, onOpenLists)
            !oneCookbook && state.showsFilter ->
                RecipeFilterPills(state.filter, state.counts, onFilterChange)
        }
        if (state.showsSortLine) {
            SortLine(state, onSort, onLayout)
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The library's header as design B draws it (#449): the title row (the +, then the ⋮ menu with Select and
 * Settings), Today's card, the views shelf, search with its filter button, the pills of the filters on,
 * and the count line (the count, the sort, then the rows / grid toggle). While selecting, the bar above
 * the rows is the whole top.
 */
@Composable
private fun RecipesHeaderB(
    state: RecipesUiState,
    root: RootHeader,
    onNewRecipe: () -> Unit,
    onPasteLink: () -> Unit,
    onScan: ((List<String>) -> Unit)?,
    onQueryChange: (String) -> Unit,
    onOpenView: (RecipeFilter) -> Unit,
    onOpenLists: () -> Unit,
    onOpenFilters: (() -> Unit)?,
    onQuickToggle: (RecipeQuickFilter) -> Unit,
    onClearQuick: () -> Unit,
    onSort: (RecipeSort) -> Unit,
    onLayout: ((RecipesLayout) -> Unit)?,
    onSelect: (() -> Unit)?
) {
    Column {
        if (state.selecting) return@Column
        Spacer(Modifier.height(4.dp))
        LibraryTitleRowB(
            onAdd = root.onAdd,
            onSelect = onSelect.takeIf { !state.recipes.isNullOrEmpty() },
            onOpenSettings = root.root.onOpenSettings
        )
        if (root.home != null) {
            Spacer(Modifier.height(4.dp))
            LibraryCard(root.home, root.root, root.homeTonight, root.onOpenRecipe, styleB = true)
            if (root.home.offersBackupFolder) {
                Spacer(Modifier.height(12.dp))
                BackupFolderCard(onChoose = root.onChooseFolder, onDismiss = root.root.viewModel::onBackupPromptDismissed)
            }
        }
        if (root.emptyLibrary) {
            EmptyLibrary(
                onPasteLink = onPasteLink,
                onScan = onScan,
                onTypeRecipe = onNewRecipe,
                onAskAi = root.onAskAi,
                restore = root.home?.restore ?: BackupStatus.Idle,
                onRestore = root.onRestore
            )
            Spacer(Modifier.height(12.dp))
            return@Column
        }
        Spacer(Modifier.height(12.dp))
        if (state.showsViewsStrip) {
            ViewsShelf(state.counts, state.lists.size, onOpenView, onOpenLists)
            Spacer(Modifier.height(12.dp))
        }
        SearchFieldB(
            query = state.query,
            onQueryChange = onQueryChange,
            filtersOn = state.activeQuickFilters.size,
            onOpenFilters = onOpenFilters.takeIf { state.showsFilter }
        )
        ActiveFilterPills(state.activeQuickFilters, onQuickToggle, onClearQuick)
        if (state.showsSortLine) {
            SortLine(state, onSort, onLayout, toggleLast = true)
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * What the library as the first screen (#375) adds to the header: [root]'s destinations, Home's state
 * (the card, the restore row, the folder card), and the actions that need the screen's launchers.
 */
private class RootHeader(
    val root: LibraryRoot,
    val home: HomeUiState?,
    val homeTonight: Boolean,
    val emptyLibrary: Boolean,
    val onAdd: () -> Unit,
    val onOpenRecipe: (Long) -> Unit,
    val onAskAi: (() -> Unit)?,
    val onRestore: (() -> Unit)?,
    val onChooseFolder: () -> Unit
)

/** What an empty list says: the chips', the search's, else the view's or the empty library's. */
@Composable
private fun EmptyMessage(state: RecipesUiState) {
    Text(
        when {
            state.quick.isNotEmpty() -> stringResource(R.string.recipes_no_results_filters)
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
    pantry: PantryCoverage? = null,
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
                    recipe, onClick = onClick, onSaveToList = onSaveToList, pantry = pantry,
                    onLongClick = onLongClick, selecting = selecting, selected = selected
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
private fun SortLine(
    state: RecipesUiState,
    onSort: (RecipeSort) -> Unit,
    onLayout: ((RecipesLayout) -> Unit)? = null,
    /** Design B (#449): the sort, then the toggle, last on the right. */
    toggleLast: Boolean = false
) {
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
        if (onLayout != null && !toggleLast) LayoutToggle(state.layout, onLayout)
        SortMenu(state.sort, onSort)
        if (onLayout != null && toggleLast) LayoutToggle(state.layout, onLayout)
    }
}
