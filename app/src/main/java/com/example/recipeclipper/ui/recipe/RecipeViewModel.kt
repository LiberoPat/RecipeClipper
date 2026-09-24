package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.AppInfo
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.local.AppSettings
import com.example.recipeclipper.data.model.IngredientRendering
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeShareText
import com.example.recipeclipper.data.model.Servings
import com.example.recipeclipper.data.model.SiteReportLink
import com.example.recipeclipper.data.model.ServingsScale
import com.example.recipeclipper.data.model.SourceDomain
import com.example.recipeclipper.data.model.StepTimers
import com.example.recipeclipper.data.model.TemperatureConverter
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.data.model.UnitSystem
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
import kotlin.math.ceil

/**
 * One recipe screen. It is opened either by id (from history, home or a list) or by URL
 * (the share target); the navigation arguments arrive through [savedStateHandle].
 */
@HiltViewModel
class RecipeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecipeRepository,
    private val unitPreferences: AppPreferences,
    private val clock: Clock,
    private val connectivity: Connectivity,
    private val appInfo: AppInfo
) : ViewModel() {

    private val recipeId: Long? = savedStateHandle.get<Long>(RECIPE_ID_ARG)?.takeIf { it > 0 }
    private val shareUrl: String? = savedStateHandle.get<String>(URL_ARG)?.takeIf { it.isNotBlank() }

    // Seeded synchronously so the first render already uses the user's units; kept current
    // afterwards by collecting [AppPreferences.settings] in [init].
    private val _uiState = unitPreferences.current.let {
        MutableStateFlow(
            RecipeUiState(
                unitSystem = it.unitSystem,
                convertLiquids = it.convertLiquids,
                temperatureUnit = it.temperatureUnit,
                darkWhileCooking = it.darkWhileCooking
            )
        )
    }
    val uiState: StateFlow<RecipeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var reconnectJob: Job? = null

    // The note as typed but not yet written, and the debounced write that will save it.
    private var pendingNotes: String? = null
    private var notesJob: Job? = null

    // Step index -> wall-clock time its timer ends. Only running timers are in here.
    private val deadlines = mutableMapOf<Int, Long>()
    private var tickJob: Job? = null

    init {
        // Settings can change a default while this screen is alive underneath it; this keeps
        // the open recipe in step instead of showing the units it was opened with (#24).
        viewModelScope.launch { unitPreferences.settings.collect(::applySettings) }
        load()
    }

    // --- Loading the recipe ---

    fun onRetry() = load()

    private fun load() {
        loadJob?.cancel()
        reconnectJob?.cancel()
        _uiState.update { it.copy(content = RecipeContent.Loading, reportSiteUrl = null) }
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
                        content = successContent(
                            result.recipe, state.unitSystem, state.convertLiquids, state.temperatureUnit
                        ),
                        checkedIngredients = result.recipe.checkedIngredients,
                        notes = result.recipe.notes.orEmpty()
                    )
                    is ParseResult.Error -> state.copy(
                        content = RecipeContent.Error(result.error),
                        reportSiteUrl = reportSiteUrl(result.error)
                    )
                }
            }
            if (result is ParseResult.Error && result.error.reloadsOnReconnect) reloadOnReconnect()
        }
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
        val next = _uiState.value.checkedIngredients.let { if (checked) it + index else it - index }
        _uiState.update { it.copy(checkedIngredients = next) }
        // Saved as they change, so closing the app mid-cook doesn't lose the ticks.
        val id = (_uiState.value.content as? RecipeContent.Success)?.recipe?.id ?: return
        viewModelScope.launch { repository.setChecked(id, next) }
    }

    /**
     * The user's note, edited in place. The screen shows every keystroke at once; the write
     * waits until typing pauses for [NOTES_SAVE_DELAY_MS], so a sentence is one write rather
     * than one per letter. Leaving the screen before then still saves it (see [onCleared]).
     */
    fun onNotesChange(text: String) {
        _uiState.update { it.copy(notes = text) }
        val id = (_uiState.value.content as? RecipeContent.Success)?.recipe?.id ?: return
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
        val id = (_uiState.value.content as? RecipeContent.Success)?.recipe?.id ?: return
        viewModelScope.launch {
            repository.delete(id)
            _uiState.update { it.copy(deleted = true) }
        }
    }

    fun onServingsChange(target: Int) {
        _uiState.update { state ->
            val content = state.content as? RecipeContent.Success ?: return@update state
            val servings = content.servings ?: return@update state
            val scale = servings.copy(target = target.coerceIn(1, Servings.MAX))
            state.copy(
                content = content.copy(
                    servings = scale,
                    ingredients = render(content.recipe, content.words, scale, state.unitSystem, state.convertLiquids)
                )
            )
        }
    }

    /**
     * The units dropdown on this screen. It is a global default, so it writes through; the
     * state updates at once too, rather than waiting for [AppPreferences.settings] to echo it.
     * The other preferences are set only in Settings and reach this screen through
     * [applySettings].
     */
    fun onUnitSystemChange(system: UnitSystem) {
        unitPreferences.unitSystem = system
        updateUnits { it.copy(unitSystem = system) }
    }

    /**
     * A change to the global defaults, from Settings or from this screen's own dropdown,
     * arriving while the recipe is open. Only a change that affects the text re-renders it:
     * [RecipeUiState.darkWhileCooking] is a display choice and leaves the recipe alone, and
     * scaled servings, ticks and cook progress are kept either way.
     */
    private fun applySettings(settings: AppSettings) {
        _uiState.update { state ->
            val next = state.copy(
                unitSystem = settings.unitSystem,
                convertLiquids = settings.convertLiquids,
                temperatureUnit = settings.temperatureUnit,
                darkWhileCooking = settings.darkWhileCooking
            )
            val rendersDifferently = next.unitSystem != state.unitSystem ||
                next.convertLiquids != state.convertLiquids ||
                next.temperatureUnit != state.temperatureUnit
            if (rendersDifferently) rerender(next) else next
        }
    }

    private fun updateUnits(change: (RecipeUiState) -> RecipeUiState) {
        _uiState.update { rerender(change(it)) }
    }

    // Re-renders the loaded recipe under [state]'s units, keeping its chosen servings.
    private fun rerender(state: RecipeUiState): RecipeUiState {
        val content = state.content as? RecipeContent.Success ?: return state
        return state.copy(
            content = content.copy(
                ingredients = render(
                    content.recipe, content.words, content.servings, state.unitSystem, state.convertLiquids
                ),
                instructions = renderInstructions(content.recipe, content.words, state.temperatureUnit)
            )
        )
    }

    // --- Cook mode ---

    fun onCookStart() {
        _uiState.update { state ->
            val content = state.content as? RecipeContent.Success ?: return@update state
            val count = content.instructions.size
            if (count == 0) return@update state
            // A finished run starts fresh; anything else resumes where it was left.
            val cook = if (state.cook.doneSteps.size >= count) CookState() else state.cook
            state.copy(cook = cook.copy(active = true, currentStep = cook.currentStep.coerceIn(0, count - 1)))
        }
    }

    fun onCookExit() = updateCook { it.copy(active = false) }

    fun onIngredientsToggle() = updateCook { it.copy(ingredientsExpanded = !it.ingredientsExpanded) }

    /** Tapping a step makes it current. Only [onStepDone] ever advances or marks progress. */
    fun onStepSelected(index: Int) = updateCook { it.copy(currentStep = index) }

    fun onStepDone() {
        _uiState.update { state ->
            val content = state.content as? RecipeContent.Success ?: return@update state
            val cook = state.cook
            val count = content.instructions.size
            val done = cook.doneSteps + cook.currentStep
            // Next unfinished step after this one, else the earliest one skipped, else finished.
            val next = (cook.currentStep + 1 until count).firstOrNull { it !in done }
                ?: (0 until count).firstOrNull { it !in done }
            state.copy(
                cook = if (next != null) {
                    cook.copy(doneSteps = done, currentStep = next)
                } else {
                    cook.copy(doneSteps = done, active = false)
                }
            )
        }
    }

    private fun updateCook(change: (CookState) -> CookState) {
        _uiState.update { it.copy(cook = change(it.cook)) }
    }

    // --- Step timers. Several can run at once, since steps overlap. ---

    fun onTimerStart(step: Int) {
        val content = _uiState.value.content as? RecipeContent.Success ?: return
        val total = content.stepTimerSeconds.getOrNull(step) ?: return
        deadlines[step] = clock.now() + total * 1000L
        setTimer(step, StepTimer(total, total, running = true))
        ensureTicking()
    }

    fun onTimerToggle(step: Int) {
        val timer = _uiState.value.cook.timers[step] ?: return
        if (timer.running) {
            deadlines.remove(step)
            setTimer(step, timer.copy(running = false))
        } else if (timer.remainingSeconds > 0) {
            deadlines[step] = clock.now() + timer.remainingSeconds * 1000L
            setTimer(step, timer.copy(running = true))
            ensureTicking()
        }
    }

    fun onTimerReset(step: Int) {
        val timer = _uiState.value.cook.timers[step] ?: return
        deadlines.remove(step)
        setTimer(step, StepTimer(timer.totalSeconds, timer.totalSeconds, running = false))
    }

    fun onTimerAlerted(step: Int) {
        val timer = _uiState.value.cook.timers[step] ?: return
        setTimer(step, timer.copy(alerted = true))
    }

    private fun setTimer(step: Int, timer: StepTimer) {
        updateCook { it.copy(timers = it.timers + (step to timer)) }
    }

    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            while (deadlines.isNotEmpty()) {
                delay(250)
                val now = clock.now()
                val remaining = deadlines.mapValues { (_, end) ->
                    ceil((end - now) / 1000.0).toInt().coerceAtLeast(0)
                }
                remaining.filterValues { it == 0 }.keys.forEach { deadlines.remove(it) }
                _uiState.update { state ->
                    val timers = state.cook.timers.toMutableMap()
                    var changed = false
                    for ((step, seconds) in remaining) {
                        val timer = timers[step] ?: continue
                        if (timer.remainingSeconds != seconds) {
                            timers[step] = timer.copy(remainingSeconds = seconds, running = seconds > 0)
                            changed = true
                        }
                    }
                    if (changed) state.copy(cook = state.cook.copy(timers = timers)) else state
                }
            }
        }
    }

    override fun onCleared() {
        deadlines.clear()
        tickJob?.cancel()
        // viewModelScope is already cancelled here, so a note typed just before leaving is
        // written on a scope of its own. One short write that nothing needs to wait for.
        val id = (_uiState.value.content as? RecipeContent.Success)?.recipe?.id
        if (id != null && pendingNotes != null) {
            CoroutineScope(Dispatchers.Unconfined).launch { flushNotes(id) }
        }
    }

    // --- Turning a recipe into what the screen shows ---

    private fun successContent(
        recipe: Recipe,
        system: UnitSystem,
        convertLiquids: Boolean,
        temperatureUnit: TemperatureUnit
    ): RecipeContent.Success {
        // The recipe's language picks the words, never the phone's (#14).
        val words = LanguageWords.forRecipe(recipe)
        val base = Servings.parse(recipe.yield, words)
        val servings = base?.let { ServingsScale(base = it, target = it) }
        return RecipeContent.Success(
            recipe = recipe,
            servings = servings,
            ingredients = render(recipe, words, servings, system, convertLiquids),
            instructions = renderInstructions(recipe, words, temperatureUnit),
            stepTimerSeconds = recipe.instructions.map { StepTimers.parse(it, words) },
            sourceDomain = SourceDomain.of(recipe.sourceUrl),
            words = words
        )
    }

    // Scale first, then convert, so a converted amount always matches the chosen servings.
    private fun render(
        recipe: Recipe,
        words: LanguageWords?,
        servings: ServingsScale?,
        system: UnitSystem,
        convertLiquids: Boolean
    ): List<String> {
        val factor = servings?.let { it.target.toDouble() / it.base } ?: 1.0
        return IngredientRendering.render(recipe.ingredients, factor, system, convertLiquids, words)
    }

    // Instructions aren't scaled (a step can mention any number), but oven temperatures
    // follow the chosen temperature unit — independent of the ingredient unit system.
    private fun renderInstructions(recipe: Recipe, words: LanguageWords?, temperatureUnit: TemperatureUnit): List<String> =
        recipe.instructions.map { TemperatureConverter.convert(it, temperatureUnit, words) }

    companion object {
        const val RECIPE_ID_ARG = "recipeId"
        const val URL_ARG = "url"

        /** How long typing must pause before the note is written. */
        const val NOTES_SAVE_DELAY_MS = 500L
    }
}
