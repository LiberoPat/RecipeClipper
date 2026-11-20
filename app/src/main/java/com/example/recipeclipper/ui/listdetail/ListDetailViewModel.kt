package com.example.recipeclipper.ui.listdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.TimerCountdownSync
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeShareText
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.recipes.RecipeDeletions
import com.example.recipeclipper.ui.recipes.RecipeRowSupport
import com.example.recipeclipper.ui.recipes.RecipeSelection
import com.example.recipeclipper.ui.recipes.RowShare
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [list] is null while loading and again once the list has been deleted, so [loaded] is what
 * tells an empty list apart from one that hasn't arrived yet. [deleted] is set by the delete
 * action itself rather than inferred from a null [list], so the screen navigates back exactly
 * once and only in response to what the user did.
 *
 * A row's menu and selecting several recipes (#376, #377) work as on Recipes: [selecting] and
 * [selectedIds] (the ticked rows still listed), [confirmingDelete] (what a Delete is asking about),
 * and the burst of deletes waiting on one Undo ([pendingDeletes], [deleteBatch], [restoreRefused]).
 */
data class ListDetailUiState(
    val loaded: Boolean = false,
    val list: RecipeList? = null,
    val recipes: List<RecipeSummary> = emptyList(),
    val renaming: Boolean = false,
    val renameValue: String = "",
    val deleted: Boolean = false,
    val pendingDeletes: List<String> = emptyList(),
    val deleteBatch: Int? = null,
    val restoreRefused: Boolean = false,
    val selecting: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val confirmingDelete: List<RecipeSummary> = emptyList()
) {
    /** The rows a selection acts on, in the order listed. */
    val selected: List<RecipeSummary> get() = recipes.filter { it.id in selectedIds }
}

