package com.example.recipeclipper.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.Servings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The "Add to plan" sheet (#49): a strip of days (this week and next), a meal type, and the
 * servings. [selectedMealTypeId] is null only until the types have loaded; it then defaults to
 * Dinner. [servings] is null for a recipe whose yield has no number (no stepper, as on the
 * recipe screen). [added] is set once the meal is written, and the sheet closes on it.
 */
data class AddToPlanUiState(
    val days: List<Long> = emptyList(),
    val today: Long = 0,
    val selectedDay: Long = 0,
    val mealTypes: List<MealType> = emptyList(),
    val selectedMealTypeId: Long? = null,
    val servings: Int? = null,
    val added: Boolean = false
) {
    val canAdd: Boolean get() = selectedMealTypeId != null && !added
}

/**
 * Backs the sheet opened from the recipe screen's overflow menu. Unlike save-to-list, adding is
 * one deliberate act with a button: a day and a meal type are chosen first, so there is no
 * sensible moment to write before it.
 *
 * The recipe arrives through [setRecipe], as with [com.example.recipeclipper.ui.savetolist.SaveToListViewModel],
 * because the sheet is not a navigation destination.
 */
@HiltViewModel
class AddToPlanViewModel @Inject constructor(
    private val repository: MealPlanRepository,
    private val calendar: PlanCalendar
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddToPlanUiState())
    val uiState: StateFlow<AddToPlanUiState> = _uiState.asStateFlow()

    private var recipeId: Long? = null

    init {
        viewModelScope.launch {
            repository.observeMealTypes().collect { types ->
                _uiState.update { state ->
                    val keep = state.selectedMealTypeId?.takeIf { id -> types.any { it.id == id } }
                    state.copy(mealTypes = types, selectedMealTypeId = keep ?: defaultType(types))
                }
            }
        }
    }

    /**
     * Opens the sheet for [id], starting on today, Dinner and the recipe's own yield
     * ([yieldServings], null when it has no number).
     */
    fun setRecipe(id: Long, yieldServings: Int?) {
        recipeId = id
        val today = calendar.today()
        val start = PlanDays.weekStart(today, calendar.firstDayOfWeek())
        _uiState.update {
            it.copy(
                days = (0 until PlanDays.SHEET_DAYS).map { offset -> start + offset },
                today = today,
                selectedDay = today,
                selectedMealTypeId = defaultType(it.mealTypes),
                servings = yieldServings,
                added = false
            )
        }
    }

    fun onDaySelected(day: Long) = _uiState.update { it.copy(selectedDay = day) }

    fun onMealTypeSelected(id: Long) = _uiState.update { it.copy(selectedMealTypeId = id) }

    fun onServingsChange(value: Int) = _uiState.update { state ->
        if (state.servings == null) state else state.copy(servings = value.coerceIn(1, Servings.MAX))
    }

    fun onAdd() {
        val state = _uiState.value
        val recipe = recipeId ?: return
        val type = state.selectedMealTypeId ?: return
        if (state.added) return
        _uiState.update { it.copy(added = true) }
        viewModelScope.launch { repository.addRecipe(recipe, state.selectedDay, type, state.servings) }
    }

    private fun defaultType(types: List<MealType>): Long? =
        (types.firstOrNull { it.builtInKey == MealType.DINNER } ?: types.firstOrNull())?.id
}
