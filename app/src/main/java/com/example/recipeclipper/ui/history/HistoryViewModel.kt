package com.example.recipeclipper.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.model.RecipeSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [recipes] is null until the database has answered, to tell "loading" from "nothing
 * matched". [pendingDeletes] holds the titles swiped away but not yet settled, newest last,
 * for the undo snackbar; it survives rotation because it lives here, not in `remember`.
 */
data class HistoryUiState(
    val query: String = "",
    val recipes: List<RecipeSummary>? = null,
    val pendingDeletes: List<String> = emptyList()
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(private val repository: RecipeRepository) : ViewModel() {

    // The query box's live value. Kept in a StateFlow, not `remember`, so it survives rotation.
    private val query = MutableStateFlow("")
    private val pendingDeletes = MutableStateFlow<List<String>>(emptyList())

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

    val uiState: StateFlow<HistoryUiState> =
        combine(query, recipes, pendingDeletes, ::HistoryUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun onQueryChange(text: String) {
        query.value = text
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
}
