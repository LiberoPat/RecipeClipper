package com.example.recipeclipper.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.model.MealPlanIcs
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.Menu
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

/** One day and its meals, in plan order. */
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

/** Moving [meal]: choose another day (today and the next 13) or meal type. */
data class MoveState(
    val meal: PlannedMeal,
    val days: List<Long>,
    val day: Long,
    val mealTypeId: Long
)

/** A meal just removed: its id keeps two removals of the same title apart. */
data class RemovedMeal(val id: Long, val label: String)

/** An .ics file to share: its name and its text (#52). */
data class CalendarFile(val fileName: String, val text: String)

/**
 * A scroll the list owes (#232): [day] to the top, animated or not. [id] tells two requests for
 * the same day apart, and lets a finished scroll clear only its own request.
 */
data class ScrollTarget(val day: Long, val animate: Boolean, val id: Int)

/** What the snackbar says after a menu action (#52). */
sealed interface MenuMessage {
    data class Saved(val name: String) : MenuMessage
    data object SaveFailed : MenuMessage
    data class Applied(val name: String, val count: Int) : MenuMessage
}

/**
 * Reusable weekly menus on the Week tab (#52). [menus] is every saved menu; [saving] opens the
 * name dialog for the week shown; [picking] opens the menus sheet (apply, rename, delete);
 * [renaming] and [deleting] are the menu being renamed or confirmed for deletion.
 */
