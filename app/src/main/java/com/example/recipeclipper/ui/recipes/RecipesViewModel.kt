package com.example.recipeclipper.ui.recipes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.TimerCountdownSync
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.model.RecipeCounts
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeList
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
 */
data class RecipesUiState(
    val query: String = "",
    val recipes: List<RecipeSummary>? = null,
    val pendingDeletes: List<String> = emptyList(),
    val deleteBatch: Int? = null,
    val sort: RecipeSort = RecipeSort.RECENTLY_VIEWED,
    val linkInput: String? = null,
    /** The free library's quiet count (#107, #345); null when there is no limit to count against. */
    val count: LibraryCount? = null,
    val filter: RecipeFilter = RecipeFilter.ALL,
    val counts: RecipeCounts? = null,
    val cullKeep: Int? = null,
    val view: RecipeFilter? = null,
    val lists: List<RecipeList> = emptyList(),
    /** The "New list" card's dialog (#371): the name typed, or null while it is closed. */
    val newListName: String? = null,
    /** An Undo was refused, the free library being full (#345): the screen says so once, then clears it. */
    val restoreRefused: Boolean = false
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

    /** The dialog's Open is enabled only for what reads as a link (`UrlInput`). */
    val canOpenLink: Boolean get() = linkInput?.let(UrlInput::normalize) != null

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
    private val lists: ListRepository? = null,
    /** The automatic view's route argument (V4), when opened on one. */
    savedStateHandle: SavedStateHandle? = null
) : ViewModel() {

    private val view: RecipeFilter? = RecipeFilter.fromRouteName(savedStateHandle?.get<String>(VIEW_ARG))

    // The query box's live value. Kept in a StateFlow, not `remember`, so it survives rotation.
    private val query = MutableStateFlow("")
    private val pendingDeletes = MutableStateFlow(PendingDeletes())
    private var batches = 0
    // Remembered in AppPreferences, so it survives leaving the screen; the flow keeps the
    // screen in step with what is stored.
    private val sort = preferences.settings
        .map { it.recipeSort }
        .distinctUntilChanged()
    private val linkInput = MutableStateFlow<String?>(null)
    private val restoreRefused = MutableStateFlow(false)
    private val newListName = MutableStateFlow<String?>(null)
    // In the ViewModel, not `remember`, so it survives rotation; not stored, so Recipes opens on All.
    private val filter = MutableStateFlow(view ?: RecipeFilter.ALL)

    // What swipe-to-dismiss captured, keyed by recipe id so a second swipe within the
    // snackbar's few seconds cannot overwrite the first and strand it. A plain field: the
    // ViewModel already survives rotation, the same reason RecipeViewModel keeps its timer
    // deadlines this way.
    private val captured = LinkedHashMap<Long, RecipeRepository.DeletedRecipe>()

    // The debounce is the typing's alone: a tap on a filter answers at once.
    private val recipes: StateFlow<List<RecipeSummary>?> = combine(
        query.debounce(250).distinctUntilChanged(),
        filter
    ) { q, f -> q to f }
        .flatMapLatest { (q, f) -> repository.observeHistory(q, f) }
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
            combine(query, recipes, pendingDeletes, sort, linkInput) { q, found, pending, order, link ->
                RecipesUiState(q, found?.let { sorted(it, order) }, pending.titles, pending.batch, order, link)
            }.combine(restoreRefused) { state, refused -> state.copy(restoreRefused = refused) },
            count,
            filter,
            counts,
            combine(cullKeep, listsFlow, newListName) { keep, shelves, naming -> Triple(keep, shelves, naming) }
        ) { state, saved, picked, all, (keep, shelves, naming) ->
            state.copy(
                count = saved, filter = picked, counts = all, cullKeep = keep, view = view, lists = shelves,
                newListName = naming
            )
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

    /** The strip's "New list" card (#371): opens the name dialog, empty. */
    fun onNewList() {
        newListName.value = ""
    }

    fun onNewListNameChange(text: String) {
        newListName.value = text
    }

    fun onNewListDismiss() {
        newListName.value = null
    }

    /** Creates the list, empty as on the Lists screen (no recipe in hand); a blank name is ignored. */
    fun onCreateList() {
        val name = newListName.value?.trim().orEmpty()
        if (name.isEmpty()) return
        newListName.value = null
        viewModelScope.launch { lists?.createList(name, addRecipeId = null) }
    }

    /** "Paste a link" from the + menu: opens the dialog, empty. */
    fun onPasteLink() {
        linkInput.value = ""
    }

    fun onLinkChange(text: String) {
        linkInput.value = text
    }

    fun onLinkDismiss() {
        linkInput.value = null
    }

    /** The link to import, closing the dialog; null, and the dialog stays, if it isn't one. */
    fun onOpenLink(): String? {
        val url = linkInput.value?.let(UrlInput::normalize) ?: return null
        linkInput.value = null
        return url
    }

    /** Swipe-to-dismiss: deletes immediately, keeping what it removed so [onUndoDelete] can restore it. */
    fun onDelete(recipe: RecipeSummary) {
        viewModelScope.launch {
            val removed = repository.delete(recipe.id) ?: return@launch
            // Its countdown goes with it; Undo brings the timer back when the recipe is opened.
            countdowns?.reconcile()
            captured[recipe.id] = removed
            // The first of a burst starts a new batch; the rest join it.
            pendingDeletes.update { pending ->
                PendingDeletes(pending.batch ?: ++batches, pending.titles + recipe.title)
            }
        }
    }

    /**
     * Restores everything still pending, oldest first so ids go back in the order they left.
     * All or nothing: one snackbar covers the batch, so there is no way to pick one out of it.
     * On the free tier at its limit (#345) none comes back: [RecipesUiState.restoreRefused].
     */
    fun onUndoDelete() {
        if (captured.isEmpty()) return
        val removed = captured.values.toList()
        captured.clear()
        pendingDeletes.value = PendingDeletes()
        viewModelScope.launch {
            // The free library's limit is checked in the repository's transaction (#345): a burst
            // that wouldn't fit restores none, and the deletes stand, photo files included.
            when (repository.restoreAll(removed)) {
                RecipeRepository.RestoreOutcome.LIBRARY_FULL -> {
                    restoreRefused.value = true
                    removed.forEach { repository.forget(it) }
                }
                RecipeRepository.RestoreOutcome.RESTORED, RecipeRepository.RestoreOutcome.FAILED -> Unit
            }
        }
    }

    /** The screen has said the Undo was refused. */
    fun onRestoreRefusedShown() {
        restoreRefused.value = false
    }

    /** The snackbar timed out or was swiped away: the deletes stand. */
    fun onSnackbarDismissed() {
        val settled = captured.values.toList()
        captured.clear()
        pendingDeletes.value = PendingDeletes()
        // Their photos (#116) were kept only for Undo.
        if (settled.isNotEmpty()) viewModelScope.launch { settled.forEach { repository.forget(it) } }
    }

    /** The burst being deleted: its [batch], null when nothing is pending, and its titles. */
    private data class PendingDeletes(val batch: Int? = null, val titles: List<String> = emptyList())

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
