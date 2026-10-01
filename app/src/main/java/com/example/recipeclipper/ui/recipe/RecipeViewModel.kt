package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.AppInfo
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.Entitlements
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.ShortStepRepository
import com.example.recipeclipper.data.TimerAlarmScheduler
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeShareText
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.data.needsNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One recipe screen. It is opened either by id (from history, home or a list) or by URL
 * (the share target); the navigation arguments arrive through [savedStateHandle].
 *
 * The single owner of [uiState] (#169, #234): it loads, then hands each job to a collaborator
 * and writes what comes back. [RecipeRenderer] turns the recipe into what the screen shows and
 * [RecipeDisplay] keeps it rendered under the cook's settings and servings; [FailedLoad] says
 * where a failed load goes; [CookController] runs cook mode ([CookSession]), its timers' alarms
 * and tick loop; [NotesAndTicks] writes ticks and the note; [OrderedWrites] is the one queue for
 * cook progress and servings; and [ChefMode] brings the on-device model's short steps and
 * decisions.
 */
@HiltViewModel
class RecipeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository,
    private val unitPreferences: AppPreferences,
    clock: Clock,
    private val connectivity: Connectivity,
    appInfo: AppInfo,
    alarms: TimerAlarmScheduler,
    // Chef mode (#100) and the free tier (#107). Optional, so a test that doesn't care leaves
    // them out (chef mode off, nothing to unlock); pass them by name.
    shortSteps: ShortStepRepository? = null,
    featureFlags: FeatureFlags? = null,
    private val entitlements: Entitlements = Entitlements.Unavailable,
    // The on-device model's decisions (#104: count brackets; junk after an ingredient, #174);
    // none without it.
    decisionRepository: DecisionRepository? = null
) : ViewModel() {

    private val recipeId: Long? = savedStateHandle.get<Long>(RECIPE_ID_ARG)?.takeIf { it > 0 }
    private val shareUrl: String? = savedStateHandle.get<String>(URL_ARG)?.takeIf { it.isNotBlank() }

    // Opened from a timer notification: start cook mode once the recipe has loaded.
    private var openInCookMode: Boolean = savedStateHandle.get<Boolean>(COOK_ARG) == true

    // Opened from the Week (#49): show the planned servings rather than the saved choice. For
    // this visit only; it isn't saved unless the cook changes the servings here.
    private var plannedServings: Int? = savedStateHandle.get<Int>(SERVINGS_ARG)?.takeIf { it > 0 }

    // Seeded synchronously so the first render already uses the user's units; kept current
    // afterwards by collecting [AppPreferences.settings] in [init].
    private val _uiState = unitPreferences.current.let {
        MutableStateFlow(
            RecipeUiState(
                unitSystem = it.unitSystem,
                convertLiquids = it.convertLiquids,
                temperatureUnit = it.temperatureUnit,
                darkWhileCooking = it.darkWhileCooking,
                amountsInSteps = it.amountsInSteps
            )
        )
    }
    val uiState: StateFlow<RecipeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var reconnectJob: Job? = null

    // The `reddit` flag (#11), read at each load as the source reads it at each fetch; on when
    // a test passes no flags, as it is by default.
    private val failedLoad = FailedLoad(shareUrl, appInfo, redditOn = { featureFlags?.isOn(Flag.REDDIT) != false })

    // Ticks as they change, and the note once typing pauses or the screen is left.
    private val notesAndTicks = NotesAndTicks(viewModelScope, repository, NOTES_SAVE_DELAY_MS)

    // Cook progress and servings writes, run one at a time in the order they were made.
    private val writes = OrderedWrites(viewModelScope)

    // Cook mode's steps and timers, their alarms and tick loop, and saving the cook's place.
    private val cookMode = CookController(
        clock = clock,
        alarms = alarms,
        scope = viewModelScope,
        writes = writes,
        repository = repository,
        content = { _uiState.value.content as? RecipeContent.Success },
        cook = { _uiState.value.cook },
        onCook = { cook -> _uiState.update { it.copy(cook = cook) } }
    )

    // Chef mode (#100) and the model's decisions (#104, #174), two of the renderer's inputs.
    // Declared before init, which starts the collectors that feed it.
    private val chef: ChefMode = ChefMode(
        scope = viewModelScope,
        shortStepRepository = shortSteps,
        featureFlags = featureFlags,
        decisionRepository = decisionRepository,
        keptRecipe = { loadedRecipe()?.takeUnless { _uiState.value.notKept } },
        onShortSteps = { _uiState.update(display::withShortSteps) },
        onDecisions = { _uiState.update(display::rerender) }
    )

    // The settings, servings and short steps the recipe is rendered with.
    private val display: RecipeDisplay = RecipeDisplay(shortSteps = { chef.shortSteps }, decisions = { chef.decisions })

    init {
        chef.observeDecisions()
        // Settings can change a default while this screen is alive underneath it; this keeps
        // the open recipe in step instead of showing the units it was opened with (#24).
        viewModelScope.launch { unitPreferences.settings.collect(::applySettings) }
        chef.observeFlag()
        load()
    }

    // --- Loading the recipe ---

    fun onRetry() = load()

    /**
     * The Unlock prompt on a recipe that wasn't kept (#107): buys the unlock, then saves the
     * recipe on screen. A pending or failed purchase leaves it shown and unsaved, and says so.
     */
    fun onUnlock() {
        viewModelScope.launch {
            val outcome = entitlements.purchase()
            if (outcome != PurchaseOutcome.UNLOCKED) {
                if (outcome.needsNotice) _uiState.update { it.copy(unlockNotice = outcome) }
                return@launch
            }
            val shown = loadedRecipe() ?: return@launch
            val saved = repository.keep(shown)
            if (saved is ParseResult.Success && saved.kept) {
                _uiState.update { state ->
                    val content = state.content as? RecipeContent.Success
                    state.copy(notKept = false, content = content?.copy(recipe = saved.recipe) ?: state.content)
                }
                chef.start() // now it has a row to keep short steps against
            }
        }
    }

    fun onUnlockNoticeShown() = _uiState.update { it.copy(unlockNotice = null) }

    private fun load() {
        loadJob?.cancel()
        reconnectJob?.cancel()
        _uiState.update {
            it.copy(
                content = RecipeContent.Loading, reportSiteUrl = null, clipUrl = null, photoPost = null,
                clipBlockedPost = null, humanCheckPage = null, asWrittenSteps = emptySet()
            )
        }
        loadJob = viewModelScope.launch {
            val result = when {
                recipeId != null -> repository.open(recipeId)
                    ?.let { ParseResult.Success(it) }
                    ?: ParseResult.Error(ParseError.NotSaved)
                shareUrl != null -> repository.importFromUrl(shareUrl)
                else -> ParseResult.Error(ParseError.NothingToShow)
            }
            _uiState.update { state ->
                when (result) {
                    is ParseResult.Success -> state.copy(
                        content = display.content(result.recipe.withPlannedServings(), state),
                        checkedIngredients = result.recipe.checkedIngredients,
                        notes = result.recipe.notes.orEmpty(),
                        notKept = !result.kept
                    )
                    is ParseResult.Error -> failedLoad.shown(state, result.error)
                }
            }
            if (result is ParseResult.Success) {
                cookMode.restore(result.recipe)
                chef.start()
                askModel()
                if (openInCookMode) {
                    openInCookMode = false
                    onCookStart()
                }
            }
            if (result is ParseResult.Error && result.error.reloadsOnReconnect) reloadOnReconnect()
        }
    }

    /**
     * While an Offline or FetchFailed error is on screen, waits for the connection to go from
     * offline to online and then loads again, once. Already online is not a transition: a
     * FetchFailed while connected waits for a drop and a return, never reloading in a loop.
     * Cancelled by any new load, including "Try again".
     */
    private fun reloadOnReconnect() {
        reconnectJob = viewModelScope.launch {
            connectivity.online.dropWhile { it }.first { it }
            load()
        }
    }

    // --- Reading view ---

    fun onIngredientChecked(index: Int, checked: Boolean) {
        // A heading has no box to tick.
        if ((_uiState.value.content as? RecipeContent.Success)?.isHeading(index) == true) return
        val next = _uiState.value.checkedIngredients.let { if (checked) it + index else it - index }
        _uiState.update { it.copy(checkedIngredients = next) }
        // Saved as they change, so closing the app mid-cook doesn't lose the ticks.
        val id = loadedRecipe()?.id ?: return
        notesAndTicks.ticked(id, next)
    }

    /**
     * The user's note, edited in place. The screen shows every keystroke at once; the write
     * waits until typing pauses for [NOTES_SAVE_DELAY_MS], so a sentence is one write rather
     * than one per letter. Leaving the screen before then still saves it (see [onCleared]).
     */
    fun onNotesChange(text: String) {
        _uiState.update { it.copy(notes = text) }
        val id = loadedRecipe()?.id ?: return
        notesAndTicks.noteChanged(id, text)
    }

    /**
     * The recipe as currently on screen — scaled servings, converted units — formatted for
     * sharing outside the app. Null when there's nothing loaded yet. Building and firing the
     * share Intent is the screen's job: this only returns text. The screen passes [labels]
     * read from its resources, so the words around the recipe follow the phone's language.
     */
    fun shareText(labels: RecipeShareText.Labels = RecipeShareText.Labels.ENGLISH): String? {
        val content = _uiState.value.content as? RecipeContent.Success ?: return null
        return RecipeShareText.format(
            recipe = content.recipe,
            servings = content.servings,
            ingredients = content.ingredients,
            instructions = content.instructions,
            labels = labels
        )
    }

    /**
     * Deletes the recipe outright — list memberships included — then marks [RecipeUiState.deleted]
     * so the screen can navigate back. No undo here: by the time a snackbar would show, the
     * screen showing this recipe is already gone.
     */
    fun onDelete() {
        val id = loadedRecipe()?.id ?: return
        // A deleted recipe's running timers must not ring later.
        cookMode.stopTimers(id)
        viewModelScope.launch {
            // No undo here: the confirmation said the photos go with it (#116).
            repository.delete(id)?.let { repository.forget(it) }
            _uiState.update { it.copy(deleted = true) }
        }
    }

    /**
     * "Update from source" (#29), confirmed on screen first: replaces the user's version with
     * the site's. The recipe stays on screen meanwhile; on success it is shown afresh, on
     * failure it is kept as it was and [RecipeUiState.updateError] says why.
     */
    fun onUpdateFromSource() {
        val recipe = loadedRecipe() ?: return
        if (!recipe.canUpdateFromSource || _uiState.value.updatingFromSource) return
        _uiState.update { it.copy(updatingFromSource = true, updateError = null) }
        viewModelScope.launch {
            val result = repository.updateFromSource(recipe.id)
            if (result is ParseResult.Success) {
                // Steps may have changed: drop this screen's alarms; restoring the cook
                // reschedules whatever the saved progress still holds.
                cookMode.stopTimers(recipe.id)
                _uiState.update { state ->
                    state.copy(
                        content = display.content(result.recipe, state),
                        checkedIngredients = result.recipe.checkedIngredients,
                        updatingFromSource = false,
                        asWrittenSteps = emptySet()
                    )
                }
                cookMode.restore(result.recipe)
                chef.start()
                askModel()
            } else {
                val error = (result as ParseResult.Error).error
                _uiState.update { it.copy(updatingFromSource = false, updateError = error) }
            }
        }
    }

    /** The screen has shown [RecipeUiState.updateError]. */
    fun onUpdateErrorShown() = _uiState.update { it.copy(updateError = null) }

    fun onServingsChange(target: Int) {
        _uiState.update { display.withServings(it, target) }
        val content = _uiState.value.content as? RecipeContent.Success ?: return
        val scale = content.servings ?: return
        val saved = display.savedServings(scale)
        writes.enqueue { repository.setServingsTarget(content.recipe.id, saved) }
    }

    /**
     * The units dropdown on this screen. It is a global default, so it writes through; the
     * state updates at once too, rather than waiting for [AppPreferences.settings] to echo it.
     * The other preferences are set only in Settings and reach this screen through
     * [applySettings].
     */
    fun onUnitSystemChange(system: UnitSystem) {
        unitPreferences.unitSystem = system
        _uiState.update { display.withUnitSystem(it, system) }
    }

    /** A change to the global defaults arriving while the recipe is open ([RecipeDisplay.withSettings]). */
    private fun applySettings(settings: AppSettings) {
        chef.onSetting(settings.chefMode)
        _uiState.update { display.withSettings(it, settings) }
    }

    // The model's questions about the loaded recipe: count brackets (#104) and junk (#174).
    private fun askModel() {
        val content = _uiState.value.content as? RecipeContent.Success ?: return
        chef.askCountBrackets(content)
        chef.askJunk(content)
    }

    /** Chef mode: shows step [index] as written, or short again. */
    fun onStepAsWrittenToggle(index: Int) {
        _uiState.update { state ->
            val shown = state.asWrittenSteps
            state.copy(asWrittenSteps = if (index in shown) shown - index else shown + index)
        }
    }

    // --- Cook mode ([CookController]) ---

    fun onCookStart() = cookMode.start()

    fun onCookExit() = cookMode.exit()

    fun onIngredientsToggle() = cookMode.toggleIngredients()

    /** Tapping a step makes it current. Only [onStepDone] ever advances or marks progress. */
    fun onStepSelected(index: Int) = cookMode.select(index)

    fun onStepDone() {
        _uiState.update { state ->
            val content = state.content as? RecipeContent.Success ?: return@update state
            val done = cookMode.done(state.cook, content, state.checkedIngredients)
            if (done.finished) {
                // The end of cooking (#147): what was ticked goes to the pantry's use-up sheet.
                state.copy(cook = done.cook, cookFinished = done.finishedCook)
            } else {
                state.copy(cook = done.cook)
            }
        }
        cookMode.save()
    }

    /** The screen has handed [RecipeUiState.cookFinished] on. */
    fun onCookFinishedHandled() = _uiState.update { it.copy(cookFinished = null) }

    // --- Step timers. Several can run at once, since steps overlap. ---

    fun onTimerStart(step: Int) = cookMode.startTimer(step)

    fun onTimerToggle(step: Int) = cookMode.toggleTimer(step)

    fun onTimerReset(step: Int) = cookMode.resetTimer(step)

    fun onTimerAlerted(step: Int) = cookMode.alerted(step)

    override fun onCleared() {
        cookMode.close()
        // viewModelScope is cancelled by now: queued cook progress and servings still land, in
        // order, and so does a note typed just before leaving.
        writes.finishAfterClose()
        notesAndTicks.flushAfterClose(loadedRecipe()?.id)
    }

    // The recipe on screen; null while loading or showing an error.
    private fun loadedRecipe(): Recipe? = (_uiState.value.content as? RecipeContent.Success)?.recipe

    /** The planned servings in place of the saved ones, the first time the recipe loads. */
    private fun Recipe.withPlannedServings(): Recipe {
        val planned = plannedServings ?: return this
        plannedServings = null
        return copy(servingsTarget = planned)
    }

    companion object {
        const val RECIPE_ID_ARG = "recipeId"
        const val URL_ARG = "url"

        /** True when opened from a timer notification: the recipe opens in cook mode. */
        const val COOK_ARG = "cook"

        /** Opened from the Week (#49): the planned servings, 0 for none. */
        const val SERVINGS_ARG = "servings"

        /** How long typing must pause before the note is written. */
        const val NOTES_SAVE_DELAY_MS = 500L
    }
}