data class MenusUiState(
    val menus: List<Menu> = emptyList(),
    val saving: Boolean = false,
    val picking: Boolean = false,
    val renaming: Menu? = null,
    val deleting: Menu? = null,
    val message: MenuMessage? = null
)

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
 * The Week tab (#49, rolling since #232). The list holds every day from [firstDay] to [lastDay],
 * one section each; [topDay] is the day at the top of it. Weeks are seven-day blocks counted
 * from [today], and [weekStart] is the block at the top: what the header names and the week's
 * actions use.
 *
 * Only [meals] of the days from [loadedFrom] to [loadedTo] are held, a window around the top
 * that moves with it; a day outside it shows no meals until the window reaches it. [removed]
 * names the meal just removed, for the undo snackbar (the recipe title, or the note).
 */
data class WeekUiState(
    val today: Long = 0,
    val firstDay: Long = 0,
    val lastDay: Long = -1,
    val topDay: Long = 0,
    val loadedFrom: Long = 0,
    val loadedTo: Long = -1,
    val meals: Map<Long, List<PlannedMeal>> = emptyMap(),
    val mealTypes: List<MealType> = emptyList(),
    val adding: AddToDayState? = null,
    val moving: MoveState? = null,
    val removed: RemovedMeal? = null,
    /** The month view, or null while the days are shown. */
    val month: MonthUiState? = null,
    /** A day the list is to bring to the top, once. */
    val scrollTo: ScrollTarget? = null,
    /** The shown week as a calendar file, waiting for the screen to share it (#52). */
    val calendarFile: CalendarFile? = null,
    /** Saved weekly menus and their dialogs (#52). */
    val menus: MenusUiState = MenusUiState()
) {
    /** The first day of the block at the top. */
    val weekStart: Long get() = PlanDays.blockStart(topDay, today)

    /** "This week": the block that starts today. */
    val isThisWeek: Boolean get() = weekStart == today

    /** How many day sections the list holds. */
    val dayCount: Int get() = (lastDay - firstDay + 1).toInt().coerceAtLeast(0)

    fun isLoaded(day: Long): Boolean = day in loadedFrom..loadedTo

    fun mealsOn(day: Long): List<PlannedMeal> = meals[day].orEmpty()

    /** The seven days of the block at the top with their meals, empty until they have loaded. */
    val days: List<WeekDay>
        get() = if (isLoaded(weekStart) && isLoaded(weekStart + 6)) {
            PlanDays.weekDays(weekStart).map { WeekDay(it, mealsOn(it)) }
        } else {
            emptyList()
        }

    /** Whether the week shown has anything to put in a calendar file or a menu. */
    val hasMeals: Boolean get() = days.any { it.meals.isNotEmpty() }
}

/**
 * The Week tab (#49): one scroll of days, opening on today; ‹ › snap to the previous or next
 * seven-day block from today (#232), and "Today" comes back. Everything is written as it
 * happens; removing a meal can be undone from the snackbar.
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

    /** The first day of the window of meals held (see [WeekUiState.meals]). */
    private val window: MutableStateFlow<Long>
    private val monthStart = MutableStateFlow<Long?>(null)
    private var removedMeal: MealPlanRepository.DeletedMeal? = null
    private var scrollRequests = 0

    init {
        val today = calendar.today()
        window = MutableStateFlow(today - WINDOW_BEFORE)
        _uiState = MutableStateFlow(
            WeekUiState(today = today, firstDay = today - DAYS_BEFORE, lastDay = today + DAYS_AFTER, topDay = today)
        )
        uiState = _uiState.asStateFlow()

        viewModelScope.launch {
            window.flatMapLatest { from ->
                plan.observeDays(from, from + WINDOW_DAYS - 1).map { meals -> from to meals }
            }.collect { (from, meals) ->
                // Replaced, never added to: what is held stays one window, however far the
                // list scrolls.
                _uiState.update {
                    it.copy(meals = meals.groupBy { meal -> meal.day }, loadedFrom = from, loadedTo = from + WINDOW_DAYS - 1)
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
            plan.observeMenus().collect { menus -> _uiState.update { it.copy(menus = it.menus.copy(menus = menus)) } }
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

    // --- Scrolling and weeks (#232)

    /**
     * The list has scrolled freely and [day] is at the top. Ignored while a requested scroll is
     * on its way, so the header doesn't flicker through the days it passes.
     */
    fun onTopDayChanged(day: Long) {
        if (_uiState.value.scrollTo != null) return
        showTop(day)
    }

    /** The list has finished (or given up) the scroll [id]; [topDay] is the day now at the top. */
    fun onScrollHandled(id: Int, topDay: Long?) {
        if (_uiState.value.scrollTo?.id != id) return
        _uiState.update { it.copy(scrollTo = null) }
        if (topDay != null) showTop(topDay)
    }

    /** › : the first day of the block after the one at the top (or the one being scrolled to). */
    fun onNextWeek() = stepBlocks(1)

    /** ‹ : the first day of the block before the one at the top. */
    fun onPreviousWeek() = stepBlocks(-1)

    /** "Today": back to today at the top, which may have changed since the screen opened. */
    fun onToday() {
        val today = calendar.today()
        _uiState.update { it.copy(today = today) }
        jumpTo(today, animate = true)
    }

    /**
     * The screen is back in front. On a new day, the weeks start from it again and today goes
     * to the top: the week starts on the day the app is opened.
     */
    fun onScreenResumed() {
        val today = calendar.today()
        if (today == _uiState.value.today) return
        _uiState.update { it.copy(today = today) }
        jumpTo(today, animate = false)
    }

    private fun stepBlocks(blocks: Int) {
        val state = _uiState.value
        val from = state.scrollTo?.day ?: state.topDay
        jumpTo(PlanDays.blockStart(from, state.today) + blocks * PlanDays.BLOCK_DAYS, animate = true)
    }

    /**
     * Asks the list to bring [day] to the top. A day outside the list's days moves the whole
     * range to sit around it (the same span as around today), and then it can't animate.
     */
    private fun jumpTo(day: Long, animate: Boolean) {
        val id = ++scrollRequests
        _uiState.update { state ->
            val inRange = day in state.firstDay..state.lastDay
            state.copy(
                topDay = day,
                firstDay = if (inRange) state.firstDay else day - DAYS_BEFORE,
                lastDay = if (inRange) state.lastDay else day + DAYS_AFTER,
                scrollTo = ScrollTarget(day, animate && inRange, id)
            )
        }
        follow(day)
    }

    private fun showTop(day: Long) {
        _uiState.update { it.copy(topDay = day) }
        follow(day)
    }

    /**
     * Moves the window of meals when the block before or after the one at the top would fall
     * outside it, re-centring it on that block: one query per four weeks scrolled.
     */
    private fun follow(day: Long) {
        val start = PlanDays.blockStart(day, _uiState.value.today)
        val from = window.value
        val to = from + WINDOW_DAYS - 1
        if (start - PlanDays.BLOCK_DAYS < from || start + 2 * PlanDays.BLOCK_DAYS - 1 > to) {
            window.value = start - WINDOW_BEFORE
        }
    }

    // --- Month view (#52)

    /** Shows the month of the week shown: today's month for this week, else the month holding
     *  most of the week (its fourth day). */
    fun onShowMonth() {
        val state = _uiState.value
        val today = calendar.today()
        val first = PlanDays.monthStart(if (state.isThisWeek) today else state.weekStart + 3)
        _uiState.update { it.copy(month = MonthUiState(first, PlanDays.monthStart(today))) }
        monthStart.value = first
    }

    /** Back to the days, where they were. */
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
        _uiState.update { it.copy(month = it.month?.copy(thisMonthStart = PlanDays.monthStart(today))) }
        showMonth(PlanDays.monthStart(today))
    }

    private fun showMonth(first: Long) {
        _uiState.update { it.copy(month = it.month?.copy(monthStart = first, days = emptyList())) }
        monthStart.value = first
    }

    /** A day in the month grid: back to the days, with that one at the top. */
    fun onMonthDaySelected(day: Long) {
        monthStart.value = null
        _uiState.update { it.copy(month = null) }
        jumpTo(day, animate = false)
    }

    // --- Calendar file (#52)

    /** The week shown as an .ics file, for the screen to share. Nothing for an empty week. */
    fun onShareCalendar() {
        val state = _uiState.value
        if (!state.hasMeals) return
        val text = MealPlanIcs.calendar(
            state.days.flatMap { it.meals },
            state.mealTypes.associate { it.id to it.name },
            calendar.now()
        )
        _uiState.update { it.copy(calendarFile = CalendarFile(MealPlanIcs.fileName(state.weekStart), text)) }
    }

    /** The share sheet has opened (or couldn't): the file is done with. */
    fun onCalendarShared() = _uiState.update { it.copy(calendarFile = null) }

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
                days = (0 until PlanDays.SHEET_DAYS).map { offset -> it.today + offset },
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

    // --- Menus (#52)

    private fun updateMenus(change: (MenusUiState) -> MenusUiState) =
        _uiState.update { it.copy(menus = change(it.menus)) }

    fun onSaveMenuStart() = updateMenus { it.copy(saving = true) }

    fun onSaveMenuDismissed() = updateMenus { it.copy(saving = false) }

    /** Saves the week shown as [name]. A blank name does nothing. */
    fun onSaveMenu(name: String) {
        val text = name.trim()
        if (text.isEmpty()) return
        val start = _uiState.value.weekStart
        updateMenus { it.copy(saving = false) }
        viewModelScope.launch {
            val saved = plan.saveWeekAsMenu(text, start, calendar.firstDayOfWeek())
            updateMenus { it.copy(message = if (saved) MenuMessage.Saved(text) else MenuMessage.SaveFailed) }
        }
    }

    fun onPickMenuStart() = updateMenus { it.copy(picking = true) }

    fun onPickMenuDismissed() = updateMenus { it.copy(picking = false) }

    /** Adds [menu]'s meals to the week shown, after what is planned there. */
    fun onApplyMenu(menu: Menu) {
        val start = _uiState.value.weekStart
        updateMenus { it.copy(picking = false) }
        viewModelScope.launch {
            val added = plan.applyMenu(menu.id, start, calendar.firstDayOfWeek())
            updateMenus { it.copy(message = MenuMessage.Applied(menu.name, added)) }
        }
    }

    fun onRenameMenuStart(menu: Menu) = updateMenus { it.copy(renaming = menu) }

    fun onRenameMenuDismissed() = updateMenus { it.copy(renaming = null) }

    fun onRenameMenu(name: String) {
        val menu = _uiState.value.menus.renaming ?: return
        if (name.isBlank()) return
        updateMenus { it.copy(renaming = null) }
        viewModelScope.launch { plan.renameMenu(menu.id, name) }
    }

    fun onDeleteMenuStart(menu: Menu) = updateMenus { it.copy(deleting = menu) }

    fun onDeleteMenuDismissed() = updateMenus { it.copy(deleting = null) }

    fun onDeleteMenuConfirm() {
        val menu = _uiState.value.menus.deleting ?: return
        updateMenus { it.copy(deleting = null) }
        viewModelScope.launch { plan.deleteMenu(menu.id) }
    }

    fun onMenuMessageShown() = updateMenus { it.copy(message = null) }

    private fun defaultType(types: List<MealType>): Long? =
        (types.firstOrNull { it.builtInKey == MealType.DINNER } ?: types.firstOrNull())?.id

    companion object {
        /** The list's days: a year back and two ahead of today (or of a day jumped to beyond). */
        const val DAYS_BEFORE = 365L
        const val DAYS_AFTER = 730L

        /** The window of meals held: four weeks either side of the block at the top. */
        const val WINDOW_BEFORE = 28L
        const val WINDOW_DAYS = 63L
    }
}
