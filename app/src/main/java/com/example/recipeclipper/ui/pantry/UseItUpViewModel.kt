package com.example.recipeclipper.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.UseItUp
import com.example.recipeclipper.data.model.UseItUpGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The expiry reminder's tap (#305), waiting for the Pantry tab to open the Use it up sheet on
 * everything due: MainActivity sets it and opens the tab; [UseItUpViewModel] takes it.
 */
@Singleton
class UseItUpInbox @Inject constructor() {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun offer() {
        _pending.value = true
    }

    /** Whether a tap was waiting, once: taking it empties the inbox. */
    fun take(): Boolean = _pending.getAndUpdate { false }
}

/**
 * "Use it up" (#305). [counts] is, per pantry item expiring soon, how many saved recipes use
 * it (its row's line); an item with none is absent. [sheet] is what the open sheet lists (one
 * item from its row, every one due from the reminder), null while it's closed. [planned] holds
 * each (recipe, day) already on the plan for today or tomorrow, which the sheet shows as planned
 * rather than offering again. Everything is empty while the flag is off.
 */
data class UseItUpUiState(
    val today: Long = 0,
    val counts: Map<Long, Int> = emptyMap(),
    val sheet: List<UseItUpGroup>? = null,
    val planned: Set<Pair<Long, Long>> = emptySet()
)

/** What the sheet was opened on: one pantry item's row, or the reminder (everything due). */
private sealed class UseItUpTarget {
    data class Item(val id: Long) : UseItUpTarget()
    data object All : UseItUpTarget()
}

/**
 * Backs the Pantry rows' "3 saved recipes use it" line and the sheet it opens (#305). Only
 * saved recipes (in at least one list) are read; matching is [UseItUp]'s, by name only. One
 * tap plans a recipe, as Dinner at its own yield, today or on the item's use-by day.
 */
@HiltViewModel
class UseItUpViewModel @Inject constructor(
    pantry: PantryRepository,
    lists: ListRepository,
    private val plan: MealPlanRepository,
    calendar: PlanCalendar,
    flags: FeatureFlags,
    private val inbox: UseItUpInbox
) : ViewModel() {

    private val today = calendar.today()
    private val _uiState = MutableStateFlow(UseItUpUiState(today = today))
    val uiState: StateFlow<UseItUpUiState> = _uiState.asStateFlow()

    private val target = MutableStateFlow<UseItUpTarget?>(null)
    private var mealType: Long? = null
    private val writing = mutableSetOf<Pair<Long, Long>>()

    init {
        viewModelScope.launch {
            combine(
                pantry.observeItems(), lists.observeSavedRecipes(), plan.observeDays(today, today + 1),
                flags.values, target
            ) { items, saved, meals, values, open ->
                if (!values.isOn(Flag.USE_IT_UP)) return@combine UseItUpUiState(today = today)
                val groups = UseItUp.groups(items, saved, today)
                val sheet = when (open) {
                    null -> null
                    UseItUpTarget.All -> groups
                    is UseItUpTarget.Item -> groups.filter { it.item.id == open.id }
                }?.takeIf { it.isNotEmpty() }
                UseItUpUiState(
                    today = today,
                    counts = groups.associate { it.item.id to it.recipes.size },
                    sheet = sheet,
                    planned = meals.mapNotNull { meal -> meal.recipeId?.let { it to meal.day } }.toSet()
                )
            }.collect { state ->
                // A tap is guarded until the plan shows it, so a quick second tap adds nothing.
                writing.removeAll(state.planned)
                _uiState.value = state
            }
        }
        viewModelScope.launch {
            plan.observeMealTypes().collect { types ->
                mealType = (types.firstOrNull { it.builtInKey == MealType.DINNER } ?: types.firstOrNull())?.id
            }
        }
        viewModelScope.launch {
            inbox.pending.collect { if (it && inbox.take()) target.value = UseItUpTarget.All }
        }
    }

    /** The row's line: the sheet for that one item. */
    fun onOpen(itemId: Long) {
        target.value = UseItUpTarget.Item(itemId)
    }

    fun onDismiss() {
        target.value = null
    }

    /** Plans [recipeId] on [day] as Dinner, unless it's already planned that day. */
    fun onPlan(recipeId: Long, day: Long) {
        val key = recipeId to day
        val type = mealType ?: return
        if (key in _uiState.value.planned || !writing.add(key)) return
        viewModelScope.launch { plan.addRecipe(recipeId, day, type, servings = null) }
    }
}
