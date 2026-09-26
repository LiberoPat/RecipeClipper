package com.example.recipeclipper.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.RecipeRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import javax.inject.Inject

/** How the Recipes screen orders its rows (#102). Held in memory, like the pantry's sort. */
enum class RecipeSort { RECENTLY_VIEWED, NAME, DATE_ADDED }

/**
 * [recipes] is null until the database has answered, to tell "loading" from "nothing
 * matched". [pendingDeletes] holds the titles swiped away but not yet settled, newest last,
 * for the undo snackbar; it survives rotation because it lives here, not in `remember`.
 * [linkInput] is the "Paste a link" dialog's text, or null while the dialog is closed.
 */
data class RecipesUiState(
    val query: String = "",
    val recipes: List<RecipeSummary>? = null,
    val pendingDeletes: List<String> = emptyList(),
    val sort: RecipeSort = RecipeSort.RECENTLY_VIEWED,
    val linkInput: String? = null,
    /** The free library's quiet count (#107); null when there is no limit to count against. */
    val count: LibraryCount? = null
) {
    /** The dialog's Open is enabled only for what Home's link field would open. */
    val canOpenLink: Boolean get() = linkInput?.let(UrlInput::normalize) != null
}

/** [recipes] saved of the free tier's [max]; a library from before the limit can be over it. */
data class LibraryCount(val recipes: Int, val max: Int) {
    val over: Boolean get() = recipes > max
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val repository: RecipeRepository,
    library: LibraryPolicy = LibraryPolicy.HistoryOnly
) : ViewModel() {

    // The query box's live value. Kept in a StateFlow, not `remember`, so it survives rotation.
    private val query = MutableStateFlow("")
    private val pendingDeletes = MutableStateFlow<List<String>>(emptyList())
    private val sort = MutableStateFlow(RecipeSort.RECENTLY_VIEWED)
    private val linkInput = MutableStateFlow<String?>(null)

    // What swipe-to-dismiss captured, keyed by recipe id so a second swipe within the
    // snackbar's few seconds cannot overwrite the first and strand it. A plain field: the
    // ViewModel already survives rotation, the same reason RecipeViewModel keeps its timer
    // deadlines this way.
    private val captured = LinkedHashMap<Long, RecipeRepository.DeletedRecipe>()

    private val recipes: StateFlow<List<RecipeSummary>?> = query
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { repository.observeHistory(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Only on the free tier: unlocked, or with the flag off, there is nothing to count against.
    private val count = combine(repository.observeCount(), library.limits) { saved, limit ->
        (limit as? LibraryLimit.Free)?.let { LibraryCount(saved, it.max) }
    }

    val uiState: StateFlow<RecipesUiState> =
        combine(
            combine(query, recipes, pendingDeletes, sort, linkInput) { q, found, pending, order, link ->
                RecipesUiState(q, found?.let { sorted(it, order) }, pending, order, link)
            },
            count
        ) { state, saved -> state.copy(count = saved) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun onSortChange(value: RecipeSort) {
        sort.value = value
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
            captured[recipe.id] = removed
            pendingDeletes.value = pendingDeletes.value + recipe.title
        }
    }

    /**
     * Restores everything still pending, oldest first so ids go back in the order they left.
     * All or nothing: one snackbar covers the batch, so there is no way to pick one out of it.
     */
    fun onUndoDelete() {
        if (captured.isEmpty()) return
        val removed = captured.values.toList()
        captured.clear()
        pendingDeletes.value = emptyList()
        viewModelScope.launch { removed.forEach { repository.restore(it) } }
    }

    /** The snackbar timed out or was swiped away: the deletes stand. */
    fun onSnackbarDismissed() {
        captured.clear()
        pendingDeletes.value = emptyList()
    }

    companion object {
        /**
         * [recipes], which the database gives newest viewed first, in [sort]'s order. Name
         * follows the phone's language (a collator, not code points); Date added is newest
         * first by id, which only ever grows (AUTOINCREMENT). Stable, so ties keep recency.
         */
        fun sorted(recipes: List<RecipeSummary>, sort: RecipeSort): List<RecipeSummary> = when (sort) {
            RecipeSort.RECENTLY_VIEWED -> recipes
            RecipeSort.NAME -> recipes.sortedWith(compareBy(Collator.getInstance()) { it.title })
            RecipeSort.DATE_ADDED -> recipes.sortedByDescending { it.id }
        }
    }
}
