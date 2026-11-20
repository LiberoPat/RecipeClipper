package com.example.recipeclipper.ui.recipes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.TimerCountdownSync
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.ui.edit.PasteTarget
import com.example.recipeclipper.ui.edit.PastedTextRouter
import com.example.recipeclipper.data.model.RecipeCounts
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeShareText
import com.example.recipeclipper.data.model.RecipeSort
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.home.UrlInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import com.example.recipeclipper.data.LibraryPolicy
import com.example.recipeclipper.data.model.LibraryLimit
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import javax.inject.Inject

/**
 * [recipes] is null until the database has answered, to tell "loading" from "nothing
 * matched". [pendingDeletes] holds the titles swiped away but not yet settled, newest last,
 * for the undo snackbar; it survives rotation because it lives here, not in `remember`.
 * [deleteBatch] names the burst they belong to, null when none is pending: the snackbar is keyed
 * on it, so further swipes update one snackbar's count in place instead of replacing it (#268).
 * [linkInput] is the "Paste a link" dialog's text, or null while the dialog is closed.
 * [filter] is All / In a list / Not in a list (#345), applied by the query beside the search;
 * [counts] are the library's, whatever the filter or search, null until the database answers.
 * [cullKeep] is the cap on recipes in no list (the old history's 50) while it applies, null
 * when nothing clears on its own (always, with `oneCookbook` on, #345).
 * [view] is the automatic view the screen was opened on (V4: Not in a list, Cooked, Typed by you),
 * null for the whole cookbook; [lists] are the lists the strip shows after the views.
 * [selecting] and [selectedIds] are selecting several recipes (#377): the ticked ids that are among
 * the rows listed, so what an action acts on is always in sight. [confirmingDelete] are the recipes
 * a Delete is asking about, empty when no confirmation is open (#376, #377).
 */
data class RecipesUiState(
    val query: String = "",
    val recipes: List<RecipeSummary>? = null,
    val pendingDeletes: List<String> = emptyList(),
    val deleteBatch: Int? = null,
    val sort: RecipeSort = RecipeSort.RECENTLY_VIEWED,
    val linkInput: String? = null,
    /** Go found nothing to open in the pasted text (#372); cleared by the next keystroke. */
    val linkNoRecipe: Boolean = false,
    /** The free library's quiet count (#107, #345); null when there is no limit to count against. */
    val count: LibraryCount? = null,
    val filter: RecipeFilter = RecipeFilter.ALL,
    val counts: RecipeCounts? = null,
    val cullKeep: Int? = null,
    val view: RecipeFilter? = null,
    val lists: List<RecipeList> = emptyList(),
    /** An Undo was refused, the free library being full (#345): the screen says so once, then clears it. */
    val restoreRefused: Boolean = false,
    val selecting: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val confirmingDelete: List<RecipeSummary> = emptyList()
) {
    /** The filter shows once there is a recipe to filter, and stays when a filter matches none. */
    val showsFilter: Boolean get() = (counts?.all ?: 0) > 0

    /**
     * The quiet line saying recipes in no list are cleared past [cullKeep] (#345): only under
     * "Not in a list", only where that clean-up applies (the free tier and an unlocked
     * library have their own rules), and only with a row to read it over.
     */
    val showsCullNote: Boolean
        get() = cullKeep != null && filter == RecipeFilter.NOT_IN_A_LIST && !recipes.isNullOrEmpty()

    /** The strip shows once there is a recipe or a list to show (V4), never inside a view. */
    val showsViewsStrip: Boolean get() = view == null && ((counts?.all ?: 0) > 0 || lists.isNotEmpty())

    /** The rows a selection acts on, in the order listed. */
    val selected: List<RecipeSummary> get() = recipes.orEmpty().filter { it.id in selectedIds }

    /** The dialog's Open is enabled only for what reads as a link (`UrlInput`). */
    val canOpenLink: Boolean get() = !linkInput.isNullOrBlank()

    /** How many rows are listed (the matches, while searching); null until the database answers. */
    val listed: Int? get() = recipes?.size

    /**
     * The line under search, with the count and the sort (#286): shown once there is a row to
     * order, or on the free tier, whose count shows even for an empty library.
     */
    val showsSortLine: Boolean get() = count != null || !recipes.isNullOrEmpty()
}

