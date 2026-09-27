package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.ShortStepRepository
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.DecisionCandidates
import com.example.recipeclipper.data.model.Decisions
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.Recipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The on-device model's part of one recipe screen (#169): Chef mode's short steps (#100) and the
 * decided count brackets (#104), two of [RecipeRenderer]'s inputs. It owns their repositories
 * and flags, and says when either input changed; the ViewModel then renders again. Every
 * repository is optional, so a screen (or a test) without the model shows the recipe as today.
 *
 * [keptRecipe] is the recipe on screen if it is saved, else null: short steps are cached per
 * saved recipe, so a recipe the free tier didn't keep (#107) gets none. [onShortSteps] is called
 * when [shortSteps] changed, [onDecisions] when [decisions] did.
 */
class ChefMode(
    private val scope: CoroutineScope,
    private val shortStepRepository: ShortStepRepository?,
    private val featureFlags: FeatureFlags?,
    private val decisionRepository: DecisionRepository?,
    private val keptRecipe: () -> Recipe?,
    private val onShortSteps: () -> Unit,
    private val onDecisions: () -> Unit
) {

    /** The loaded recipe's short steps as saved, before rendering; empty while Chef mode is off. */
    var shortSteps: List<String?> = emptyList()
        private set

    /** The model's decided count brackets (#104); NONE (today's rendering) until one lands. */
    var decisions: Decisions = Decisions.NONE
        private set

    // On when both its flag and its setting are.
    private var flagOn = false
    private var settingOn = false
    private var on = false
    private var job: Job? = null

    /** Follows the model's decisions for as long as [scope] lives. */
    fun observeDecisions() {
        val repository = decisionRepository ?: return
        scope.launch {
            repository.observe().collect {
                if (it != decisions) {
                    decisions = it
                    onDecisions()
                }
            }
        }
    }

    /** Follows the `chefMode` flag for as long as [scope] lives. */
    fun observeFlag() {
        val flags = featureFlags ?: return
        scope.launch {
            flags.values.collect {
                flagOn = it.isOn(Flag.CHEF_MODE)
                changed()
            }
        }
    }

    /** The Chef mode setting, as Settings has it now. */
    fun onSetting(on: Boolean) {
        settingOn = on
        changed()
    }

    private fun changed() {
        val next = flagOn && settingOn
        if (next == on) return
        on = next
        start()
    }

    /**
     * (Re)starts the loaded recipe's short steps: the saved ones show at once, and the missing
     * ones are written one by one, the steps showing as written meanwhile. Nothing happens on a
     * phone or recipe language the model can't do; Chef mode off clears them.
     */
    fun start() {
        job?.cancel()
        job = null
        shortSteps = emptyList()
        onShortSteps()
        val recipe = keptRecipe() ?: return
        val repository = shortStepRepository ?: return
        if (!on) return
        job = scope.launch {
            val support = repository.support()
            if (support !is ChefSupport.Available || !support.covers(LanguageWords.forRecipe(recipe)?.language)) {
                return@launch
            }
            launch { repository.fill(recipe) }
            repository.observe(recipe).collect { shorts ->
                shortSteps = shorts
                onShortSteps()
            }
        }
    }

    /**
     * Asks the model about [content]'s count brackets (#104), in the background: lines show as
     * today until an answer lands. Only a recipe with a servings stepper can scale, and only with
     * `aiCountBrackets` on (#127).
     */
    fun askCountBrackets(content: RecipeContent.Success) {
        if (featureFlags?.isOn(Flag.AI_COUNT_BRACKETS) != true) return
        val repository = decisionRepository ?: return
        if (content.servings == null) return
        val questions = DecisionCandidates.countBrackets(content.recipe.ingredients, content.words)
        if (questions.isNotEmpty()) scope.launch { repository.decide(questions) }
    }
}
