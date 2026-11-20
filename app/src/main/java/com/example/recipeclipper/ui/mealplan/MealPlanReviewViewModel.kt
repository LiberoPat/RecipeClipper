package com.example.recipeclipper.ui.mealplan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.Entitlements
import com.example.recipeclipper.data.LibraryPolicy
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.PlanCalendar
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.FreshMeal
import com.example.recipeclipper.data.model.FreshNudge
import com.example.recipeclipper.data.model.FreshPlan
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.Perishable
import com.example.recipeclipper.data.model.Perishables
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.needsNotice
import com.example.recipeclipper.data.remote.MealPlanImport
import com.example.recipeclipper.di.DefaultDispatcher
import com.example.recipeclipper.ui.edit.SharedTextInbox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One recipe of the review (#352). [day] is the day the cook has chosen to plan it on (an epoch
 * day), null to leave it unplanned; [passed] says the answer's own day is already behind us, so
 * the row asks for another. [fits] is false for a kept recipe the free library has no room for.
 */
data class ReviewItem(
    val index: Int,
    val name: String,
    /** The first ingredients, to tell recipes apart at a glance. */
    val preview: String,
    val day: Long?,
    val passed: Boolean,
    val ticked: Boolean,
    val fits: Boolean = true
)

/**
 * [room] is how many more recipes the free library holds, null without a limit. [kept] counts the
 * recipes saved from this review so far, [planned] those of them put on a day.
 */
data class MealPlanReviewUiState(
    val loading: Boolean = true,
    val items: List<ReviewItem> = emptyList(),
    /** The days the pickers offer: today and the next 13. */
    val days: List<Long> = emptyList(),
    val room: Int? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val kept: Int = 0,
    val planned: Int = 0,
    /** Everything left was saved: the screen opens Week (when [planned]) or goes back. */
    val finished: Boolean = false,
    /** A purchase from the review's Unlock that is pending or failed; shown until the next change. */
    val unlockNotice: PurchaseOutcome? = null,
    /** Fresh food (#396): the quiet notes, by [ReviewItem.index]; none with the flag off. */
    val nudges: Map<Int, FreshNudge> = emptyMap()
) {
    val tickedCount: Int get() = items.count { it.ticked }

    /** The kept recipes that will be saved: the ticked ones that fit. */
    val savingCount: Int get() = items.count { it.ticked && it.fits }

    /** Some ticked recipes won't be saved for want of room: the review says so and offers Unlock. */
    val overLimit: Boolean get() = items.any { it.ticked && !it.fits }
}

/**
 * The review of a week of recipes shared in (#352): the answer to "Plan my week" (#353) read once
 * by [MealPlanImport] and left in [SharedTextInbox]; a recipe at a time ticked (all are at first),
 * with its day to change or drop. **Nothing is saved before Save**; then each kept recipe goes
 * through the same [RecipeRepository.addManual] as a single chat recipe (CHAT, `manual:<uuid>`,
 * so the one `upsert` and its library limit decide) and is planned on its day under Dinner.
 *
 * The free limit (#107, #345): [MealPlanReviewUiState.room] says how many more the library holds
 * (from the limit and the count, both followed); the first ticked recipes that fit are saved, the
 * rest stay listed, never at the cost of anything old, and Unlock (a purchase) makes room. A day
 * that has passed, or none, leaves the recipe unplanned until the cook picks one.
 *
 * After process death the text is read again from saved state, with every recipe ticked again.
 */