/** [recipes] saved of the free tier's [max]; a library from before the limit can be over it. */
data class LibraryCount(val recipes: Int, val max: Int) {
    val over: Boolean get() = recipes > max
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val repository: RecipeRepository,
    private val preferences: AppPreferences,
    library: LibraryPolicy = LibraryPolicy.HistoryOnly,
    /** The countdowns outside the app (#309), squared with the database after a swipe's
     *  delete (#333); none without it (tests that aren't about them). */
    private val countdowns: TimerCountdownSync? = null,
    /** The strip's lists (#345); none without it (tests that aren't about them). */
    lists: ListRepository? = null,
    /** The automatic view's route argument (V4), when opened on one. */
    savedStateHandle: SavedStateHandle? = null,
    /** "Paste a link" takes any text (#372): where it goes is the shared-text route's; null opens links only. */
    private val pasted: PastedTextRouter? = null,
    /** The typed decisions Share renders under, like the reading view (#376); none without it. */
    decisions: DecisionRepository? = null
) : ViewModel() {

    private val view: RecipeFilter? = RecipeFilter.fromRouteName(savedStateHandle?.get<String>(VIEW_ARG))

    // The query box's live value. Kept in a StateFlow, not `remember`, so it survives rotation.
    private val query = MutableStateFlow("")
    // Deleted and not yet settled, and the one Undo they share: a swipe, a row's menu and a selection (#377).
    private val deletions = RecipeDeletions(repository, countdowns, viewModelScope)
    private val selection = RecipeSelection()
    private val confirmingDelete = MutableStateFlow<List<RecipeSummary>>(emptyList())
    private val rowSupport = RecipeRowSupport(repository, preferences, decisions)
    // The rows last listed: what an action reads, as an action may not wait for a collector.
    private var listed: List<RecipeSummary>? = null
    // Remembered in AppPreferences, so it survives leaving the screen; the flow keeps the
    // screen in step with what is stored.
    private val sort = preferences.settings
        .map { it.recipeSort }
        .distinctUntilChanged()
    private val linkInput = MutableStateFlow<String?>(null)
    private val linkNoRecipe = MutableStateFlow(false)
    // In the ViewModel, not `remember`, so it survives rotation; not stored, so Recipes opens on All.
    private val filter = MutableStateFlow(view ?: RecipeFilter.ALL)

    // The debounce is the typing's alone: a tap on a filter answers at once.
    private val recipes: StateFlow<List<RecipeSummary>?> = combine(
        query.debounce(250).distinctUntilChanged(),
        filter
    ) { q, f -> q to f }
        .flatMapLatest { (q, f) -> repository.observeHistory(q, f) }
        .onEach { listed = it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Only on the free tier: unlocked, or with the flag off, there is nothing to count against.
    private val count = combine(repository.observeCount(), library.limits) { saved, limit ->
        when (limit) {
            is LibraryLimit.Free -> LibraryCount(saved, limit.max)
            is LibraryLimit.Capped -> LibraryCount(saved, limit.max)
            else -> null
        }
    }

    private val listsFlow = lists?.observeLists() ?: flowOf(emptyList())

    private val counts = repository.observeRecipeCounts()

    // Whether recipes in no list are cleared past a cap: the old history's, with the free tier off.
    private val cullKeep = library.limits.map { (it as? LibraryLimit.History)?.keep }

    val uiState: StateFlow<RecipesUiState> =
        combine(
            combine(query, recipes, deletions.pending, sort, linkInput) { q, found, pending, order, link ->
                RecipesUiState(q, found?.let { sorted(it, order) }, pending.titles, pending.batch, order, link)
            }.combine(deletions.restoreRefused) { state, refused -> state.copy(restoreRefused = refused) }
                .combine(linkNoRecipe) { state, none -> state.copy(linkNoRecipe = none) }
                .combine(selection.chosen) { state, chosen ->
                    state.copy(
                        selecting = chosen != null,
                        selectedIds = RecipeSelection.effective(chosen, state.recipes)
                    )
                }
                .combine(confirmingDelete) { state, asking -> state.copy(confirmingDelete = asking) },
            count,
            filter,
            counts,
            combine(cullKeep, listsFlow) { keep, shelves -> keep to shelves }
        ) { state, saved, picked, all, (keep, shelves) ->
            state.copy(count = saved, filter = picked, counts = all, cullKeep = keep, view = view, lists = shelves)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun onFilterChange(value: RecipeFilter) {
        filter.value = value
    }

    fun onSortChange(value: RecipeSort) {
        preferences.recipeSort = value
    }

    /** "Paste a link" from the + menu: opens the dialog, empty. */
    fun onPasteLink() {
        linkInput.value = ""
    }

    fun onLinkChange(text: String) {
        linkInput.value = text
        linkNoRecipe.value = false
    }

    fun onLinkDismiss() {
        linkInput.value = null
        linkNoRecipe.value = false
    }

    /**
     * Go (#372): a link imports, any other text goes where the same text shared would (the
     * editor, the meal-plan review, Groceries' list sheet). [onTarget] gets where, and the dialog
     * closes; with nothing to open the dialog stays, saying so. Nothing is saved here.
     */
    fun onOpenLink(onTarget: (PasteTarget) -> Unit) {
        val text = linkInput.value?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val target = pasted?.open(text) ?: UrlInput.normalize(text)?.let { PasteTarget.Link(it) }
            if (target == null) {
                linkNoRecipe.value = true
            } else {
                linkInput.value = null
                onTarget(target)
            }
        }
    }

    /** Swipe-to-dismiss: deletes immediately, keeping what it removed so [onUndoDelete] can restore it. */
    fun onDelete(recipe: RecipeSummary) = deletions.delete(listOf(recipe))

    /** A row menu's Delete (#376) or a selection's (#377): asks first, naming what would go. */
    fun onRequestDelete(recipes: List<RecipeSummary>) {
        confirmingDelete.value = recipes
    }

    /** A selection's Delete: asks about the ticked rows still in sight. */
    fun onRequestDeleteSelected() {
        val chosen = RecipeSelection.effective(selection.chosen.value, listed)
        onRequestDelete(listed.orEmpty().filter { it.id in chosen })
    }

    fun onCancelDelete() {
        confirmingDelete.value = emptyList()
    }

    /**
     * The confirmation's Delete: everything asked about goes in one transaction and shares one Undo
     * snackbar (#377); a selection is over.
     */
    fun onConfirmDelete() {
        val doomed = confirmingDelete.value
        confirmingDelete.value = emptyList()
        selection.exit()
        deletions.delete(doomed)
    }

    /** Selecting several recipes (#377): from a row's menu with that row ticked, or the top bar's Select with none. */
    fun onStartSelecting(recipe: RecipeSummary? = null) = selection.start(recipe?.id)

    fun onToggleSelected(id: Long) = selection.toggle(id)

    fun onExitSelection() = selection.exit()

    /** A row's Share (#376): the recipe's text as the reading view sends it, handed to [onReady]. */
    fun onShare(recipeId: Long, labels: RecipeShareText.Labels, onReady: (RowShare) -> Unit) {
        viewModelScope.launch { rowSupport.share(recipeId, labels)?.let(onReady) }
    }

    /** A row's Add to plan (#376): the recipe's own yield, which the sheet starts on, handed to [onReady]. */
    fun onAddToPlan(recipeId: Long, onReady: (Int?) -> Unit) {
        viewModelScope.launch {
            val base = rowSupport.yieldServings(recipeId)
            onReady(base)
        }
    }

    /** Restores everything still pending ([RecipeDeletions.undo]). */
    fun onUndoDelete() = deletions.undo()

    /** The screen has said the Undo was refused. */
    fun onRestoreRefusedShown() = deletions.refusedShown()

    /** The snackbar timed out or was swiped away: the deletes stand. */
    fun onSnackbarDismissed() = deletions.dismissed()

    companion object {
        /** The route argument naming an automatic view (V4): `NOT_IN_A_LIST`, `COOKED`, `TYPED` or `CHAT`. */
        const val VIEW_ARG = "view"

        /**
         * [recipes], which the database gives newest viewed first, in [sort]'s order. Name
         * follows the phone's language (a collator, not code points); Date added is newest
         * first by id, which only ever grows (AUTOINCREMENT). Stable, so ties keep recency.
         */
        fun sorted(recipes: List<RecipeSummary>, sort: RecipeSort): List<RecipeSummary> = when (sort) {
            RecipeSort.RECENTLY_VIEWED -> recipes
            RecipeSort.NAME -> recipes.sortedWith(compareBy(Collator.getInstance()) { it.title })
            RecipeSort.DATE_ADDED -> recipes.sortedByDescending { it.id }
            // #116: cooked ones first, latest cook first; the rest keep recency after them.
            RecipeSort.RECENTLY_COOKED -> recipes.sortedByDescending { it.lastCookedDay ?: Long.MIN_VALUE }
        }
    }
}
