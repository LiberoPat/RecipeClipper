package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.AppInfo
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.Entitlements
import com.example.recipeclipper.data.PhotoPost
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
import com.example.recipeclipper.data.model.SiteReportLink
import com.example.recipeclipper.data.model.StepAlarm
import com.example.recipeclipper.data.model.UnitSystem
import com.example.recipeclipper.data.needsNotice
import com.example.recipeclipper.data.remote.RedditUrls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One recipe screen. It is opened either by id (from history, home or a list) or by URL
 * (the share target); the navigation arguments arrive through [savedStateHandle].
 *
 * The single owner of [uiState] (#169): it loads, then hands each job to a collaborator and
 * writes what comes back. [RecipeRenderer] turns the recipe into what the screen shows,
 * [CookSession] runs cook mode and its timers, and [ChefMode] brings the on-device model's
 * short steps and decisions. This keeps the tick loop, the alarm calls and the serialized
 * writes (ticks, notes, cook progress, servings).
 */
@HiltViewModel
class RecipeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository,
    private val unitPreferences: AppPreferences,
    clock: Clock,
    private val connectivity: Connectivity,
    private val appInfo: AppInfo,
    private val alarms: TimerAlarmScheduler,
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

    // The `reddit` flag (#11), read at each load as the source reads it at each fetch; on when
    // a test passes no flags, as it is by default.
    private val redditOn: () -> Boolean = { featureFlags?.isOn(Flag.REDDIT) != false }

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

    // The note as typed but not yet written, and the debounced write that will save it.
    private var pendingNotes: String? = null
    private var notesJob: Job? = null

    // Cook mode's steps and timers; this keeps only the tick loop and the alarm calls.
    private val cookSession = CookSession(clock)
    private var tickJob: Job? = null

    // Cook progress and servings writes, run one at a time in the order they were made, so two
    // quick taps can never land out of order and leave the older state saved. A plain queue
    // rather than a Channel: a Channel drops an element handed to a receiver that is cancelled
    // before it runs, which is exactly the tap made just before leaving the screen.
    private val pendingWrites = ArrayDeque<suspend () -> Unit>()
    private var writer: Job? = null

    // Chef mode (#100) and the model's decisions (#104, #174), two of the renderer's inputs.
    // Declared before init, which starts the collectors that feed it.
    private val chef = ChefMode(
        scope = viewModelScope,
        shortStepRepository = shortSteps,
        featureFlags = featureFlags,
        decisionRepository = decisionRepository,
        keptRecipe = { loadedRecipe()?.takeUnless { _uiState.value.notKept } },
        onShortSteps = { _uiState.update(::withShortSteps) },
        onDecisions = { _uiState.update(::rerender) }
    )

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
                clipBlockedPost = null, asWrittenSteps = emptySet()
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
            // Reddit wouldn't let the app read the post (#213): no error screen; the post opens
            // in "Clip it yourself" instead, where Reddit does let it in.
            val clipInstead = shareUrl?.takeIf {
                result is ParseResult.Error && RedditUrls.clipsWhenBlocked(it, result.error, redditOn())
            }
            _uiState.update { state ->
                when (result) {
                    is ParseResult.Success -> state.copy(
                        content = RecipeRenderer.content(result.recipe.withPlannedServings(), state.renderSettings()),
                        checkedIngredients = result.recipe.checkedIngredients,
                        notes = result.recipe.notes.orEmpty(),
                        notKept = !result.kept
                    )
                    is ParseResult.Error -> if (clipInstead != null) {
                        state.copy(clipBlockedPost = clipInstead)
                    } else {
                        state.copy(
                            content = RecipeContent.Error(result.error),
                            reportSiteUrl = reportSiteUrl(result.error),
                            clipUrl = shareUrl.takeIf { result.error == ParseError.NoRecipeFound },
                            photoPost = photoPost(result.error)
                        )
                    }
                }
            }
            if (result is ParseResult.Success) {
                restoreCook(result.recipe)
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

    /** Picks up where the cook left off ([CookSession.restore]), rescheduling running timers' alarms. */
    private fun restoreCook(recipe: Recipe) {
        val restored = cookSession.restore(recipe)
        restored.alarms.forEach(alarms::schedule)
        _uiState.update { it.copy(cook = restored.cook) }
        if (cookSession.hasRunningTimers) ensureTicking()
    }

    /** A shared Reddit post with no recipe text but a picture can have its photo read (#198). */
    private fun photoPost(error: ParseError): PhotoPost? {
        val post = error as? ParseError.NoTranscription ?: return null
        val link = shareUrl ?: return null
        return PhotoPost(link, post.title, post.imageUrls).takeIf { it.imageUrls.isNotEmpty() }
    }

    /**
     * Only a shared link that loaded but held no recipe is worth reporting: a block, being
     * offline or a failed fetch usually lifts on its own, and a saved recipe has no page to
     * report.
     */
    private fun reportSiteUrl(error: ParseError): String? {
        if (error != ParseError.NoRecipeFound) return null
        val link = shareUrl ?: return null
        return SiteReportLink.issueUrl(link, appInfo.platform, appInfo.appVersion)
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
        viewModelScope.launch { repository.setChecked(id, next) }
    }

    /**
     * The user's note, edited in place. The screen shows every keystroke at once; the write
     * waits until typing pauses for [NOTES_SAVE_DELAY_MS], so a sentence is one write rather
     * than one per letter. Leaving the screen before then still saves it (see [onCleared]).
     */
    fun onNotesChange(text: String) {
        _uiState.update { it.copy(notes = text) }
        val id = loadedRecipe()?.id ?: return
        pendingNotes = text
        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            delay(NOTES_SAVE_DELAY_MS)
            flushNotes(id)
        }
    }

    private suspend fun flushNotes(id: Long) {
        val text = pendingNotes ?: return
        pendingNotes = null
        // Once taken off pendingNotes it must land: leaving the screen mid-write can't drop it.
        withContext(NonCancellable) { repository.setNotes(id, text) }
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
        cookSession.stopTimers().forEach { alarms.cancel(id, it) }
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
                // Steps may have changed: drop this screen's alarms; restoreCook reschedules
                // whatever the saved progress still holds.
                cookSession.stopTimers().forEach { alarms.cancel(recipe.id, it) }
                _uiState.update { state ->
                    state.copy(
                        content = RecipeRenderer.content(result.recipe, state.renderSettings()),
                        checkedIngredients = result.recipe.checkedIngredients,
                        updatingFromSource = false,
                        asWrittenSteps = emptySet()
                    )
                }
                restoreCook(result.recipe)
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
        _uiState.update { state ->
            val content = state.content as? RecipeContent.Success ?: return@update state
            val scaled = RecipeRenderer.withServings(content, target, state.renderSettings()) ?: return@update state
            state.copy(content = scaled)
        }
        val content = _uiState.value.content as? RecipeContent.Success ?: return
        val scale = content.servings ?: return
        // The recipe's own yield is saved as no choice at all.
        val saved = scale.target.takeIf { it != scale.base }
        save { repository.setServingsTarget(content.recipe.id, saved) }
    }

    /**
     * The units dropdown on this screen. It is a global default, so it writes through; the
     * state updates at once too, rather than waiting for [AppPreferences.settings] to echo it.
     * The other preferences are set only in Settings and reach this screen through
     * [applySettings].
     */
    fun onUnitSystemChange(system: UnitSystem) {
        unitPreferences.unitSystem = system
        _uiState.update { rerender(it.copy(unitSystem = system)) }
    }

    /**
     * A change to the global defaults, from Settings or from this screen's own dropdown,
     * arriving while the recipe is open. Only a change that affects the text re-renders it:
     * [RecipeUiState.darkWhileCooking] is a display choice and leaves the recipe alone, and
     * scaled servings, ticks and cook progress are kept either way.
     */
    private fun applySettings(settings: AppSettings) {
        chef.onSetting(settings.chefMode)
        _uiState.update { state ->
            val next = state.copy(
                unitSystem = settings.unitSystem,
                convertLiquids = settings.convertLiquids,
                temperatureUnit = settings.temperatureUnit,
                darkWhileCooking = settings.darkWhileCooking,
                amountsInSteps = settings.amountsInSteps
            )
            val rendersDifferently = next.unitSystem != state.unitSystem ||
                next.convertLiquids != state.convertLiquids ||
                next.temperatureUnit != state.temperatureUnit ||
                next.amountsInSteps != state.amountsInSteps
            if (rendersDifferently) rerender(next) else next
        }
    }

    // Re-renders the loaded recipe under [state]'s settings, keeping its chosen servings.
    private fun rerender(state: RecipeUiState): RecipeUiState {
        val content = state.content as? RecipeContent.Success ?: return state
        return state.copy(content = RecipeRenderer.rerender(content, state.renderSettings(), chef.shortSteps))
    }

    // Chef mode's short steps as they now stand, rendered like the steps they stand for.
    private fun withShortSteps(state: RecipeUiState): RecipeUiState {
        val content = state.content as? RecipeContent.Success ?: return state
        return state.copy(content = RecipeRenderer.withShortSteps(content, chef.shortSteps, state.renderSettings()))
    }

    private fun RecipeUiState.renderSettings() = RecipeRenderer.Settings(
        unitSystem = unitSystem,
        convertLiquids = convertLiquids,
        temperatureUnit = temperatureUnit,
        amountsInSteps = amountsInSteps,
        decisions = chef.decisions
    )

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

    // --- Cook mode ---

    fun onCookStart() {
        val current = _uiState.value
        val loaded = current.content as? RecipeContent.Success ?: return
        val start = cookSession.start(current.cook, loaded.instructions.size)
        // A finished run starts fresh, and its timers go with it, so their alarms must too.
        start.stopped.forEach { alarms.cancel(loaded.recipe.id, it) }
        start.cook?.let { cook -> _uiState.update { it.copy(cook = cook) } }
        saveCook()
    }

    fun onCookExit() {
        updateCook(cookSession::exit)
        saveCook()
    }

    fun onIngredientsToggle() = updateCook(cookSession::toggleIngredients)

    /** Tapping a step makes it current. Only [onStepDone] ever advances or marks progress. */
    fun onStepSelected(index: Int) {
        updateCook { cookSession.select(it, index) }
        saveCook()
    }

    fun onStepDone() {
        _uiState.update { state ->
            val content = state.content as? RecipeContent.Success ?: return@update state
            val done = cookSession.done(state.cook, content.instructions.size)
            if (done.finished) {
                // The end of cooking (#147): what was ticked goes to the pantry's use-up sheet.
                // A finished run finishes again only after starting fresh, so once per cook.
                state.copy(cook = done.cook, cookFinished = cookSession.finishedCook(content, state.checkedIngredients))
            } else {
                state.copy(cook = done.cook)
            }
        }
        saveCook()
    }

    /** The screen has handed [RecipeUiState.cookFinished] on. */
    fun onCookFinishedHandled() = _uiState.update { it.copy(cookFinished = null) }

    private fun updateCook(change: (CookState) -> CookState) {
        _uiState.update { it.copy(cook = change(it.cook)) }
    }

    /** Saves the cook's place as it now stands ([CookSession.progress]). */
    private fun saveCook() {
        val id = loadedRecipe()?.id ?: return
        val progress = cookSession.progress(_uiState.value.cook)
        save { repository.setCookProgress(id, progress) }
    }

    private fun save(write: suspend () -> Unit) {
        pendingWrites.addLast(write)
        if (writer?.isActive != true) writer = viewModelScope.launch { drainWrites() }
    }

    // Main thread only. Each write runs NonCancellable, and nothing between them suspends
    // cancellably, so once started this empties the queue even if the screen is left.
    private suspend fun drainWrites() {
        while (pendingWrites.isNotEmpty()) {
            val write = pendingWrites.removeFirst()
            withContext(NonCancellable) { write() }
        }
    }

    // --- Step timers. Several can run at once, since steps overlap. ---

    fun onTimerStart(step: Int) {
        val content = _uiState.value.content as? RecipeContent.Success ?: return
        val total = content.stepTimerSeconds.getOrNull(step) ?: return
        applyTimer(cookSession.startTimer(_uiState.value.cook, step, total), content.recipe, step)
    }

    fun onTimerToggle(step: Int) {
        val recipe = loadedRecipe() ?: return
        val change = cookSession.toggleTimer(_uiState.value.cook, step) ?: return
        applyTimer(change, recipe, step)
    }

    // Shows a timer's change, schedules or cancels its alarm, and saves.
    private fun applyTimer(change: CookSession.TimerChange, recipe: Recipe, step: Int) {
        _uiState.update { it.copy(cook = change.cook) }
        val endsAt = change.endsAt
        if (endsAt != null) {
            alarms.schedule(StepAlarm(recipe.id, recipe.name, step, endsAt))
            ensureTicking()
        } else {
            alarms.cancel(recipe.id, step)
        }
        saveCook()
    }

    fun onTimerReset(step: Int) {
        val cook = cookSession.resetTimer(_uiState.value.cook, step) ?: return
        _uiState.update { it.copy(cook = cook) }
        loadedRecipe()?.id?.let { alarms.cancel(it, step) }
        saveCook()
    }

    fun onTimerAlerted(step: Int) {
        val cook = cookSession.alerted(_uiState.value.cook, step) ?: return
        _uiState.update { it.copy(cook = cook) }
    }

    // One loop for every running timer, ending by itself once none is; each tick recomputes
    // the remaining time from the wall clock ([CookSession.tick]) and writes nothing.
    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            while (cookSession.hasRunningTimers) {
                delay(250)
                cookSession.tick(_uiState.value.cook)?.let { cook -> _uiState.update { it.copy(cook = cook) } }
            }
        }
    }

    override fun onCleared() {
        cookSession.stopTimers()
        tickJob?.cancel()
        // viewModelScope is cancelled by now. A writer that had started finishes the queue
        // itself; one that never got to run is replaced here, after it, so order is kept.
        if (pendingWrites.isNotEmpty()) {
            val previous = writer
            CoroutineScope(Dispatchers.Unconfined).launch {
                previous?.join()
                drainWrites()
            }
        }
        // viewModelScope is already cancelled here, so a note typed just before leaving is
        // written on a scope of its own. One short write that nothing needs to wait for.
        val id = loadedRecipe()?.id
        if (id != null && pendingNotes != null) {
            CoroutineScope(Dispatchers.Unconfined).launch { flushNotes(id) }
        }
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
