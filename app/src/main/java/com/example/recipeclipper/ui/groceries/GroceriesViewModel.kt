package com.example.recipeclipper.ui.groceries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.GroceryCombiner
import com.example.recipeclipper.data.model.GroceryShareText
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.NewGroceryLine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What the undo snackbar says was removed: one row's [label], or the checked items ([label] null). */
data class RemovedGroceries(val id: Long, val label: String?)

/**
 * [sections] is null until the list has loaded. [draft] is the "Add an item" field. [moving]
 * is the row whose aisle is being chosen.
 */
data class GroceriesUiState(
    val sections: List<GroceryCombiner.Section>? = null,
    val draft: String = "",
    val moving: GroceryCombiner.Row? = null,
    val removed: RemovedGroceries? = null
) {
    val hasChecked: Boolean get() = sections.orEmpty().any { s -> s.rows.any { r -> r.items.any { it.checked } } }
    val isEmpty: Boolean get() = sections?.isEmpty() == true
}

/**
 * The Groceries tab (#50): the list grouped by aisle, with lines naming the same ingredient
 * together (and added up when that's exact, [GroceryCombiner]). Everything is written as it
 * happens; a delete or "Clear checked" can be undone from the snackbar.
 *
 * A typed item has no recipe, so it's read with the phone's language when the app has words
 * for it, else English: the one place the phone's language picks the words, since the person
 * typing it is the only source of it.
 */
@HiltViewModel
class GroceriesViewModel @Inject constructor(
    private val repository: GroceryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroceriesUiState())
    val uiState: StateFlow<GroceriesUiState> = _uiState.asStateFlow()

    private var removedItems: GroceryRepository.DeletedItems? = null
    private var removals = 0L

    init {
        viewModelScope.launch {
            repository.observeItems().collect { items ->
                val sections = GroceryCombiner.sections(items)
                _uiState.update { state ->
                    // A row being moved that has since gone closes the aisle picker.
                    val moving = state.moving?.takeIf { row -> row.items.all { i -> items.any { it.id == i.id } } }
                    state.copy(sections = sections, moving = moving)
                }
            }
        }
    }

    fun onDraftChange(text: String) = _uiState.update { it.copy(draft = text) }

    fun onAddTyped() {
        val text = _uiState.value.draft.trim()
        if (text.isEmpty()) return
        _uiState.update { it.copy(draft = "") }
        viewModelScope.launch { repository.add(listOf(NewGroceryLine(text, typedLanguage()))) }
    }

    /** Ticks or unticks every line in [row]: a combined row is one thing to pick up. */
    fun onToggle(row: GroceryCombiner.Row) {
        val checked = !row.items.all { it.checked }
        viewModelScope.launch { repository.setChecked(row.items.map { it.id }, checked) }
    }

    fun onMoveStart(row: GroceryCombiner.Row) = _uiState.update { it.copy(moving = row) }

    fun onMoveTo(aisle: Aisle) {
        val row = _uiState.value.moving ?: return
        _uiState.update { it.copy(moving = null) }
        viewModelScope.launch { repository.setAisle(row.items.map { it.id }, aisle) }
    }

    fun onMoveDismissed() = _uiState.update { it.copy(moving = null) }

    // --- Removing, with undo. One undo at a time: a second removal settles the first.

    fun onDelete(row: GroceryCombiner.Row, label: String) {
        viewModelScope.launch {
            val deleted = repository.delete(row.items.map { it.id }) ?: return@launch
            removed(deleted, label)
        }
    }

    fun onClearChecked() {
        viewModelScope.launch {
            val deleted = repository.clearChecked() ?: return@launch
            removed(deleted, null)
        }
    }

    private fun removed(deleted: GroceryRepository.DeletedItems, label: String?) {
        removedItems = deleted
        _uiState.update { it.copy(removed = RemovedGroceries(++removals, label)) }
    }

    fun onUndoRemove() {
        val deleted = removedItems ?: return
        removedItems = null
        _uiState.update { it.copy(removed = null) }
        viewModelScope.launch { repository.restore(deleted) }
    }

    /** The snackbar timed out or was dismissed: the removal stands. */
    fun onSnackbarDismissed() {
        removedItems = null
        _uiState.update { it.copy(removed = null) }
    }

    /** The list as plain text for the share sheet; null when there's nothing left to buy. */
    fun shareText(title: String, aisleName: (Aisle) -> String): String? {
        val sections = _uiState.value.sections.orEmpty()
        if (sections.none { s -> s.rows.any { r -> r.items.none { it.checked } } }) return null
        return GroceryShareText.format(sections, title, aisleName)
    }

    private fun typedLanguage(): String =
        LanguageWords.forTag(Locale.getDefault().language)?.language ?: LanguageWords.ENGLISH.language
}
