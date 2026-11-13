package com.example.recipeclipper.ui.print

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.model.RecipePrint
import com.example.recipeclipper.di.DefaultDispatcher
import com.example.recipeclipper.ui.recipe.RecipeRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What to draw (#318): one recipe for the print dialog, or a cookbook PDF for the share sheet. */
sealed class PrintJob {
    data class Recipe(val title: String, val blocks: List<RecipePrint.Block>) : PrintJob()

    /** [recipes] are each recipe's name with its card, in the list's order; [contents] heads the contents. */
    data class Cookbook(
        val title: String,
        val contents: String,
        val recipes: List<Pair<String, List<RecipePrint.Block>>>
    ) : PrintJob()
}

/**
 * [busy] while a job is built or drawn (the screen shows it, #331); [job] is ready for the
 * screen to draw; [failed] once it couldn't be made.
 */
data class PrintUiState(val busy: Boolean = false, val job: PrintJob? = null, val failed: Boolean = false)

/**
 * Print or save as PDF (#318). The recipe screen hands over its card, already built from what
 * it shows; a list's cookbook renders each recipe as the reading view would open it (its chosen
 * servings, the cook's units), without the note: a cookbook is for giving, as Send as file
 * leaves the note out. Drawing the PDF is the screen's ([PrintEffect]): it needs a `Context`.
 * Leaving the screen ([onLeave]) drops the job, so nothing pops up on return (#331).
 */
@HiltViewModel
class PrintViewModel @Inject constructor(
    private val recipes: RecipeRepository,
    private val preferences: AppPreferences,
    private val decisions: DecisionRepository,
    /** Where a cookbook's recipes are rendered, off the main thread (#331). */
    @DefaultDispatcher private val rendering: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrintUiState())
    val uiState: StateFlow<PrintUiState> = _uiState.asStateFlow()

    private var building: Job? = null

    fun printRecipe(title: String, blocks: List<RecipePrint.Block>) {
        if (_uiState.value.busy) return
        _uiState.value = PrintUiState(busy = true, job = PrintJob.Recipe(title, blocks))
    }

    /** The list named [title], its recipes [recipeIds] in order; a recipe gone meanwhile is skipped. */
    fun makeCookbook(title: String, recipeIds: List<Long>, labels: RecipePrint.Labels, contents: String) {
        if (_uiState.value.busy || recipeIds.isEmpty()) return
        _uiState.value = PrintUiState(busy = true)
        building = viewModelScope.launch {
            val current = preferences.current
            val settings = RecipeRenderer.Settings(
                unitSystem = current.unitSystem,
                convertLiquids = current.convertLiquids,
                temperatureUnit = current.temperatureUnit,
                decisions = decisions.current()
            )
            // One query for the lot, then each rendered off the main thread.
            val found = recipes.byIds(recipeIds).associateBy { it.id }
            val cards = withContext(rendering) {
                recipeIds.mapNotNull(found::get).map { recipe ->
                    val shown = RecipeRenderer.content(recipe, settings)
                    recipe.name to RecipePrint.blocks(
                        recipe, shown.servings, shown.ingredients, shown.instructions, shown.sourceDomain, note = null, labels = labels
                    )
                }
            }
            _uiState.value = if (cards.isEmpty()) {
                PrintUiState(failed = true)
            } else {
                PrintUiState(busy = true, job = PrintJob.Cookbook(title, contents, cards))
            }
        }
    }

    /** The screen drew the job (and opened the dialog or the share sheet). */
    fun onDone() {
        _uiState.value = PrintUiState()
    }

    /** The screen couldn't draw the job. */
    fun onFailed() {
        _uiState.value = PrintUiState(failed = true)
    }

    /** The failure line has gone; a job started meanwhile stays. */
    fun onFailureShown() {
        _uiState.update { if (it.failed) PrintUiState() else it }
    }

    /** The screen was left: whatever was being built or drawn is dropped, and nothing shows on return. */
    fun onLeave() {
        building?.cancel()
        building = null
        _uiState.value = PrintUiState()
    }
}
