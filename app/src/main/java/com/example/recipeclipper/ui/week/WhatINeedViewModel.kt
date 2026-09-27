package com.example.recipeclipper.ui.week

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.GroceryRepository
import com.example.recipeclipper.data.model.DecisionCandidates
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.PantryItem
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    private val preferences: AppPreferences,
    // The model's "same ingredient?" answers (#104); none without it.
    private val decisions: DecisionRepository? = null
) : ViewModel() {

    private val start: Long = checkNotNull(savedStateHandle.get<Long>(WEEK_START_ARG))

    private val _uiState = MutableStateFlow(WhatINeedUiState(weekStart = start))
    val uiState: StateFlow<WhatINeedUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = preferences.current
            val sources = GrocerySources.fromPlan(
                groceries.plannedIngredients(start, start + 6), settings.unitSystem, settings.convertLiquids,
                decisions?.current() ?: Decisions.NONE
            )
            val decided = decisions?.observe() ?: flowOf(Decisions.NONE)
            pantry.observeItems().combine(decided) { items, d -> items to d }.collect { (items, d) ->
                val needs = PantryMatch.weekNeeds(sources, items, d)
                _uiState.update { it.copy(needs = needs) }
                askSame(needs, items)
            }
        }
    }

    /**
     * Asks the model, in the background, whether a Buy row's close pantry names are the same
     * ingredient (#104). A "same" moves the row to Have once it lands; anything else leaves it.
     */
    private fun askSame(needs: WeekNeeds, items: List<PantryItem>) {
        val repo = decisions ?: return
        val questions = needs.buy.filter { it.name != null }.groupBy { it.lines.first().language }
            .flatMap { (language, rows) -> DecisionCandidates.samePairs(rows.mapNotNull { it.name }, language, items) }
        if (questions.isNotEmpty()) viewModelScope.launch { repo.decide(questions) }
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