@HiltViewModel
class ListDetailViewModel @Inject constructor(
    private val repository: ListRepository,
    savedStateHandle: SavedStateHandle,
    /** Deleting, sharing and planning a row's recipe (#376, #377). */
    recipes: RecipeRepository,
    preferences: AppPreferences,
    /** The countdowns outside the app (#309), squared after a delete (#333); none in tests that aren't about them. */
    countdowns: TimerCountdownSync? = null,
    /** The typed decisions Share renders under, like the reading view; none without it. */
    decisions: DecisionRepository? = null
) : ViewModel() {

    private val listId: Long = checkNotNull(savedStateHandle[LIST_ID_ARG])

    private val renaming = MutableStateFlow(false)
    private val renameValue = MutableStateFlow("")
    private val deleted = MutableStateFlow(false)
    private val deletions = RecipeDeletions(recipes, countdowns, viewModelScope)
    private val selection = RecipeSelection()
    private val confirmingDelete = MutableStateFlow<List<RecipeSummary>>(emptyList())
    private val rowSupport = RecipeRowSupport(recipes, preferences, decisions)

    // The rows last listed: what an action reads, as an action may not wait for a collector.
    private var listed: List<RecipeSummary> = emptyList()

    /**
     * The list itself comes from `observeLists()` rather than its own query: there are only
     * ever a handful of lists, the count comes along for free, and it is one flow to keep
     * correct instead of two.
     *
     * It is collected into its own state here, in [viewModelScope], rather than read back off
     * [uiState] when an action needs it. [uiState] is a `WhileSubscribed` flow, so its `value`
     * is the initial state whenever nothing is collecting — and [onDelete] reading a null list
     * there would silently refuse a delete that should have gone through.
     */
    private val currentList = MutableStateFlow<RecipeList?>(null)

    init {
        viewModelScope.launch {
            repository.observeLists()
                .map { lists -> lists.firstOrNull { it.id == listId } }
                .collect { currentList.value = it }
        }
    }

    val uiState: StateFlow<ListDetailUiState> = combine(
        currentList,
        repository.observeRecipesIn(listId).onEach { listed = it },
        renaming,
        renameValue,
        deleted
    ) { list, recipes, isRenaming, rename, isDeleted ->
        ListDetailUiState(
            loaded = true,
            list = list,
            recipes = recipes,
            renaming = isRenaming,
            renameValue = rename,
            deleted = isDeleted
        )
    }.combine(deletions.pending) { state, pending ->
        state.copy(pendingDeletes = pending.titles, deleteBatch = pending.batch)
    }.combine(deletions.restoreRefused) { state, refused -> state.copy(restoreRefused = refused) }
        .combine(selection.chosen) { state, chosen ->
            state.copy(
                selecting = chosen != null,
                selectedIds = RecipeSelection.effective(chosen, state.recipes)
            )
        }
        .combine(confirmingDelete) { state, asking -> state.copy(confirmingDelete = asking) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListDetailUiState())

    /** Seeds the field with the current name, so renaming is an edit rather than a retype. */
    fun onStartRenaming() {
        renameValue.value = currentList.value?.name.orEmpty()
        renaming.value = true
    }

    fun onCancelRenaming() {
        renaming.value = false
        renameValue.value = ""
    }

    fun onRenameValueChange(value: String) {
        renameValue.value = value
    }

    /** Built-ins are renameable; only deleting them is refused. A blank name is ignored. */
    fun onRenameConfirm() {
        val name = renameValue.value.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            repository.rename(listId, name)
            renaming.value = false
            renameValue.value = ""
        }
    }

    /**
     * Deletes the list, never the recipes in it — they fall back to ordinary history.
     * Refused for Favorites only; every other list, seeded or not, can go.
     */
    fun onDelete() {
        if (currentList.value?.isFavorites != false) return
        viewModelScope.launch {
            repository.deleteList(listId)
            deleted.value = true
        }
    }

    /** Selecting several recipes (#377): from a row's menu with that row ticked, or the overflow's Select with none. */
    fun onStartSelecting(recipe: RecipeSummary? = null) = selection.start(recipe?.id)

    fun onToggleSelected(id: Long) = selection.toggle(id)

    fun onExitSelection() = selection.exit()

    /**
     * Takes [ids] off this list (#376, #377). Leaving a list is not a deletion: the recipes stay in
     * Recipes, under Not in a list, so there is no confirmation and nothing to undo. One transaction;
     * a selection is over.
     */
    fun onRemoveFromList(ids: Collection<Long>) {
        selection.exit()
        if (ids.isEmpty()) return
        viewModelScope.launch { repository.setMembershipAll(ids.toList(), listId, false) }
    }

    /** A selection's Remove from this list: the ticked rows still in sight. */
    fun onRemoveSelectedFromList() {
        onRemoveFromList(RecipeSelection.effective(selection.chosen.value, listed))
    }

    /** A row menu's Delete (#376) or a selection's (#377): asks first, naming what would go. */
    fun onRequestDelete(recipes: List<RecipeSummary>) {
        confirmingDelete.value = recipes
    }

    /** A selection's Delete: asks about the ticked rows still in sight. */
    fun onRequestDeleteSelected() {
        val chosen = RecipeSelection.effective(selection.chosen.value, listed)
        onRequestDelete(listed.filter { it.id in chosen })
    }

    fun onCancelDelete() {
        confirmingDelete.value = emptyList()
    }

    /** The confirmation's Delete: one transaction and one Undo snackbar for everything asked about. */
    fun onConfirmDelete() {
        val doomed = confirmingDelete.value
        confirmingDelete.value = emptyList()
        selection.exit()
        deletions.delete(doomed)
    }

    /** Restores everything still pending ([RecipeDeletions.undo]). */
    fun onUndoDelete() = deletions.undo()

    /** The screen has said the Undo was refused. */
    fun onRestoreRefusedShown() = deletions.refusedShown()

    /** The snackbar timed out or was swiped away: the deletes stand. */
    fun onSnackbarDismissed() = deletions.dismissed()

    /** A row's Share (#376): the recipe's text as the reading view sends it, handed to [onReady]. */
    fun onShare(recipeId: Long, labels: RecipeShareText.Labels, onReady: (RowShare) -> Unit) {
        viewModelScope.launch { rowSupport.share(recipeId, labels)?.let(onReady) }
    }

    /** A row's Add to plan (#376): the recipe's own yield, which the sheet starts on, handed to [onReady]. */
    fun onAddToPlan(recipeId: Long, onReady: (Int?) -> Unit) {
        viewModelScope.launch { onReady(rowSupport.yieldServings(recipeId)) }
    }

    companion object {
        const val LIST_ID_ARG = "listId"
    }
}
