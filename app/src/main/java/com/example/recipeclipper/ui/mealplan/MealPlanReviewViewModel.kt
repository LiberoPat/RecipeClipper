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
import com.example.recipeclipper.data.model.SavedLink
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
import com.example.recipeclipper.data.model.CutOffMessage
import javax.inject.Inject

/**
 * One recipe of the review (#352). [day] is the day the cook has chosen to plan it on (an epoch
 * day), null to leave it unplanned; [passed] says the answer's own day is already behind us, so
 * the row asks for another. [fits] is false for a kept recipe the free library has no room for.
 * With Plan my week's choices (#392, #367): [mealTypeId] is the meal type it is planned under, and
 * [existingId] is set for a recipe the answer named by its link that is already in the library:
 * Save plans that recipe, never a duplicate.
 */
data class ReviewItem(
    val index: Int,
    val name: String,
    /** The first ingredients, to tell recipes apart at a glance. */
    val preview: String,
    val day: Long?,
    val passed: Boolean,
    val ticked: Boolean,
    val fits: Boolean = true,
    val mealTypeId: Long? = null,
    val existingId: Long? = null
) {
    /** The recipe is one the cook has: Save only plans it. */
    val existing: Boolean get() = existingId != null

    /** Save does something with this row: a new recipe that fits, or an existing one with a day. */
    val saves: Boolean get() = ticked && if (existing) day != null else fits
}

/**
 * [room] is how many more recipes the free library holds, null without a limit. [kept] counts the
 * recipes saved from this review so far, [planned] those of them put on a day.
 */
data class MealPlanReviewUiState(
    val loading: Boolean = true,
    val items: List<ReviewItem> = emptyList(),
    /** The answer stopped mid-recipe: only the whole recipes before it are listed. */
    val cutOff: Boolean = false,
    /** The message for the AI was copied: the note says to paste it into the chat. */
    val copied: Boolean = false,
    /** The days the pickers offer: today and the next 13. */
    val days: List<Long> = emptyList(),
    /** Plan my week's choices are on (#392): each row shows its meal, to change like its day. */
    val meals: Boolean = false,
    /** The cook's meal types, for each row's meal picker. */
    val mealTypes: List<MealType> = emptyList(),
    /** How many recipes the answer named by a link that matches nothing saved: left out, never made up (#367). */
    val notFound: Int = 0,
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

    /** What Save does: the new recipes ticked that fit, and the ticked existing ones with a day. */
    val savingCount: Int get() = items.count { it.saves }

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
    // Plan my week's choices (#392, #367): null reads the answer as before, dinners and new recipes.
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

    private val meals: Boolean = flags?.isOn(Flag.PLAN_MY_WEEK_PLUS) == true

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
            val types = if (meals) plan.observeMealTypes().first() else emptyList()
            val saved = if (meals && read.any { it.namesSavedRecipe }) recipes.observeHistory("").first() else emptyList()
            var notFound = 0
            val rows = mutableListOf<ReviewItem>()
            read.forEachIndexed { i, item ->
                when {
                    !item.namesSavedRecipe -> rows += rowOf(i, item, types)
                    // A recipe named by its link is the library's: matched, or left out and counted (#367).
                    // With the choices off nothing could save it, so it is left out as it always was.
                    !meals -> Unit
                    else -> SavedLink.find(item.link.orEmpty(), saved)
                        ?.let { rows += rowOf(i, item, types, it.id, it.title) } ?: run { notFound++ }
                }
            }
            _uiState.update { state ->
                fitted(
                    state.copy(
                        loading = false, cutOff = found?.cutOff == true, items = rows,
                        meals = meals, mealTypes = types, notFound = notFound
                    )
                )
            }
        }
        viewModelScope.launch {
            combine(policy.limits, recipes.observeCount()) { limit, count -> roomOf(limit, count) }
                .collect { room -> _uiState.update { fitted(it.copy(room = room)) } }
        }
    }

    private fun rowOf(
        index: Int, item: MealPlanImport.Item, types: List<MealType>, existingId: Long? = null, existingTitle: String? = null
    ): ReviewItem {
        val named = item.day
        val passed = named != null && named < today
        return ReviewItem(
            index = index,
            name = existingTitle ?: item.recipe.name,
            preview = item.recipe.ingredients.take(PREVIEW_LINES).joinToString(", "),
            day = named?.takeIf { !passed },
            passed = passed,
            ticked = true,
            // The meal the prompt listed, by the name it used; else Dinner (never guessed from the recipe).
            mealTypeId = if (meals) MealType.idForMeal(item.meal, types) else null,
            existingId = existingId
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
            // A recipe the cook already has takes no room.
            val fits = row.existing || !row.ticked || remaining == null || remaining > 0
            if (!row.existing && row.ticked && remaining != null && remaining > 0) left = remaining - 1
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

    /** A row's meal type (#392): the one it is planned under. */
    fun onMeal(index: Int, typeId: Long) = change { items ->
        items.map { if (it.index == index) it.copy(mealTypeId = typeId) else it }
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
            for (row in state.items.filter { it.ticked && (it.existing || it.fits) }) {
                val type = row.mealTypeId ?: mealType
                val existing = row.existingId
                if (existing != null) {
                    // Already in the library (#367): planned on its day and meal, never saved again.
                    val day = row.day
                    if (day == null) { saved += row.index; continue }
                    if (type != null && plan.addRecipe(existing, day, type, null)) {
                        saved += row.index
                        kept++
                        planned++
                    } else {
                        failed = true
                    }
                    continue
                }
                val item = read[row.index]
                // Tips (#432) stay apart only with recipeTips on; off, they are the last steps, as before.
                val asRead = if (flags?.isOn(Flag.RECIPE_TIPS) == true) item.recipe else item.recipe.withTipsAsSteps()
                val recipe = recipes.addManual(RecipeDraft.of(asRead), item.recipe.language, null, fromChat = true)
                if (recipe == null) { failed = true; continue }
                // Id 0: the one upsert did not keep it (no room). It stays in the review.
                if (recipe.id == 0L) continue
                saved += row.index
                kept++
                val day = row.day
                if (day != null && type != null && plan.addRecipe(recipe.id, day, type, null)) planned++
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

    /**
     * The message that asks the AI for the rest of a cut-off week (#352): the days the answer's
     * whole recipes leave out ([CutOffMessage]), in the UI language's [words]. The screen copies it.
     */
    fun followUp(words: CutOffMessage.Words): String =
        if (meals) CutOffMessage.forPlanMeals(read.map { it.day to it.meal }, words) else CutOffMessage.forPlan(read.map { it.day }, words)

    /** The screen copied [followUp]: the note says to paste it into the chat. */
    fun onCopied() = _uiState.update { it.copy(copied = true) }

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
