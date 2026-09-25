package com.example.recipeclipper.ui.week

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.model.GrocerySources
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PantryMatch
import com.example.recipeclipper.data.model.WeekNeeds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [needs] is null while loading. [added] is set once the Buy lines are on the grocery list. */
data class WhatINeedUiState(
    val weekStart: Long,
    val needs: WeekNeeds? = null,
    val added: Boolean = false
)

/**
 * The week's "What I need" (#51): every planned recipe's lines, rendered at the planned
 * servings in the user's units ([GrocerySources.fromPlan], as the grocery sheet does), grouped
 * by ingredient and marked Have or Buy against the pantry ([PantryMatch]). Follows the pantry
 * live, so ticking something in stock elsewhere moves it across.
 */
@HiltViewModel
class WhatINeedViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groceries: GroceryRepository,
    private val pantry: PantryRepository,
    private val preferences: AppPreferences
) : ViewModel() {

    private val start: Long = checkNotNull(savedStateHandle.get<Long>(WEEK_START_ARG))

    private val _uiState = MutableStateFlow(WhatINeedUiState(weekStart = start))
    val uiState: StateFlow<WhatINeedUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = preferences.current
            val sources = GrocerySources.fromPlan(
                groceries.plannedIngredients(start, start + 6), settings.unitSystem, settings.convertLiquids
            )
            pantry.observeItems().collect { items ->
                _uiState.update { it.copy(needs = PantryMatch.weekNeeds(sources, items)) }
            }
        }
    }

    /** Every Buy line onto the grocery list, through the same path as the grocery sheet (#50). */
    fun onAddBuyToGroceries() {
        val state = _uiState.value
        if (state.added) return
        val lines = state.needs?.buy.orEmpty().flatMap { row ->
            row.lines.map { NewGroceryLine(it.text, it.language, it.recipeId, it.day) }
        }
        if (lines.isEmpty()) return
        _uiState.update { it.copy(added = true) }
        viewModelScope.launch { groceries.add(lines) }
    }

    companion object {
        const val WEEK_START_ARG = "weekStart"
    }
}
