package com.example.recipeclipper.ui.groceries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.model.GrocerySource
import com.example.recipeclipper.data.model.GrocerySources
import com.example.recipeclipper.data.model.NewGroceryLine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One line in the sheet: [source]'s line number [index]. */
data class SourceLine(val source: String, val index: Int)

/**
 * The "Add to groceries" sheet (#50): one recipe's lines (from its menu) or every planned
 * recipe's (from the Week), each with a checkbox, all ticked to start. [sources] is null while
 * the week's are loading. [added] is set once the ticked lines are written; the sheet closes on it.
 */
data class AddToGroceriesUiState(
    val sources: List<GrocerySource>? = null,
    val unticked: Set<SourceLine> = emptySet(),
    val added: Boolean = false
) {
    val tickedCount: Int
        get() = sources.orEmpty().sumOf { s -> s.lines.indices.count { SourceLine(s.key, it) !in unticked } }
}

/**
 * Backs the "Add to groceries" sheet. Unlike the list itself, adding is one deliberate act with
 * a button: the cook first unticks what's already in the cupboard. The lines arrive through
 * [setRecipe] or [loadWeek], as with the plan sheet, because the sheet is not a destination.
 */
@HiltViewModel
class AddToGroceriesViewModel @Inject constructor(
    private val repository: GroceryRepository,
    private val preferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddToGroceriesUiState())
    val uiState: StateFlow<AddToGroceriesUiState> = _uiState.asStateFlow()

    /** One recipe, its [rendered] lines exactly as the reading view shows them. */
    fun setRecipe(recipeId: Long, title: String, language: String?, rendered: List<String>) {
        _uiState.value = AddToGroceriesUiState(
            sources = listOf(GrocerySources.fromRecipe(recipeId, title, language, rendered))
        )
    }

    /** Every recipe planned from [start] for seven days, at its planned servings, in the user's units. */
    fun loadWeek(start: Long) {
        _uiState.value = AddToGroceriesUiState()
        viewModelScope.launch {
            val planned = repository.plannedIngredients(start, start + 6)
            val settings = preferences.current
            val sources = GrocerySources.fromPlan(planned, settings.unitSystem, settings.convertLiquids)
            _uiState.update { it.copy(sources = sources) }
        }
    }

    fun onToggle(line: SourceLine) = _uiState.update {
        it.copy(unticked = if (line in it.unticked) it.unticked - line else it.unticked + line)
    }

    fun onAdd() {
        val state = _uiState.value
        if (state.added) return
        val lines = state.sources.orEmpty().flatMap { source ->
            source.lines.mapIndexedNotNull { index, text ->
                if (SourceLine(source.key, index) in state.unticked) null
                else NewGroceryLine(text, source.language, source.recipeId, source.day)
            }
        }
        if (lines.isEmpty()) return
        _uiState.update { it.copy(added = true) }
        viewModelScope.launch { repository.add(lines) }
    }
}
