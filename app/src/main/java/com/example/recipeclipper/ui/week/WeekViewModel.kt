package com.example.recipeclipper.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.data.model.RecipeSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One day of the week and its meals, in plan order. */
data class WeekDay(val day: Long, val meals: List<PlannedMeal>)

/**
 * The "+" sheet of one day: pick a meal type, then a recipe from history (searchable) or type
 * a note. [results] is null until history has answered.
 */
data class AddToDayState(
    val day: Long,
    val mealTypeId: Long?,
    val query: String = "",
    val results: List<RecipeSummary>? = null
)

/** Moving [meal]: choose another day (this week and next, from the week shown) or meal type. */
data class MoveState(
    val meal: PlannedMeal,
    val days: List<Long>,
    val day: Long,
    val mealTypeId: Long
)

/** A meal just removed: its id keeps two removals of the same title apart. */
data class RemovedMeal(val id: Long, val label: String)

/** One cell of the month grid: [inMonth] is false for the days that fill the first and last rows. */
data class MonthDay(val day: Long, val inMonth: Boolean, val mealCount: Int)

/**
 * The month view (#52): [days] is whole weeks from the locale's first day (see
 * [PlanDays.monthGrid]), empty until the plan has loaded.
 */
data class MonthUiState(
    val monthStart: Long,
    val thisMonthStart: Long,
    val days: List<MonthDay> = emptyList()
) {
    val isThisMonth: Boolean get() = monthStart == thisMonthStart
}

/**
 * [days] is empty until the plan has loaded. [removed] names the meal just removed, for the
 * undo snackbar (the recipe title, or the note).
 */
data class WeekUiState(
    val weekStart: Long = 0,
    val thisWeekStart: Long = 0,
    val today: Long = 0,
    val days: List<WeekDay> = emptyList(),
    val mealTypes: List<MealType> = emptyList(),
    val adding: AddToDayState? = null,
    val moving: MoveState? = null,
    val removed: RemovedMeal? = null,
    /** The month view, or null while the week is shown. */
    val month: MonthUiState? = null,
    /** A day the week view scrolls to once, after a tap in the month view. */
    val focusDay: Long? = null
) {
    val isThisWeek: Boolean get() = weekStart == thisWeekStart
}