@HiltViewModel
class MealPlanReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recipes: RecipeRepository,
    private val plan: MealPlanRepository,
    private val calendar: PlanCalendar,
    private val policy: LibraryPolicy = LibraryPolicy.HistoryOnly,
    private val entitlements: Entitlements = Entitlements.Unavailable,
    private val sharedTexts: SharedTextInbox = SharedTextInbox(),
    @DefaultDispatcher private val reading: CoroutineDispatcher = Dispatchers.Default,
    private val flags: FeatureFlags? = null
) : ViewModel() {

    private val entry: SharedTextInbox.PlanEntry? = savedStateHandle.get<String>(PLAN_ARG)
        ?.takeIf { it.isNotBlank() }?.let(sharedTexts::takePlan)
        ?.also { savedStateHandle[PLAN_STATE] = it.text }

    private val text: String? = entry?.text ?: savedStateHandle.get<String>(PLAN_STATE)?.takeIf { it.isNotBlank() }

    private val today = calendar.today()
    private val _uiState = MutableStateFlow(
        MealPlanReviewUiState(days = (0 until PlanDays.SHEET_DAYS).map { today + it })
    )
    val uiState: StateFlow<MealPlanReviewUiState> = _uiState.asStateFlow()

    /** What was read, by [ReviewItem.index]; the rows are these with the cook's choices. */
    private var read: List<MealPlanImport.Item> = emptyList()

    /** Each recipe's most delicate perishable (#396), by [ReviewItem.index]; empty with the flag off. */
    private var perishables: Map<Int, Perishable> = emptyMap()

    init {
        viewModelScope.launch {
            val found = entry?.plan ?: text?.let { withContext(reading) { MealPlanImport.read(it) } }
            read = found?.items.orEmpty()
            if (flags?.isOn(Flag.FRESH_FOOD) == true) {
                perishables = read.withIndex().mapNotNull { (i, item) ->
                    Perishables.mostDelicate(item.recipe.ingredients, LanguageWords.forRecipe(item.recipe))?.let { i to it }
                }.toMap()
            }
            _uiState.update { state ->
                fitted(state.copy(loading = false, items = read.mapIndexed { i, item -> rowOf(i, item) }))
            }
        }
        viewModelScope.launch {
            combine(policy.limits, recipes.observeCount()) { limit, count -> roomOf(limit, count) }
                .collect { room -> _uiState.update { fitted(it.copy(room = room)) } }
        }
    }

    private fun rowOf(index: Int, item: MealPlanImport.Item): ReviewItem {
        val named = item.day
        val passed = named != null && named < today
        return ReviewItem(
            index = index,
            name = item.recipe.name,
            preview = item.recipe.ingredients.take(PREVIEW_LINES).joinToString(", "),
            day = named?.takeIf { !passed },
            passed = passed,
            ticked = true
        )
    }

    /** How many more recipes the library holds under [limit] with [count] saved; null: no limit. */
    private fun roomOf(limit: LibraryLimit, count: Int): Int? = when (limit) {
        is LibraryLimit.Capped -> (limit.max - count).coerceAtLeast(0)
        is LibraryLimit.Free -> (limit.max - count).coerceAtLeast(0)
        is LibraryLimit.History, LibraryLimit.Unlimited -> null
    }

    /**
     * [state] with each ticked row marked as fitting or not (the first [MealPlanReviewUiState.room]
     * do), and the plan's quiet notes (#396) worked out again from the days the rows now hold.
     */
    private fun fitted(state: MealPlanReviewUiState): MealPlanReviewUiState {
        var left = state.room
        val items = state.items.map { row ->
            val remaining = left
            val fits = !row.ticked || remaining == null || remaining > 0
            if (row.ticked && remaining != null && remaining > 0) left = remaining - 1
            row.copy(fits = fits)
        }
        return state.copy(items = items, nudges = nudgesOf(items))
    }

    private fun nudgesOf(items: List<ReviewItem>): Map<Int, FreshNudge> {
        if (perishables.isEmpty()) return emptyMap()
        val meals = items.filter { it.ticked && it.day != null }.map { FreshMeal(it.index.toLong(), it.day!!, perishables[it.index]) }
        return FreshPlan.nudges(meals, today).associateBy { it.key.toInt() }
    }

    /**
     * The cook tapped "Swap with ..." under a note (#396): this row and the one it names trade days.
     * Only on the tap; nothing is saved before Save either.
     */
    fun onSwap(index: Int) {
        val nudge = _uiState.value.nudges[index] ?: return
        val partner = nudge.swapKey.toInt()
        change { items ->
            items.map {
                when (it.index) {
                    index -> it.copy(day = nudge.swapDay)
                    partner -> it.copy(day = nudge.day)
                    else -> it
                }
            }
        }
    }

    fun onTick(index: Int) = change { items ->
        items.map { if (it.index == index) it.copy(ticked = !it.ticked) else it }
    }

    /** A row's day: an epoch day, or null to leave it unplanned. */
    fun onDay(index: Int, day: Long?) = change { items ->
        items.map { if (it.index == index) it.copy(day = day) else it }
    }

    private fun change(edit: (List<ReviewItem>) -> List<ReviewItem>) =
        _uiState.update { fitted(it.copy(items = edit(it.items), saveFailed = false, unlockNotice = null)) }

    fun onSave() {
        val state = _uiState.value
        if (state.loading || state.saving || state.savingCount == 0) return
        _uiState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val mealType = MealType.defaultTypeId(plan.observeMealTypes().first())
            var kept = 0
            var planned = 0
            var failed = false
            val saved = mutableSetOf<Int>()
            for (row in state.items.filter { it.ticked && it.fits }) {
                val item = read[row.index]
                val recipe = recipes.addManual(RecipeDraft.of(item.recipe), item.recipe.language, null, fromChat = true)
                if (recipe == null) { failed = true; continue }
                // Id 0: the one upsert did not keep it (no room). It stays in the review.
                if (recipe.id == 0L) continue
                saved += row.index
                kept++
                val day = row.day
                if (day != null && mealType != null && plan.addRecipe(recipe.id, day, mealType, null)) planned++
            }
            _uiState.update { current ->
                val left = current.items.filter { it.index !in saved }
                fitted(
                    current.copy(
                        saving = false, saveFailed = failed, items = left,
                        kept = current.kept + kept, planned = current.planned + planned,
                        finished = left.none { it.ticked } && !failed
                    )
                )
            }
        }
    }

    /** Unlock from the review (#107): a purchase makes room, which the limit and count then say. */
    fun onUnlock() {
        viewModelScope.launch {
            val outcome = entitlements.purchase()
            if (outcome.needsNotice) _uiState.update { it.copy(unlockNotice = outcome) }
        }
    }

    companion object {
        /** The key of the plan waiting in [SharedTextInbox], carried by the route. */
        const val PLAN_ARG = "key"

        /** The shared text itself, kept in saved state once taken, so the review survives process death. */
        const val PLAN_STATE = "planText"

        private const val PREVIEW_LINES = 3
    }
}
