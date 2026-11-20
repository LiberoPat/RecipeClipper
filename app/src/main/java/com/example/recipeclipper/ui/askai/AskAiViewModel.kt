package com.example.recipeclipper.ui.askai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.MealPlanRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.local.PlanChoices
import com.example.recipeclipper.data.model.AskAiPrompt
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.UseItUp
import com.example.recipeclipper.data.remote.MealPlanImport
import com.example.recipeclipper.ui.edit.PasteTarget
import com.example.recipeclipper.ui.edit.PastedTextRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Which prompt Copy and Share send (#351, #353): the cook's own words, or one of the ready ones
 * filled from the app's data. Choosing a ready one leaves the field for extra wishes.
 */
enum class AskAiKind { DESCRIBE, WHAT_CAN_I_MAKE, PLAN_MY_WEEK }

/** Why "Paste the answer" opened nothing (#372): the clipboard was empty, or held no recipe. */
enum class PasteProblem { EMPTY, NO_RECIPE }

/** A list the plan can take its recipes from (#367): its id and name as the cook sees it. */
data class PlanListChoice(val id: Long, val name: String)

/**
 * [request] is the field's text. [pantryCount] counts what is in stock, [useUpCount] the items to
 * use up first, [favouriteCount] and [cookedCount] what "Plan my week" draws on, so the ready
 * prompts can say what they will carry. [copied] is true after Copy until anything changes.
 */
data class AskAiUiState(
    val request: String = "",
    val kind: AskAiKind = AskAiKind.DESCRIBE,
    val pantryCount: Int = 0,
    val useUpCount: Int = 0,
    val favouriteCount: Int = 0,
    val cookedCount: Int = 0,
    val copied: Boolean = false,
    /** What "Paste the answer" found wrong (#372), until anything changes; null when nothing. */
    val pasteProblem: PasteProblem? = null,
    /**
     * Plan my week with choices (#392, #367), only with the `planMyWeekPlus` flag ([plan] false: none
     * of what follows is shown or used, and the plan is today's week of dinners).
     */
    val plan: Boolean = false,
    /** The cook's meal types, in their order, and which are ticked (by [MealType.key]). */
    val mealTypes: List<MealType> = emptyList(),
    val chosenMeals: Set<String> = emptySet(),
    /** The start-day strip: today and the next 13, and the one chosen (default today). */
    val days: List<Long> = emptyList(),
    val today: Long = 0,
    val startDay: Long = 0,
    val source: AskAiPrompt.PlanSource = AskAiPrompt.PlanSource.NEW,
    /** A mix: how many of the cook's recipes at most, 1 to the week's meals. */
    val mix: Int = 1,
    /** The lists holding a recipe, to take the plan's recipes from instead of all of them. */
    val planLists: List<PlanListChoice> = emptyList(),
    val listId: Long? = null,
    /** How many recipes the chosen scope offers, and how many of them the prompt names. */
    val recipeCount: Int = 0,
    val useFavourites: Boolean = true,
    val skipCooked: Boolean = true,
    /** Many meals to write out: chat apps may cut the answer off. */
    val longAnswer: Boolean = false
) {
    /** The week's meals: every chosen meal on each of [AskAiPrompt.PLAN_DAYS] days. */
    val planSlots: Int get() = AskAiPrompt.PLAN_DAYS * chosenMeals.size

    /** How many of [recipeCount] the prompt names (the most recently viewed). */
    val recipesSent: Int get() = minOf(recipeCount, AskAiPrompt.MAX_CANDIDATES)

    /** Plan my week asks the AI to name the cook's recipes (From my recipes, or a mix). */
    val namesRecipes: Boolean get() = source != AskAiPrompt.PlanSource.NEW

    /** Plan my week has everything it needs: a meal, and recipes to name where it names them. */
    val canPlan: Boolean get() = !plan || (chosenMeals.isNotEmpty() && (!namesRecipes || recipeCount > 0))

    /** "What can I make?" needs something in the pantry to make it from. */
    val canAskWhatCanIMake: Boolean get() = pantryCount > 0

    /** Copy and Share are on: the cook's own words need words; a ready prompt needs its data. */
    val canSend: Boolean
        get() = when (kind) {
            AskAiKind.DESCRIBE -> request.isNotBlank()
            AskAiKind.WHAT_CAN_I_MAKE -> canAskWhatCanIMake
            AskAiKind.PLAN_MY_WEEK -> canPlan
        }

    /** "Change this recipe" needs the cook's request. */
    val canSendChange: Boolean get() = request.isNotBlank()
}

/**
 * "Ask an AI" (#351, #353): builds the prompt to copy or share. No AI runs in the app. The
 * prompt is [AskAiPrompt]'s; the screen hands over its [AskAiPrompt.Words] (read from
 * `strings.xml`), and the Settings units and the data come from here. Everything a prompt reads
 * is collected by this ViewModel itself, so [prompt] is right whenever it is called.
 *
 * With the `planMyWeekPlus` flag (#392, #367) "Plan my week" takes choices: the meal types
 * (ticked by [MealType.key], the last choice remembered in [PlanChoices], Dinner at first), a
 * start day (today, never remembered), where the recipes come from (new, the cook's own, a mix),
 * and which of favourites and cooked-lately go in. A choice that names a deleted type or list
 * falls back, so the sheet never offers what is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AskAiViewModel @Inject constructor(
    pantry: PantryRepository,
    lists: ListRepository,
    recipes: RecipeRepository,
    preferences: AppPreferences,
    private val clock: Clock,
    // "Paste the answer" (#372) goes where a shared text would; null opens nothing.
    private val pasted: PastedTextRouter? = null,
    // Plan my week with choices (#392, #367): the cook's meal types, what was chosen last, the flag.
    mealPlan: MealPlanRepository? = null,
    private val choices: PlanChoices? = null,
    private val flags: FeatureFlags? = null
) : ViewModel() {

    private class Data(
        val pantry: List<PantryItem> = emptyList(),
        val favourites: List<String> = emptyList(),
        val cooked: List<String> = emptyList(),
        val settings: AppSettings = AppSettings(),
        val mealTypes: List<MealType> = emptyList(),
        val everyRecipe: List<AskAiPrompt.PlanCandidate> = emptyList(),
        val lists: List<RecipeList> = emptyList(),
        val listRecipes: List<AskAiPrompt.PlanCandidate> = emptyList()
    )

    /** What the cook chose in the plan part of the sheet; null where nothing was touched yet. */
    private data class PlanInput(
        val meals: Set<String>? = null,
        val startDay: Long? = null,
        val source: AskAiPrompt.PlanSource = AskAiPrompt.PlanSource.NEW,
        val mix: Int = DEFAULT_MIX,
        val listId: Long? = null,
        val useFavourites: Boolean = true,
        val skipCooked: Boolean = true
    )

    private val input = MutableStateFlow(AskAiUiState())
    private val planInput = MutableStateFlow(PlanInput())

    /** The types ticked last time: until a choice is made now ([PlanInput.meals]), and again once the sheet is closed. */
    private val remembered: List<String>? get() = choices?.planMealKeys

    /** The favourites list's recipes, by title as the list orders them (most recently added first). */
    private val favourites: Flow<List<String>> = lists.observeLists()
        .map { all -> all.firstOrNull { it.isFavorites }?.id }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else lists.observeRecipesIn(id).map { rows -> rows.map { it.title } }
        }

    /** The recipes the cook marked cooked (#116, #173), the latest first. */
    private val cooked: Flow<List<String>> = recipes.observeHistory("").map { rows ->
        rows.filter { it.lastCookedDay != null }
            .sortedByDescending { it.lastCookedDay }
            .take(AskAiPrompt.MAX_TITLES)
            .map { it.title }
    }

    /** Every recipe, as the plan can name it: most recently viewed first, never the tour's sample. */
    private val everyRecipe: Flow<List<AskAiPrompt.PlanCandidate>> =
        recipes.observeHistory("").map(AskAiPrompt::candidates)

    /** The recipes of the list the cook chose to plan from, the same way; none while none is chosen. */
    private val listRecipes: Flow<List<AskAiPrompt.PlanCandidate>> = planInput.map { it.listId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else lists.observeRecipesIn(id).map(AskAiPrompt::candidates)
        }

    private val data: StateFlow<Data> = combine(
        combine(pantry.observeItems(), favourites, cooked, preferences.settings) { pantry, favourites, cooked, settings ->
            Data(pantry, favourites, cooked, settings)
        },
        mealPlan?.observeMealTypes() ?: flowOf(emptyList()),
        everyRecipe,
        lists.observeLists(),
        listRecipes
    ) { base, types, every, allLists, inList ->
        Data(base.pantry, base.favourites, base.cooked, base.settings, types, every, allLists, inList)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Data())

    val uiState: StateFlow<AskAiUiState> = combine(input, planInput, data) { input, plan, data ->
        view(input, plan, data)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskAiUiState())

    /** [input] with everything the ready prompts say they carry, and the plan's choices resolved against [data]. */
    private fun view(input: AskAiUiState, plan: PlanInput, data: Data): AskAiUiState {
        val today = today()
        val inStock = data.pantry.filter { it.inStock && it.name.isNotBlank() }
        val base = input.copy(
            pantryCount = inStock.size,
            useUpCount = inStock.count { UseItUp.soon(it, today) },
            favouriteCount = data.favourites.size,
            cookedCount = data.cooked.size
        )
        if (!plusOn()) return base
        val chosen = MealType.chosenKeys(data.mealTypes, plan.meals ?: remembered)
        val planLists = data.lists.filter { it.recipeCount > 0 }.map { PlanListChoice(it.id, it.name) }
        val listId = plan.listId?.takeIf { id -> planLists.any { it.id == id } }
        val count = if (listId == null) data.everyRecipe.size else data.listRecipes.size
        val slots = AskAiPrompt.PLAN_DAYS * chosen.size
        val mix = plan.mix.coerceIn(1, maxOf(1, slots))
        return base.copy(
            plan = true,
            mealTypes = data.mealTypes,
            chosenMeals = chosen,
            days = (0 until PlanDays.SHEET_DAYS).map { today + it },
            today = today,
            startDay = plan.startDay?.coerceAtLeast(today) ?: today,
            source = plan.source,
            mix = mix,
            planLists = planLists,
            listId = listId,
            recipeCount = count,
            useFavourites = plan.useFavourites,
            skipCooked = plan.skipCooked,
            longAnswer = AskAiPrompt.recipesToWrite(plan.source, slots, mix) > AskAiPrompt.LONG_ANSWER
        )
    }

    private fun plusOn(): Boolean = flags?.isOn(Flag.PLAN_MY_WEEK_PLUS) == true

    fun onRequestChange(text: String) = input.update { it.copy(request = text, copied = false, pasteProblem = null) }

    /** The prompt to send: the cook's own words, or a ready one. */
    fun onKind(kind: AskAiKind) = input.update { it.copy(kind = kind, copied = false, pasteProblem = null) }

    /** The prompt was copied: the button says so until something changes. */
    fun onCopied() = input.update { it.copy(copied = true) }

    /**
     * Ticks or unticks the meal type [key] ([MealType.key]) for Plan my week (#392). The choice is
     * remembered at once, in the order of the cook's types; no more than [MAX_MEALS] can be ticked.
     * Unticking the last one is allowed (the sheet then asks for a meal).
     */
    fun onMeal(key: String) {
        val types = data.value.mealTypes
        val now = MealType.chosenKeys(types, planInput.value.meals ?: remembered)
        val next = when {
            key in now -> now - key
            now.size >= MAX_MEALS -> now
            else -> now + key
        }
        planInput.update { it.copy(meals = next) }
        input.update { it.copy(copied = false, pasteProblem = null) }
        choices?.planMealKeys = types.map { it.key }.filter { it in next }
    }

    /** The first day of the plan: one of the strip's days, today by default; never remembered. */
    fun onStartDay(day: Long) {
        planInput.update { it.copy(startDay = day) }
        input.update { it.copy(copied = false, pasteProblem = null) }
    }

    /** Where the plan's recipes come from: new ones, the cook's own, or a mix (#367). */
    fun onSource(source: AskAiPrompt.PlanSource) {
        planInput.update { it.copy(source = source) }
        input.update { it.copy(copied = false, pasteProblem = null) }
    }

    /** A mix: how many of the cook's recipes at most; the stepper keeps it between 1 and the week's meals. */
    fun onMix(count: Int) {
        planInput.update { it.copy(mix = count.coerceAtLeast(1)) }
        input.update { it.copy(copied = false, pasteProblem = null) }
    }

    /** The list the plan's recipes come from; null is all of them. */
    fun onList(id: Long?) {
        planInput.update { it.copy(listId = id) }
        input.update { it.copy(copied = false, pasteProblem = null) }
    }

    fun onUseFavourites(on: Boolean) {
        planInput.update { it.copy(useFavourites = on) }
        input.update { it.copy(copied = false, pasteProblem = null) }
    }

    fun onSkipCooked(on: Boolean) {
        planInput.update { it.copy(skipCooked = on) }
        input.update { it.copy(copied = false, pasteProblem = null) }
    }

    /**
     * "Paste the answer" (#372): [clip] is what the view layer read from the clipboard, once, on
     * the tap (null: nothing there). It goes exactly where the same text shared would: the
     * editor for a recipe, the review for a week. [onTarget] gets where; with nothing to open
     * the sheet says why ([PasteProblem]). Nothing is saved here.
     */
    fun onPasteAnswer(clip: String?, onTarget: (PasteTarget) -> Unit) {
        if (clip.isNullOrBlank()) {
            input.update { it.copy(pasteProblem = PasteProblem.EMPTY) }
            return
        }
        viewModelScope.launch {
            val target = pasted?.open(clip)
            if (target == null) input.update { it.copy(pasteProblem = PasteProblem.NO_RECIPE) } else onTarget(target)
        }
    }

    /** The sheet closed: the next one opens empty (the meals come back as last chosen). */
    fun onReset() {
        input.update { AskAiUiState() }
        planInput.update { PlanInput() }
    }

    /** The prompt for the chosen kind, or null when it can't be sent yet ([AskAiUiState.canSend]). */
    fun prompt(words: AskAiPrompt.Words): String? {
        val state = view(input.value, planInput.value, data.value)
        val data = data.value
        val setup = setup(words, data)
        val request = state.request
        val today = today()
        return when (state.kind) {
            AskAiKind.DESCRIBE -> request.takeIf { it.isNotBlank() }?.let { AskAiPrompt.describe(it, setup) }
            AskAiKind.WHAT_CAN_I_MAKE -> data.pantry.takeIf { items -> items.any { it.inStock } }
                ?.let { AskAiPrompt.whatCanIMake(it, today, request, setup) }
            AskAiKind.PLAN_MY_WEEK ->
                if (!state.plan) {
                    AskAiPrompt.planMyWeek(
                        (0 until AskAiPrompt.PLAN_DAYS).map { today + it }, data.favourites, data.cooked, request, setup
                    )
                } else if (!state.canSend) {
                    null
                } else {
                    planPrompt(state, data, request, setup)
                }
        }
    }

    /** Plan my week with choices: the meals in the cook's order, the recipes by source, the toggled lists. */
    private fun planPrompt(state: AskAiUiState, data: Data, request: String, setup: AskAiPrompt.Setup): String {
        val names = state.mealTypes.filter { it.key in state.chosenMeals }.map { it.name }
        val own = state.listId?.let { data.listRecipes } ?: data.everyRecipe
        val choice = AskAiPrompt.PlanChoice(
            days = (0 until AskAiPrompt.PLAN_DAYS).map { state.startDay + it },
            meals = names,
            source = state.source,
            candidates = if (state.namesRecipes) own else emptyList(),
            mix = state.mix
        )
        // Ideas and "don't repeat" make no sense when the week is the cook's own recipes only.
        val ideas = state.source != AskAiPrompt.PlanSource.MINE
        return AskAiPrompt.planMyWeekPlus(
            choice,
            favourites = if (ideas && state.useFavourites) data.favourites else emptyList(),
            cooked = if (ideas && state.skipCooked) data.cooked else emptyList(),
            request = request,
            setup = setup
        )
    }

    /** "Change this recipe" (#353): the request and [recipe], or null without a request. */
    fun changePrompt(recipe: Recipe, words: AskAiPrompt.Words): String? =
        input.value.request.takeIf { it.isNotBlank() }
            ?.let { AskAiPrompt.changeRecipe(recipe, it, setup(words, data.value)) }

    private fun setup(words: AskAiPrompt.Words, data: Data) =
        AskAiPrompt.Setup(words, data.settings.unitSystem, data.settings.temperatureUnit)

    private fun today(): Long = PlanDays.today(clock.now())

    companion object {
        /** A mix starts with this many of the cook's recipes (kept to the week's meals). */
        const val DEFAULT_MIX = 3

        /** At most this many meal types can be ticked: the reader keeps [MealPlanImport.MAX_RECIPES] recipes. */
        val MAX_MEALS = MealPlanImport.MAX_RECIPES / AskAiPrompt.PLAN_DAYS
    }
}