/**
 * The Week tab (#49): seven days from the locale's first day of the week, ‹ › between weeks,
 * and "This week" back. Everything is written as it happens; removing a meal can be undone
 * from the snackbar.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class WeekViewModel @Inject constructor(
    private val plan: MealPlanRepository,
    private val recipes: RecipeRepository,
    private val calendar: PlanCalendar
) : ViewModel() {

    private val _uiState: MutableStateFlow<WeekUiState>
    val uiState: StateFlow<WeekUiState>

    private val weekStart: MutableStateFlow<Long>
    private val monthStart = MutableStateFlow<Long?>(null)
    private var removedMeal: MealPlanRepository.DeletedMeal? = null

    init {
        val today = calendar.today()
        val start = PlanDays.weekStart(today, calendar.firstDayOfWeek())
        weekStart = MutableStateFlow(start)
        _uiState = MutableStateFlow(WeekUiState(weekStart = start, thisWeekStart = start, today = today))
        uiState = _uiState.asStateFlow()

        viewModelScope.launch {
            weekStart.flatMapLatest { first ->
                plan.observeDays(first, first + 6).map { meals -> first to meals }
            }.collect { (first, meals) ->
                val byDay = meals.groupBy { it.day }
                _uiState.update { state ->
                    state.copy(days = PlanDays.weekDays(first).map { WeekDay(it, byDay[it].orEmpty()) })
                }
            }
        }
        viewModelScope.launch {
            monthStart.flatMapLatest { first ->
                if (first == null) {
                    flowOf(null)
                } else {
                    val grid = PlanDays.monthGrid(first, calendar.firstDayOfWeek())
                    plan.observeDays(grid.first(), grid.last()).map { meals -> Triple(first, grid, meals) }
                }
            }.collect { loaded ->
                val (first, grid, meals) = loaded ?: return@collect
                val counts = meals.groupingBy { it.day }.eachCount()
                val next = PlanDays.addMonths(first, 1)
                _uiState.update { state ->
                    state.copy(
                        month = state.month?.takeIf { it.monthStart == first }?.copy(
                            days = grid.map { MonthDay(it, inMonth = it in first until next, mealCount = counts[it] ?: 0) }
                        ) ?: state.month
                    )
                }
            }
        }
        viewModelScope.launch {
            plan.observeMealTypes().collect { types -> _uiState.update { it.copy(mealTypes = types) } }
        }
        viewModelScope.launch {
            _uiState.map { it.adding?.query }
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(250)
                .flatMapLatest { recipes.observeHistory(it) }
                .collect { found ->
                    _uiState.update { state -> state.copy(adding = state.adding?.copy(results = found)) }
                }
        }
    }

    // --- Weeks

    fun onPreviousWeek() = showWeek(weekStart.value - 7)

    fun onNextWeek() = showWeek(weekStart.value + 7)

    /** Back to the week holding today, which may have changed since the screen opened. */
    fun onThisWeek() {
        val today = calendar.today()
        val start = PlanDays.weekStart(today, calendar.firstDayOfWeek())
        _uiState.update { it.copy(today = today, thisWeekStart = start) }
        showWeek(start)
    }

    // --- Month view (#52)

    /** Shows the month of the week shown: today's month for this week, else the month holding
     *  most of the week (its fourth day). */
    fun onShowMonth() {
        val state = _uiState.value
        val today = calendar.today()
        val first = PlanDays.monthStart(if (state.isThisWeek) today else state.weekStart + 3)
        _uiState.update { it.copy(today = today, month = MonthUiState(first, PlanDays.monthStart(today))) }
        monthStart.value = first
    }

    /** Back to the week view, on the week that was shown. */
    fun onShowWeek() {
        monthStart.value = null
        _uiState.update { it.copy(month = null) }
    }

    fun onPreviousMonth() {
        _uiState.value.month?.let { showMonth(PlanDays.addMonths(it.monthStart, -1)) }
    }

    fun onNextMonth() {
        _uiState.value.month?.let { showMonth(PlanDays.addMonths(it.monthStart, 1)) }
    }

    /** Back to the month holding today, which may have changed since the view opened. */
    fun onThisMonth() {
        val today = calendar.today()
        _uiState.update { it.copy(today = today, month = it.month?.copy(thisMonthStart = PlanDays.monthStart(today))) }
        showMonth(PlanDays.monthStart(today))
    }

    private fun showMonth(first: Long) {
        _uiState.update { it.copy(month = it.month?.copy(monthStart = first, days = emptyList())) }
        monthStart.value = first
    }

    /** A day in the month grid: back to the week view, on that day's week, scrolled to it. */
    fun onMonthDaySelected(day: Long) {
        monthStart.value = null
        _uiState.update { it.copy(month = null, focusDay = day) }
        showWeek(PlanDays.weekStart(day, calendar.firstDayOfWeek()))
    }

    /** The week view has scrolled to [WeekUiState.focusDay]. */
    fun onFocusHandled() = _uiState.update { it.copy(focusDay = null) }

    private fun showWeek(start: Long) {
        // The same week again (a day of it tapped in the month view) keeps its loaded days: the
        // StateFlow wouldn't emit again to refill them.
        if (start == weekStart.value) return
        // Clear first: on Main.immediate the new week's days can arrive before this returns.
        _uiState.update { it.copy(weekStart = start, days = emptyList()) }
        weekStart.value = start
    }

    // --- Adding to a day

    fun onAddToDay(day: Long) = _uiState.update {
        it.copy(adding = AddToDayState(day = day, mealTypeId = defaultType(it.mealTypes)))
    }

    fun onAddQueryChange(query: String) = _uiState.update { it.copy(adding = it.adding?.copy(query = query)) }

    fun onAddMealTypeSelected(id: Long) = _uiState.update { it.copy(adding = it.adding?.copy(mealTypeId = id)) }

    fun onAddRecipe(recipeId: Long) {
        val adding = _uiState.value.adding ?: return
        val type = adding.mealTypeId ?: return
        _uiState.update { it.copy(adding = null) }
        viewModelScope.launch { plan.addRecipe(recipeId, adding.day, type, servings = null) }
    }

    /** The search field's text, planned as a note. */
    fun onAddNote() {
        val adding = _uiState.value.adding ?: return
        val type = adding.mealTypeId ?: return
        if (adding.query.isBlank()) return
        _uiState.update { it.copy(adding = null) }
        viewModelScope.launch { plan.addNote(adding.query, adding.day, type) }
    }

    fun onAddDismissed() = _uiState.update { it.copy(adding = null) }

    // --- Moving

    fun onMoveStart(meal: PlannedMeal) = _uiState.update {
        it.copy(
            moving = MoveState(
                meal = meal,
                days = (0 until PlanDays.SHEET_DAYS).map { offset -> it.weekStart + offset },
                day = meal.day,
                mealTypeId = meal.mealTypeId
            )
        )
    }

    fun onMoveDaySelected(day: Long) = _uiState.update { it.copy(moving = it.moving?.copy(day = day)) }

    fun onMoveMealTypeSelected(id: Long) = _uiState.update { it.copy(moving = it.moving?.copy(mealTypeId = id)) }

    fun onMoveConfirm() {
        val moving = _uiState.value.moving ?: return
        _uiState.update { it.copy(moving = null) }
        viewModelScope.launch { plan.move(moving.meal.id, moving.day, moving.mealTypeId) }
    }

    fun onMoveDismissed() = _uiState.update { it.copy(moving = null) }

    // --- Removing, with undo

    /** Removes a meal from the plan, never the recipe. One undo at a time: a second removal
     *  settles the first. */
    fun onRemove(meal: PlannedMeal) {
        viewModelScope.launch {
            val deleted = plan.delete(meal.id) ?: return@launch
            removedMeal = deleted
            _uiState.update { it.copy(removed = RemovedMeal(meal.id, meal.title ?: meal.note.orEmpty())) }
        }
    }

    fun onUndoRemove() {
        val deleted = removedMeal ?: return
        removedMeal = null
        _uiState.update { it.copy(removed = null) }
        viewModelScope.launch { plan.restore(deleted) }
    }

    /** The snackbar timed out or was dismissed: the removal stands. */
    fun onSnackbarDismissed() {
        removedMeal = null
        _uiState.update { it.copy(removed = null) }
    }

    private fun defaultType(types: List<MealType>): Long? =
        (types.firstOrNull { it.builtInKey == MealType.DINNER } ?: types.firstOrNull())?.id
}
