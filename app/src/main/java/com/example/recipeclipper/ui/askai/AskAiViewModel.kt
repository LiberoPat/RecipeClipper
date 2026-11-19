package com.example.recipeclipper.ui.askai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.ListRepository
import com.example.recipeclipper.data.PantryRepository
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.model.AskAiPrompt
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.UseItUp
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
    val pasteProblem: PasteProblem? = null
) {
    /** "What can I make?" needs something in the pantry to make it from. */
    val canAskWhatCanIMake: Boolean get() = pantryCount > 0

    /** Copy and Share are on: the cook's own words need words; a ready prompt needs its data. */
    val canSend: Boolean
        get() = when (kind) {
            AskAiKind.DESCRIBE -> request.isNotBlank()
            AskAiKind.WHAT_CAN_I_MAKE -> canAskWhatCanIMake
            AskAiKind.PLAN_MY_WEEK -> true
        }

    /** "Change this recipe" needs the cook's request. */
    val canSendChange: Boolean get() = request.isNotBlank()
}

/**
 * "Ask an AI" (#351, #353): builds the prompt to copy or share. No AI runs in the app. The
 * prompt is [AskAiPrompt]'s; the screen hands over its [AskAiPrompt.Words] (read from
 * `strings.xml`), and the Settings units and the data come from here. Everything a prompt reads
 * is collected by this ViewModel itself, so [prompt] is right whenever it is called.
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
    private val pasted: PastedTextRouter? = null
) : ViewModel() {

    private class Data(
        val pantry: List<PantryItem> = emptyList(),
        val favourites: List<String> = emptyList(),
        val cooked: List<String> = emptyList(),
        val settings: AppSettings = AppSettings()
    )

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

    private val data: StateFlow<Data> = combine(
        pantry.observeItems(), favourites, cooked, preferences.settings, ::Data
    ).stateIn(viewModelScope, SharingStarted.Eagerly, Data())

    private val input = MutableStateFlow(AskAiUiState())

    val uiState: StateFlow<AskAiUiState> = combine(input, data) { input, data ->
        val today = today()
        val inStock = data.pantry.filter { it.inStock && it.name.isNotBlank() }
        input.copy(
            pantryCount = inStock.size,
            useUpCount = inStock.count { UseItUp.soon(it, today) },
            favouriteCount = data.favourites.size,
            cookedCount = data.cooked.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskAiUiState())

    fun onRequestChange(text: String) = input.update { it.copy(request = text, copied = false, pasteProblem = null) }

    /** The prompt to send: the cook's own words, or a ready one. */
    fun onKind(kind: AskAiKind) = input.update { it.copy(kind = kind, copied = false, pasteProblem = null) }

    /** The prompt was copied: the button says so until something changes. */
    fun onCopied() = input.update { it.copy(copied = true) }

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

    /** The sheet closed: the next one opens empty. */
    fun onReset() = input.update { AskAiUiState() }

    /** The prompt for the chosen kind, or null when it can't be sent yet ([AskAiUiState.canSend]). */
    fun prompt(words: AskAiPrompt.Words): String? {
        val state = input.value
        val data = data.value
        val setup = setup(words, data)
        val request = state.request
        val today = today()
        return when (state.kind) {
            AskAiKind.DESCRIBE -> request.takeIf { it.isNotBlank() }?.let { AskAiPrompt.describe(it, setup) }
            AskAiKind.WHAT_CAN_I_MAKE -> data.pantry.takeIf { items -> items.any { it.inStock } }
                ?.let { AskAiPrompt.whatCanIMake(it, today, request, setup) }
            AskAiKind.PLAN_MY_WEEK -> AskAiPrompt.planMyWeek(
                (0 until AskAiPrompt.PLAN_DAYS).map { today + it }, data.favourites, data.cooked, request, setup
            )
        }
    }

    /** "Change this recipe" (#353): the request and [recipe], or null without a request. */
    fun changePrompt(recipe: Recipe, words: AskAiPrompt.Words): String? =
        input.value.request.takeIf { it.isNotBlank() }
            ?.let { AskAiPrompt.changeRecipe(recipe, it, setup(words, data.value)) }

    private fun setup(words: AskAiPrompt.Words, data: Data) =
        AskAiPrompt.Setup(words, data.settings.unitSystem, data.settings.temperatureUnit)

    private fun today(): Long = PlanDays.today(clock.now())
}
